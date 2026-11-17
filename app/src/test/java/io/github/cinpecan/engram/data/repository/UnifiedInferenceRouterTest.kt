package io.github.cinpecan.engram.data.repository

import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.edgemnn.InferenceTelemetry
import io.github.cinpecan.engram.edgemnn.StreamChunk
import io.github.cinpecan.engram.edgemnn.UnloadReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedInferenceRouterTest {

    private class FakeEdgeEngine(
        override val isBackendAvailable: Boolean,
        initialState: EngineState,
        private val emittedJson: String? = null,
    ) : EdgeAiEngine {
        var generateCalled = false
        override val state: StateFlow<EngineState> = MutableStateFlow(initialState)
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)

        override suspend fun loadModel(modelId: String, modelDirPath: String) = Result.success(Unit)

        override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {
            generateCalled = true
            val text = emittedJson ?: error("No MNN model loaded")
            val mid = text.length / 2
            emit(StreamChunk.Delta(text.substring(0, mid)))
            emit(StreamChunk.Delta(text.substring(mid)))
            emit(
                StreamChunk.Completed(
                    fullText = text,
                    telemetry = InferenceTelemetry(
                        engineLabel = "MNN-LLM (ARM64)",
                        modelId = "qwen2.5-0.5b-instruct-mnn",
                        ttftMs = 140L,
                        totalMs = 400L,
                        generatedTokens = 12,
                        tokensPerSec = 30.0,
                        residentMemoryMb = 412.0,
                        isRealMnn = true,
                    ),
                ),
            )
        }

        override suspend fun unload(reason: UnloadReason): Long = 0L
        override fun cancelActiveGeneration() = Unit
        override fun onTrimMemory(level: Int) = Unit
        override fun currentMemoryBytes(): Long = 0L
    }

    private fun buildMockSseOkHttp(sseBody: String?, statusCode: Int = 200): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (sseBody == null) {
                    throw java.io.IOException("Gateway unreachable")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode == 200) "OK" else "Service Unavailable")
                    .body(sseBody.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
            .build()

    @Test
    fun offlineModeStreamsRealMnnOrFallsBackHonestlyToRules() = runTest {
        val validJson = """
            {"summary":"老李向张姐对齐采购合同条款。","tags":["商务合同"],"calendarProposal":null,"relations":[{"source":"老李","target":"张姐","relation":"对齐条款","bidirectional":true}]}
        """.trimIndent()
        val realEdge = FakeEdgeEngine(
            isBackendAvailable = true,
            initialState = EngineState.Ready("qwen2.5-0.5b-instruct-mnn", "MNN-LLM (ARM64)", 400L * 1024 * 1024),
            emittedJson = validJson,
        )
        val router = UnifiedInferenceRouter(realEdge, CloudSseClient(buildMockSseOkHttp(null)), MemoryAnalyzer())

        val events = router.distillMemory(
            content = "老李和法务张姐对齐合同条款",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.OFFLINE_EDGE,
            serverBaseUrl = "http://127.0.0.1:8787",
        ).toList()

        assertTrue(realEdge.generateCalled)
        assertTrue(events.any { it is DistillationEvent.StreamingPreview })
        val completed = events.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.MNN_EDGE, completed.source)
        assertTrue(completed.telemetryBadge.contains("MNN 0.5B"))
        assertTrue(completed.telemetryBadge.contains("TTFT 140ms"))

        // When native MNN backend is unavailable, never pretend MNN ran
        val unavailableEdge = FakeEdgeEngine(
            isBackendAvailable = false,
            initialState = EngineState.Unloaded,
        )
        val fallbackRouter = UnifiedInferenceRouter(
            unavailableEdge,
            CloudSseClient(buildMockSseOkHttp(null)),
            MemoryAnalyzer(),
        )
        val fallbackEvents = fallbackRouter.distillMemory(
            content = "老李和法务张姐对齐合同条款",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.OFFLINE_EDGE,
            serverBaseUrl = "http://127.0.0.1:8787",
        ).toList()

        assertFalse(unavailableEdge.generateCalled)
        val fallbackCompleted = fallbackEvents.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.LOCAL_RULE_FALLBACK, fallbackCompleted.source)
        assertEquals("本地规则兜底", fallbackCompleted.telemetryBadge)
    }

    @Test
    fun cloudModeStreamsSseAndFallsBackGracefullyOnHttpError() = runTest {
        val payloadJson = """{"summary":"明日评审补充条款。","tags":["日程跟进"],"calendarProposal":null,"relations":[]}"""
        val escaped = payloadJson.replace("\"", "\\\"")
        val sseStream = buildString {
            append("""data: {"delta":"$escaped","done":false,"model":"gpt-4o-mini","latencyMs":12}""").append("\n\n")
            append("""data: {"delta":"","done":true,"model":"gpt-4o-mini","latencyMs":48}""").append("\n\n")
            append("data: [DONE]\n\n")
        }

        val edge = FakeEdgeEngine(isBackendAvailable = false, initialState = EngineState.Unloaded)
        val cloudRouter = UnifiedInferenceRouter(edge, CloudSseClient(buildMockSseOkHttp(sseStream)), MemoryAnalyzer())

        val events = cloudRouter.distillMemory(
            content = "明天评审补充条款",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.CLOUD_COLLAB,
            serverBaseUrl = "http://127.0.0.1:8787",
        ).toList()

        val completed = events.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.CLOUD_SSE, completed.source)
        assertTrue(completed.telemetryBadge.startsWith("Cloud SSE"))

        // 503 unconfigured gateway should degrade to local rule fallback transparently
        val unconfiguredRouter = UnifiedInferenceRouter(
            edge,
            CloudSseClient(buildMockSseOkHttp("{\"detail\":\"not configured\"}", statusCode = 503)),
            MemoryAnalyzer(),
        )
        val degradedEvents = unconfiguredRouter.distillMemory(
            content = "明天评审补充条款",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.CLOUD_COLLAB,
            serverBaseUrl = "http://127.0.0.1:8787",
        ).toList()

        val degradedCompleted = degradedEvents.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.LOCAL_RULE_FALLBACK, degradedCompleted.source)
        assertEquals("云端未连接 · 本地规则兜底", degradedCompleted.telemetryBadge)
    }
}
