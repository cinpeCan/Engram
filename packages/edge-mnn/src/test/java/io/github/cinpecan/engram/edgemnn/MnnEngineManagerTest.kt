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

    private class FakeNativeBridge(
        private val backendAvailable: Boolean = true,
    ) : NativeBridge {
        private val nextHandle = AtomicLong(100L)
        val releaseCount = AtomicInteger(0)
        val activeConcurrency = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        var simulatedRssBytes: Long = 512L * 1024 * 1024
        val createdModelDirs = mutableListOf<String>()

        override fun hasRealMnnBackend(): Boolean = backendAvailable

        override fun createSession(modelDirPath: String): Long {
            if (!backendAvailable || modelDirPath.isBlank()) return 0L
            createdModelDirs.add(modelDirPath)
            simulatedRssBytes = 768L * 1024 * 1024
            return nextHandle.getAndIncrement()
        }

        override fun requestCancel(sessionHandle: Long) = Unit

        override fun releaseSession(sessionHandle: Long) {
            releaseCount.incrementAndGet()
            simulatedRssBytes = 128L * 1024 * 1024
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
    }

    @Test
    fun refusesBlankDirAndMissingBackendWithoutFakingReadyState() = runTest {
        val noBackendBridge = FakeNativeBridge(backendAvailable = false)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noBackendManager = MnnEngineManager(
            nativeBridge = noBackendBridge,
            memoryProvider = { noBackendBridge.simulatedRssBytes },
            serialDispatcher = dispatcher,
            lifecycleScope = this,
        )

        val missingBackendResult = noBackendManager.loadModel("qwen-0.5b", "/tmp/models/qwen")
        assertTrue(missingBackendResult.isFailure)
        assertTrue(noBackendManager.state.value is EngineState.Error)
        assertTrue(noBackendBridge.createdModelDirs.isEmpty())

        val validBridge = FakeNativeBridge(backendAvailable = true)
        val blankDirManager = MnnEngineManager(
            nativeBridge = validBridge,
            memoryProvider = { validBridge.simulatedRssBytes },
            serialDispatcher = dispatcher,
            lifecycleScope = this,
        )

        val blankDirResult = blankDirManager.loadModel("qwen-0.5b", "")
        assertTrue(blankDirResult.isFailure)
        assertTrue(blankDirManager.state.value is EngineState.Error)
        assertTrue(validBridge.createdModelDirs.isEmpty())

        val streamError = runCatching {
            blankDirManager.streamGenerate("hello").toList()
        }.exceptionOrNull()
        assertTrue(streamError is IllegalStateException)
    }

    @Test
    fun loadAndManualUnloadTransitionsStateAndReclaimsMemory() = runTest {
        val bridge = FakeNativeBridge()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = MnnEngineManager(
            nativeBridge = bridge,
            memoryProvider = { bridge.simulatedRssBytes },
            serialDispatcher = dispatcher,
            lifecycleScope = this,
            coalescer = TokenStreamCoalescer(windowMs = 10L),
        )

        assertEquals(EngineState.Unloaded, manager.state.value)
        val loadResult = manager.loadModel("qwen-0.5b", "/tmp/models/qwen")
        assertTrue(loadResult.isSuccess)
        val readyState = manager.state.value as EngineState.Ready
        assertEquals(768L * 1024 * 1024, readyState.residentMemoryBytes)
        assertEquals(256L * 1024 * 1024, readyState.modelFootprintBytes)

        val chunks = manager.streamGenerate("hello").toList()
        val completed = chunks.filterIsInstance<StreamChunk.Completed>().single()
        assertEquals("测试输出", completed.fullText)
        assertEquals(12L, completed.telemetry.ttftMs)
        assertTrue(completed.telemetry.isRealMnn)

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
            memoryProvider = { bridge.simulatedRssBytes },
            serialDispatcher = dispatcher,
            lifecycleScope = this,
        )

        manager.loadModel("qwen-0.5b", "/tmp/models/qwen")
        val jobs = (1..4).map { idx ->
            async {
                manager.streamGenerate("prompt-$idx").toList()
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
            memoryProvider = { bridge.simulatedRssBytes },
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

    @Test
    fun lazyReloadAfterTrimMemoryRetainsConfiguredModelDir() = runTest {
        val bridge = FakeNativeBridge()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = MnnEngineManager(
            nativeBridge = bridge,
            memoryProvider = { bridge.simulatedRssBytes },
            serialDispatcher = dispatcher,
            lifecycleScope = this,
            coalescer = TokenStreamCoalescer(windowMs = 10L),
        )

        manager.loadModel("qwen-0.5b", "/tmp/models/qwen")
        manager.onTrimMemory(MnnEngineManager.TRIM_MEMORY_RUNNING_CRITICAL)
        advanceUntilIdle()
        assertEquals(EngineState.Unloaded, manager.state.value)

        val chunks = manager.streamGenerate("hi").toList()
        val completed = chunks.filterIsInstance<StreamChunk.Completed>().single()
        assertEquals("测试输出", completed.fullText)
        assertEquals(listOf("/tmp/models/qwen", "/tmp/models/qwen"), bridge.createdModelDirs)
        assertEquals("qwen-0.5b", completed.telemetry.modelId)
    }
}
