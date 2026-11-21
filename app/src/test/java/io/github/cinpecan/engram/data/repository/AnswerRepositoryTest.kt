package io.github.cinpecan.engram.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.AnswerStreamRequestDto
import io.github.cinpecan.engram.data.remote.CloudAuthClient
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.data.seed.SeedStorylineProvider
import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.edgemnn.InferenceTelemetry
import io.github.cinpecan.engram.edgemnn.StreamChunk
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnswerRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val seeds = SeedStorylineProvider.createInitialStoryline(nowMillis = 1_800_000_000_000L)

    private class SeedStore(private val items: List<MemoryItem>) : LocalMemoryStore {
        override fun observeMemories(): Flow<List<MemoryItem>> = flowOf(items)
        override fun observeMemoryById(memoryId: String): Flow<MemoryItem?> = flowOf(items.find { it.id == memoryId })
        override fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> = flowOf(emptyList())
        override suspend fun insertRawMemory(item: MemoryItem) = Unit
        override suspend fun updateMemoryAnalysis(
            memoryId: String,
            payload: MemoryAnalysisPayload,
            analysisSource: AnalysisSource,
            telemetryBadge: String,
        ) = Unit
        override suspend fun setCalendarSaved(memoryId: String, saved: Boolean) = Unit
        override suspend fun mergeRemoteMemories(remoteItems: List<MemoryItem>): Int = 0
        override suspend fun getAllMemories(): List<MemoryItem> = items
        override suspend fun countMemories(): Int = items.size
    }

    private class FakeEdge(
        override val isBackendAvailable: Boolean,
        private val reply: String = "",
    ) : EdgeAiEngine {
        override val state: StateFlow<EngineState> = MutableStateFlow(EngineState.Unloaded)
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)
        var lastPrompt: String? = null
        override suspend fun loadModel(modelId: String, modelDirPath: String): Result<Unit> = Result.success(Unit)
        override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> {
            lastPrompt = prompt
            return flowOf(
                StreamChunk.Delta(reply),
                StreamChunk.Completed(
                    fullText = reply,
                    telemetry = InferenceTelemetry(
                        engineLabel = "MNN-LLM",
                        modelId = "qwen2.5-0.5b-instruct-mnn",
                        ttftMs = 900L,
                        totalMs = 2_000L,
                        generatedTokens = 30,
                        tokensPerSec = 15.0,
                        residentMemoryMb = 300.0,
                        isRealMnn = true,
                    ),
                ),
            )
        }
        override suspend fun unload(reason: io.github.cinpecan.engram.edgemnn.UnloadReason): Long = 0L
        override fun cancelActiveGeneration() = Unit
        override fun onTrimMemory(level: Int) = Unit
        override fun currentMemoryBytes(): Long = 0L
    }

    private suspend fun cloudPrefs(): EnginePreferencesRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO),
            produceFile = { tempFolder.newFile("answer_prefs_${System.nanoTime()}.preferences_pb") },
        )
        return EnginePreferencesRepository(dataStore).apply {
            setInferenceMode(InferenceMode.CLOUD_COLLAB)
            updateSyncConfig("http://127.0.0.1:8787", "eng_69200000_team01_${"a".repeat(24)}")
        }
    }

    private fun gateway(status: Int, captured: MutableList<AnswerStreamRequestDto>, auth: MutableList<String?>) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val buf = Buffer().also { req.body?.writeTo(it) }
            captured += json.decodeFromString<AnswerStreamRequestDto>(buf.readUtf8())
            auth += req.header("Authorization")
            val body = if (status == 200) {
                listOf(
                    """data: {"delta":"Priya is waiting for Liam's sign-off ","done":false,"model":"gemini-3.5-flash-lite","latencyMs":300}""",
                    """data: {"delta":"[1].","done":false,"model":"gemini-3.5-flash-lite","latencyMs":320}""",
                    """data: {"delta":"","done":true,"model":"gemini-3.5-flash-lite","latencyMs":330}""",
                    "data: [DONE]",
                ).joinToString("\n\n", postfix = "\n\n")
            } else {
                """{"detail":"denied"}"""
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("HTTP $status")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()

    @Test
    fun cloudAnswerStreamsWithCitationsContextsAndOffsetTimestamp() = runTest {
        val requests = mutableListOf<AnswerStreamRequestDto>()
        val auths = mutableListOf<String?>()
        val repo = AnswerRepository(
            store = SeedStore(seeds),
            edgeEngine = FakeEdge(isBackendAvailable = false),
            cloudSseClient = CloudSseClient(gateway(200, requests, auths), json),
            preferencesRepository = cloudPrefs(),
            zoneId = ZoneOffset.ofHours(8),
        )

        val events = repo.ask("Why is the Helios Display deposit on hold?").toList()

        val retrieved = events.first() as AnswerEvent.Retrieved
        assertTrue(retrieved.result.citations.any { it.memoryId == "seed-2" })
        assertTrue(events.any { it is AnswerEvent.Streaming && it.textSoFar.contains("Priya") })
        val completed = events.last() as AnswerEvent.Completed
        assertEquals(AnswerSource.CLOUD, completed.source)
        assertEquals("Priya is waiting for Liam's sign-off [1].", completed.text)
        assertTrue(completed.badge.startsWith("Gemini 3.5 Flash-Lite · TTFT"))
        assertTrue("Expected tok/s in cloud badge: ${completed.badge}", completed.badge.contains("tok/s"))

        val request = requests.single()
        assertEquals(retrieved.result.citations.map { it.memoryId }, request.contexts.map { it.id })
        assertTrue(request.contexts.all { it.text.length <= 1200 })
        assertTrue("nowIso must carry an offset: ${request.nowIso}", request.nowIso.endsWith("+08:00"))
        assertTrue(auths.single()!!.startsWith("Bearer eng_"))
    }

    @Test
    fun cloudUnauthorizedFallsBackToOnDeviceModel() = runTest {
        val edge = FakeEdge(isBackendAvailable = true, reply = "Liam's burn-in sign-off is pending [2].")
        val repo = AnswerRepository(
            store = SeedStore(seeds),
            edgeEngine = edge,
            cloudSseClient = CloudSseClient(gateway(401, mutableListOf(), mutableListOf()), json),
            preferencesRepository = cloudPrefs(),
        )

        val completed = repo.ask("Why is the Helios Display deposit on hold?").toList().last() as AnswerEvent.Completed

        assertEquals(AnswerSource.EDGE, completed.source)
        assertTrue(completed.badge.startsWith("Cloud fallback · Qwen2.5 0.5B"))
        val prompt = edge.lastPrompt.orEmpty()
        assertTrue(prompt.contains("[1] ("))
        assertTrue(prompt.contains("Question: Why is the Helios Display deposit on hold?"))
    }

    @Test
    fun withoutAnyGeneratorShowsRetrievedMemoriesHonestly() = runTest {
        val repo = AnswerRepository(
            store = SeedStore(seeds),
            edgeEngine = FakeEdge(isBackendAvailable = false),
            cloudSseClient = CloudSseClient(OkHttpClient(), json),
            preferencesRepository = null,
        )

        val events = repo.ask("What did Sarah and Marcus agree?").toList()

        assertTrue((events.first() as AnswerEvent.Retrieved).result.citations.isNotEmpty())
        assertEquals(AnswerEvent.RetrievalOnly(AnswerRepository.REASON_NO_MODEL), events.last())
        assertTrue(events.none { it is AnswerEvent.Completed })
    }

    @Test
    fun cloudUnauthorizedWithoutModelReportsTokenReason() = runTest {
        val repo = AnswerRepository(
            store = SeedStore(seeds),
            edgeEngine = FakeEdge(isBackendAvailable = false),
            cloudSseClient = CloudSseClient(gateway(401, mutableListOf(), mutableListOf()), json),
            preferencesRepository = cloudPrefs(),
        )

        val last = repo.ask("Why is the Helios Display deposit on hold?").toList().last()
        assertEquals(AnswerEvent.RetrievalOnly(AnswerRepository.REASON_UNAUTHORIZED), last)
    }

    @Test
    fun nothingMatchedSkipsGenerationEntirely() = runTest {
        val edge = FakeEdge(isBackendAvailable = true, reply = "should not run")
        val repo = AnswerRepository(
            store = SeedStore(seeds),
            edgeEngine = edge,
            cloudSseClient = CloudSseClient(OkHttpClient(), json),
        )

        val events = repo.ask("What is the weather on Mars?").toList()

        assertTrue((events.first() as AnswerEvent.Retrieved).result.isEmpty)
        assertEquals(AnswerEvent.RetrievalOnly(AnswerRepository.REASON_NO_MATCH), events.last())
        assertEquals(null, edge.lastPrompt)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cloudAnswerAndUnauthorizedRefreshCloudSessionQuota() = runTest {
        val prefs = cloudPrefs()
        val quotaRemaining = AtomicInteger(42)
        val answerStatus = intArrayOf(200)
        val mockHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            if (req.url.encodedPath.endsWith("/auth/status")) {
                val code = if (answerStatus[0] == 401) 401 else 200
                val body = if (code == 200) {
                    """{"status":"ok","mode":"demo","remainingQuota":${quotaRemaining.get()}}"""
                } else {
                    """{"detail":"invalid"}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("HTTP $code")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                val code = answerStatus[0]
                val body = if (code == 200) {
                    quotaRemaining.decrementAndGet()
                    listOf(
                        """data: {"delta":"Deposit waits [1].","done":false,"model":"gemini-3.5-flash-lite","latencyMs":120}""",
                        """data: {"delta":"","done":true,"model":"gemini-3.5-flash-lite","latencyMs":150}""",
                        "data: [DONE]",
                    ).joinToString("\n\n", postfix = "\n\n")
                } else {
                    """{"detail":"denied"}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("HTTP $code")
                    .body(body.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
        }.build()

        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val monitor = CloudSessionMonitor(
            preferencesRepository = prefs,
            authClient = CloudAuthClient(mockHttp, json, ioDispatcher = testDispatcher),
            hasBundledDefaultEndpoint = true,
            debounceMs = 0L,
            scope = CoroutineScope(backgroundScope.coroutineContext + testDispatcher),
        )
        backgroundScope.launch(testDispatcher) { monitor.state.collect {} }
        advanceUntilIdle()
        assertEquals(CloudSessionState.Ready(42), monitor.state.first { it is CloudSessionState.Ready })

        val repo = AnswerRepository(
            store = SeedStore(seeds),
            edgeEngine = FakeEdge(isBackendAvailable = false),
            cloudSseClient = CloudSseClient(mockHttp, json),
            preferencesRepository = prefs,
            cloudSessionMonitor = monitor,
        )

        repo.ask("Why is the Helios Display deposit on hold?").toList()
        advanceUntilIdle()
        assertEquals(CloudSessionState.Ready(41), monitor.state.value)

        answerStatus[0] = 401
        repo.ask("Why is the Helios Display deposit on hold?").toList()
        advanceUntilIdle()
        assertEquals(CloudSessionState.InvalidToken, monitor.state.value)
    }
}
