package io.github.cinpecan.engram.edgemnn

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MnnEngineManagerTest {

    private class FakeNativeBridge : NativeBridge {
        private val nextHandle = AtomicLong(100L)
        val releaseCount = AtomicInteger(0)
        val activeConcurrency = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        var simulatedHeapBytes: Long = 512L * 1024 * 1024

        override fun hasRealMnnBackend(): Boolean = false

        override fun createSession(modelDirPath: String): Long {
            simulatedHeapBytes = 768L * 1024 * 1024
            return nextHandle.getAndIncrement()
        }

        override fun requestCancel(sessionHandle: Long) = Unit

        override fun releaseSession(sessionHandle: Long) {
            releaseCount.incrementAndGet()
            simulatedHeapBytes = 128L * 1024 * 1024
        }

        override fun generateStream(
            sessionHandle: Long,
            prompt: String,
            maxTokens: Int,
            callback: NativeStreamCallback,
        ) {
            val current = activeConcurrency.incrementAndGet()
            maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, current) }
            try {
                Thread.sleep(10)
                callback.onToken("测试")
                callback.onToken("输出")
                callback.onComplete(ttftMs = 12L, totalMs = 24L, generatedTokens = 2)
            } finally {
                activeConcurrency.decrementAndGet()
            }
        }

        override fun embed(sessionHandle: Long, text: String, dims: Int): FloatArray {
            val current = activeConcurrency.incrementAndGet()
            maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, current) }
            try {
                return FloatArray(dims) { if (it == 0) 1f else 0f }
            } finally {
                activeConcurrency.decrementAndGet()
            }
        }
    }

    @Test
    fun loadAndManualUnloadTransitionsStateAndReclaimsMemory() = runTest {
        val bridge = FakeNativeBridge()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = MnnEngineManager(
            nativeBridge = bridge,
            memoryProvider = { bridge.simulatedHeapBytes },
            serialDispatcher = dispatcher,
            lifecycleScope = this,
            coalescer = TokenStreamCoalescer(windowMs = 10L),
        )

        assertEquals(EngineState.Unloaded, manager.state.value)
        val loadResult = manager.loadModel("qwen-0.5b", "/tmp/models/qwen")
        assertTrue(loadResult.isSuccess)
        assertTrue(manager.state.value is EngineState.Ready)

        val chunks = manager.streamGenerate("hello").toList()
        val completed = chunks.filterIsInstance<StreamChunk.Completed>().single()
        assertEquals("测试输出", completed.fullText)
        assertEquals(12L, completed.telemetry.ttftMs)

        val reclaimed = manager.unload(UnloadReason.USER_REQUESTED)
        assertEquals(EngineState.Unloaded, manager.state.value)
        assertEquals(1, bridge.releaseCount.get())
        assertEquals(640L * 1024 * 1024, reclaimed)
    }

    @Test
    fun serializesConcurrentJniCalls() = runTest {
        val bridge = FakeNativeBridge()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = MnnEngineManager(
            nativeBridge = bridge,
            memoryProvider = { bridge.simulatedHeapBytes },
            serialDispatcher = dispatcher,
            lifecycleScope = this,
        )

        manager.loadModel("qwen-0.5b", "/tmp/models/qwen")
        val jobs = (1..4).map { idx ->
            async {
                if (idx % 2 == 0) {
                    manager.embedText("query-$idx", dims = 16)
                } else {
                    manager.streamGenerate("prompt-$idx").toList()
                }
            }
        }
        jobs.awaitAll()

        assertEquals(1, bridge.maxObservedConcurrency.get())
    }

    @Test
    fun onTrimMemoryUnloadsOnCriticalPressureAndUiHiddenTimeout() = runTest {
        val bridge = FakeNativeBridge()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = MnnEngineManager(
            nativeBridge = bridge,
            memoryProvider = { bridge.simulatedHeapBytes },
            serialDispatcher = dispatcher,
            lifecycleScope = this,
            uiHiddenGraceMs = 1_000L,
        )

        manager.loadModel("qwen-0.5b", "/tmp/models/qwen")
        manager.onTrimMemory(MnnEngineManager.TRIM_MEMORY_UI_HIDDEN)
        advanceTimeBy(500L)
        assertTrue(manager.state.value is EngineState.Ready)

        advanceTimeBy(600L)
        advanceUntilIdle()
        assertEquals(EngineState.Unloaded, manager.state.value)

        manager.loadModel("qwen-0.5b", "/tmp/models/qwen")
        manager.onTrimMemory(MnnEngineManager.TRIM_MEMORY_RUNNING_CRITICAL)
        advanceUntilIdle()
        assertEquals(EngineState.Unloaded, manager.state.value)
    }
}
