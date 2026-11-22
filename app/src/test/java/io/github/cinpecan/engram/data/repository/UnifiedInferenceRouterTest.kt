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
        var lastSystemPrompt: String? = null
        var lastUserPrompt: String? = null
        override val state: StateFlow<EngineState> = MutableStateFlow(initialState)
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)

        override suspend fun loadModel(modelId: String, modelDirPath: String) = Result.success(Unit)

        override fun streamGenerate(
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
        ): Flow<StreamChunk> {
            lastSystemPrompt = systemPrompt
            lastUserPrompt = userPrompt
            return streamGenerate(
                listOf(systemPrompt, userPrompt).filter { it.isNotEmpty() }.joinToString("\n\n"),
                maxTokens,
            )
        }

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
            {"summary":"Marcus aligns the supply agreement clauses with Sarah.","tags":["Legal"],"calendarProposal":null,"relations":[{"source":"Marcus Cole","target":"Sarah Jenkins","relation":"aligns clauses","bidirectional":true}]}
        """.trimIndent()
        val realEdge = FakeEdgeEngine(
            isBackendAvailable = true,
            initialState = EngineState.Ready("qwen2.5-0.5b-instruct-mnn", "MNN-LLM (ARM64)", 400L * 1024 * 1024),
            emittedJson = validJson,
        )
        val router = UnifiedInferenceRouter(realEdge, CloudSseClient(buildMockSseOkHttp(null)), MemoryAnalyzer())

        val events = router.distillMemory(
            content = "Marcus Cole and Sarah Jenkins align the contract clauses",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.OFFLINE_EDGE,
            serverBaseUrl = "http://127.0.0.1:8787",
        ).toList()

        assertTrue(realEdge.generateCalled)
        assertEquals("", realEdge.lastSystemPrompt)
        assertTrue(realEdge.lastUserPrompt.orEmpty().contains("Marcus Cole and Sarah Jenkins align the contract clauses"))
        assertTrue(events.any { it is DistillationEvent.StreamingPreview })
        val completed = events.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.MNN_EDGE, completed.source)
        assertTrue(completed.telemetryBadge.startsWith("Qwen2.5 0.5B · MNN"))
        assertTrue(completed.telemetryBadge.contains("TTFT 140ms"))

        // When the native MNN backend is unavailable, never pretend MNN ran
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
            content = "Marcus Cole and Sarah Jenkins align the contract clauses",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.OFFLINE_EDGE,
            serverBaseUrl = "http://127.0.0.1:8787",
        ).toList()

        assertFalse(unavailableEdge.generateCalled)
        val fallbackCompleted = fallbackEvents.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.LOCAL_RULE_FALLBACK, fallbackCompleted.source)
        assertEquals("", fallbackCompleted.telemetryBadge)
    }

    @Test
    fun cloudModeStreamsSseAndFallsBackGracefullyOnHttpError() = runTest {
        val payloadJson = """{"summary":"Review the addendum tomorrow.","tags":["Follow-up"],"calendarProposal":null,"relations":[]}"""
        val escaped = payloadJson.replace("\"", "\\\"")
        val sseStream = buildString {
            append("""data: {"delta":"$escaped","done":false,"model":"gemini-3.5-flash-lite","latencyMs":12}""").append("\n\n")
            append("""data: {"delta":"","done":true,"model":"gemini-3.5-flash-lite","latencyMs":48}""").append("\n\n")
            append("data: [DONE]\n\n")
        }

        val edge = FakeEdgeEngine(isBackendAvailable = false, initialState = EngineState.Unloaded)
        val cloudRouter = UnifiedInferenceRouter(edge, CloudSseClient(buildMockSseOkHttp(sseStream)), MemoryAnalyzer())

        val events = cloudRouter.distillMemory(
            content = "Review the addendum tomorrow",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.CLOUD_COLLAB,
            serverBaseUrl = "http://127.0.0.1:8787",
        ).toList()

        val completed = events.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.CLOUD_SSE, completed.source)
        assertTrue(completed.telemetryBadge.startsWith("Gemini 3.5 Flash-Lite · TTFT "))

        // 503 unconfigured gateway should degrade to local rule fallback transparently
        val unconfiguredRouter = UnifiedInferenceRouter(
            edge,
            CloudSseClient(buildMockSseOkHttp("{\"detail\":\"not configured\"}", statusCode = 503)),
            MemoryAnalyzer(),
        )
        val degradedEvents = unconfiguredRouter.distillMemory(
            content = "Review the addendum tomorrow",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.CLOUD_COLLAB,
            serverBaseUrl = "http://127.0.0.1:8787",
        ).toList()

        val degradedCompleted = degradedEvents.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.LOCAL_RULE_FALLBACK, degradedCompleted.source)
        assertEquals("Cloud offline · Local extraction", degradedCompleted.telemetryBadge)
    }

    @Test
    fun cloudModeAttachesBearerTokenAndReportsHonest401And429Badges() = runTest {
        val capturedAuthHeaders = mutableListOf<String?>()
        val statusToReturn = intArrayOf(401)
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                capturedAuthHeaders.add(req.header("Authorization"))
                val code = statusToReturn[0]
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Error $code")
                    .body("{\"detail\":\"err\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val edge = FakeEdgeEngine(isBackendAvailable = false, initialState = EngineState.Unloaded)
        val router = UnifiedInferenceRouter(edge, CloudSseClient(mockHttp), MemoryAnalyzer())

        // Placeholder syncKey omits Authorization header and 401 maps to honest badge
        val events401 = router.distillMemory(
            content = "Review the addendum tomorrow",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.CLOUD_COLLAB,
            serverBaseUrl = "http://127.0.0.1:8787",
            authToken = "engram-demo",
        ).toList()
        assertEquals(listOf<String?>(null), capturedAuthHeaders)
        val completed401 = events401.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.LOCAL_RULE_FALLBACK, completed401.source)
        assertEquals("Access token required · Local extraction", completed401.telemetryBadge)

        // Valid demo token sends Authorization: Bearer <token> and 429 maps to quota badge
        statusToReturn[0] = 429
        val token = listOf("eng", "69200000", "team01", "b".repeat(24)).joinToString("_")
        val events429 = router.distillMemory(
            content = "Review the addendum tomorrow",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.CLOUD_COLLAB,
            serverBaseUrl = "http://127.0.0.1:8787",
            authToken = token,
        ).toList()
        assertEquals("Bearer $token", capturedAuthHeaders.last())
        val completed429 = events429.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.LOCAL_RULE_FALLBACK, completed429.source)
        assertEquals("Cloud quota reached · Local extraction", completed429.telemetryBadge)

        // When real MNN edge model is ready, cloud 401 seamlessly falls back to MNN_EDGE first
        val validJson = """
            {"summary":"Review the addendum tomorrow.","tags":["Follow-up"],"calendarProposal":null,"relations":[]}
        """.trimIndent()
        val readyEdge = FakeEdgeEngine(
            isBackendAvailable = true,
            initialState = EngineState.Ready("qwen2.5-0.5b-instruct-mnn", "MNN-LLM (ARM64)", 400L * 1024 * 1024),
            emittedJson = validJson,
        )
        statusToReturn[0] = 401
        val edgeFallbackRouter = UnifiedInferenceRouter(readyEdge, CloudSseClient(mockHttp), MemoryAnalyzer())
        val edgeEvents = edgeFallbackRouter.distillMemory(
            content = "Review the addendum tomorrow",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.CLOUD_COLLAB,
            serverBaseUrl = "http://127.0.0.1:8787",
            authToken = null,
        ).toList()
        assertTrue(readyEdge.generateCalled)
        val edgeCompleted = edgeEvents.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.MNN_EDGE, edgeCompleted.source)
        assertTrue(edgeCompleted.telemetryBadge.startsWith("Cloud fallback · Qwen2.5 0.5B · TTFT "))
    }

    @Test
    fun streamingPreviewsExposeOnlyDecodedSummaryText() = runTest {
        val payloadJson = """{"summary":"• Sarah aligns the clause\n• Priya releases the deposit","tags":["Legal"],"calendarProposal":null,"relations":[]}"""
        val chunks = payloadJson.chunked(9)
        val sseStream = buildString {
            for (piece in chunks) {
                val escaped = piece.replace("\\", "\\\\").replace("\"", "\\\"")
                append("data: {\"delta\":\"$escaped\",\"done\":false,\"model\":\"gemini-3.5-flash-lite\",\"latencyMs\":5}").append("\n\n")
            }
            append("data: {\"delta\":\"\",\"done\":true,\"model\":\"gemini-3.5-flash-lite\",\"latencyMs\":40}").append("\n\n")
            append("data: [DONE]\n\n")
        }
        val edge = FakeEdgeEngine(isBackendAvailable = false, initialState = EngineState.Unloaded)
        val router = UnifiedInferenceRouter(edge, CloudSseClient(buildMockSseOkHttp(sseStream)), MemoryAnalyzer())

        val events = router.distillMemory(
            content = "Sarah aligns the clause; Priya releases the deposit",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.CLOUD_COLLAB,
            serverBaseUrl = "http://127.0.0.1:8787",
        ).toList()

        val previews = events.filterIsInstance<DistillationEvent.StreamingPreview>()
        assertTrue(previews.isNotEmpty())
        assertTrue(previews.none { it.partialText.contains('{') || it.partialText.contains("\"summary\"") })
        assertTrue(previews.any { it.stageLabel == UnifiedInferenceRouter.STAGE_CLOUD_STREAMING && it.partialText.isNotEmpty() })
        val last = previews.last()
        assertEquals(UnifiedInferenceRouter.STAGE_MAPPING_RELATIONS, last.stageLabel)
        assertEquals("• Sarah aligns the clause\n• Priya releases the deposit", last.partialText)

        val completed = events.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.CLOUD_SSE, completed.source)
        assertTrue(completed.telemetryBadge.startsWith("Gemini 3.5 Flash-Lite · TTFT "))
    }

    @Test
    fun humanizesModelNames() {
        assertEquals("Gemini 3.5 Flash-Lite", UnifiedInferenceRouter.humanizeModelName("gemini-3.5-flash-lite"))
        assertEquals("Gemini 2.5 Pro", UnifiedInferenceRouter.humanizeModelName("gemini-2.5-pro"))
        assertEquals("custom-llm", UnifiedInferenceRouter.humanizeModelName("custom-llm"))
        assertEquals("Qwen2.5 0.5B", UnifiedInferenceRouter.humanizeEdgeModelName("qwen2.5-0.5b-instruct-mnn"))
    }

    @Test
    fun skipCloudAttemptJumpsDirectlyToEdgeAndScrubsStreamingPrefixArtifacts() = runTest {
        var cloudHits = 0
        val countingHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                cloudHits++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Unexpected")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val validJson = """
            {"summary":"• First point: Review the addendum tomorrow","tags":["Legal"],"calendarProposal":null,"relations":[]}
        """.trimIndent()
        val readyEdge = FakeEdgeEngine(
            isBackendAvailable = true,
            initialState = EngineState.Ready("qwen2.5-0.5b-instruct-mnn", "MNN-LLM (ARM64)", 400L * 1024 * 1024),
            emittedJson = validJson,
        )
        val router = UnifiedInferenceRouter(readyEdge, CloudSseClient(countingHttp), MemoryAnalyzer())

        val events = router.distillMemory(
            content = "Review the contract addendum tomorrow at 3:00 PM with Sarah Jenkins",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.CLOUD_COLLAB,
            serverBaseUrl = "http://127.0.0.1:8787",
            skipCloudAttempt = true,
        ).toList()

        assertEquals(0, cloudHits)
        assertTrue(readyEdge.generateCalled)
        val previews = events.filterIsInstance<DistillationEvent.StreamingPreview>()
        assertTrue(previews.none { it.partialText.contains("First point:", ignoreCase = true) })
        val completed = events.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.MNN_EDGE, completed.source)
        assertTrue(completed.telemetryBadge.startsWith("Cloud fallback · Qwen2.5 0.5B · "))
    }

    @Test
    fun offlineModeFallsBackQuietlyWithoutBadgeOnInvalidEdgeOutput() = runTest {
        val brokenEdge = FakeEdgeEngine(
            isBackendAvailable = true,
            initialState = EngineState.Ready("qwen2.5-0.5b-instruct-mnn", "MNN-LLM (ARM64)", 400L * 1024 * 1024),
            emittedJson = "{\"summary\":\"unterminated repetition...",
        )
        val router = UnifiedInferenceRouter(brokenEdge, CloudSseClient(buildMockSseOkHttp(null)), MemoryAnalyzer())

        val events = router.distillMemory(
            content = "Check sample shipment tomorrow at 3pm",
            nowMillis = 1_700_000_000_000L,
            mode = InferenceMode.OFFLINE_EDGE,
            serverBaseUrl = "http://127.0.0.1:8787",
        ).toList()

        assertTrue(brokenEdge.generateCalled)
        val completed = events.filterIsInstance<DistillationEvent.Completed>().single()
        assertEquals(AnalysisSource.LOCAL_RULE_FALLBACK, completed.source)
        assertEquals("", completed.telemetryBadge)
    }
}
