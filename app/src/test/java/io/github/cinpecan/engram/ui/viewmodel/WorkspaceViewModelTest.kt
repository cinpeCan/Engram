package io.github.cinpecan.engram.ui.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.CloudAuthClient
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.data.remote.ModelDescriptorDto
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.data.remote.ModelFileDto
import io.github.cinpecan.engram.data.repository.CloudSessionMonitor
import io.github.cinpecan.engram.data.repository.CloudSessionState
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.data.repository.UnifiedInferenceRouter
import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.edgemnn.InferenceTelemetry
import io.github.cinpecan.engram.edgemnn.StreamChunk
import io.github.cinpecan.engram.edgemnn.UnloadReason
import io.github.cinpecan.engram.util.MainDispatcherRule
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeEdgeEngine(
        override val isBackendAvailable: Boolean = true,
        initialState: EngineState = EngineState.Unloaded,
    ) : EdgeAiEngine {
        val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<EngineState> = mutableState
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)

        override suspend fun loadModel(modelId: String, modelDirPath: String) = Result.success(Unit)
        override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {}
        override suspend fun unload(reason: UnloadReason): Long = 0L
        override fun cancelActiveGeneration() = Unit
        override fun onTrimMemory(level: Int) = Unit
        override fun currentMemoryBytes(): Long = 0L
    }

    private suspend fun <T> awaitReal(timeoutMs: Long = 5_000L, block: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMs) { block() }
        }

    private fun TestScope.awaitNoticeMessage(vm: WorkspaceViewModel, expectedMessage: String?) {
        runCurrent()
        val deadlineNs = System.nanoTime() + 2_000_000_000L
        while (vm.activeNotice.value?.message != expectedMessage) {
            check(System.nanoTime() < deadlineNs) {
                "Expected notice=$expectedMessage but was=${vm.activeNotice.value}"
            }
            Thread.sleep(2L)
            runCurrent()
        }
    }

    private fun dummyHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    @Test
    fun noticesAutoDismissDeterministicallyReplaceLatestAndClearOnActionOrOverlay() = runTest(testDispatcher) {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("ws_notice_${System.nanoTime()}.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        awaitReal { prefsRepo.preferencesFlow.first() }

        val sessionMonitor = CloudSessionMonitor(
            preferencesRepository = prefsRepo,
            authClient = CloudAuthClient(dummyHttpClient(), ioDispatcher = UnconfinedTestDispatcher(testScheduler)),
            hasBundledDefaultEndpoint = true,
            debounceMs = 0L,
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )
        val vm = WorkspaceViewModel(
            edgeEngine = FakeEdgeEngine(),
            preferencesRepository = prefsRepo,
            cloudSessionMonitor = sessionMonitor,
        )
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.activeNotice.collect {}
        }
        runCurrent()

        // 1. INFO auto-dismisses after 2,500ms
        vm.onIntent(WorkspaceIntent.ShowNotice(WorkspaceNoticeRequest("Cloud offline · Using on-device model")))
        runCurrent()
        assertNotNull(vm.activeNotice.value)
        advanceTimeBy(2_400L)
        runCurrent()
        assertNotNull(vm.activeNotice.value)
        advanceTimeBy(200L)
        runCurrent()
        assertNull(vm.activeNotice.value)

        // 2. Rapid replacement drops stale timer/backlog and keeps newest notice
        vm.onIntent(
            WorkspaceIntent.ShowNotice(
                WorkspaceNoticeRequest(
                    message = "Download the on-device model first",
                    kind = WorkspaceNoticeKind.ENGINE_SETUP,
                    actionLabel = "Open",
                    action = WorkspaceNoticeAction.OPEN_ENGINE_SHEET,
                ),
            ),
        )
        runCurrent()
        vm.onIntent(
            WorkspaceIntent.ShowNotice(
                WorkspaceNoticeRequest(
                    message = "No calendar app found",
                    kind = WorkspaceNoticeKind.INFO,
                ),
            ),
        )
        awaitNoticeMessage(vm, "No calendar app found")
        assertEquals("No calendar app found", vm.activeNotice.value?.message)

        // Tapping setup action immediately clears notice and emits OpenEngineSheet
        vm.onIntent(
            WorkspaceIntent.ShowNotice(
                WorkspaceNoticeRequest(
                    message = "Download the on-device model first",
                    kind = WorkspaceNoticeKind.ENGINE_SETUP,
                    actionLabel = "Open",
                    action = WorkspaceNoticeAction.OPEN_ENGINE_SHEET,
                ),
            ),
        )
        runCurrent()
        vm.onIntent(WorkspaceIntent.PerformNoticeAction)
        awaitNoticeMessage(vm, null)
        assertNull(vm.activeNotice.value)
        assertEquals(WorkspaceEffect.OpenEngineSheet, vm.effects.first())

        // 3. Opening an overlay sheet immediately clears active notice
        vm.onIntent(
            WorkspaceIntent.ShowNotice(
                WorkspaceNoticeRequest(
                    message = "Download the on-device model first",
                    kind = WorkspaceNoticeKind.ENGINE_SETUP,
                    actionLabel = "Open",
                    action = WorkspaceNoticeAction.OPEN_ENGINE_SHEET,
                ),
            ),
        )
        runCurrent()
        assertNotNull(vm.activeNotice.value)
        vm.onIntent(WorkspaceIntent.OnOverlayPresenceChanged(isOverlayOpen = true))
        awaitNoticeMessage(vm, null)
        assertNull(vm.activeNotice.value)

        collector.cancel()
    }

    @Test
    fun engineSetupNoticeDismissesImmediatelyWhenModelDownloadsOrEngineBecomesReady() = runTest(testDispatcher) {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("ws_ready_${System.nanoTime()}.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        awaitReal { prefsRepo.setInferenceMode(InferenceMode.OFFLINE_EDGE) }
        awaitReal { prefsRepo.preferencesFlow.first() }

        val modelsDir = tempFolder.newFolder("ws_models_${System.nanoTime()}")
        val descriptor = ModelDescriptorDto(
            id = "qwen2.5-0.5b-instruct-mnn",
            name = "Qwen2.5-0.5B Instruct",
            role = "generation",
            quantization = "INT4",
            sizeBytes = 2L,
            minRamMb = 2048,
            downloadUrl = "https://example.com/config.json",
            mirrorUrl = "https://example.com/config.json",
            sha256 = null,
            description = null,
            files = listOf(
                ModelFileDto(
                    name = "config.json",
                    url = "https://example.com/config.json",
                    sizeBytes = 2L,
                    sha256 = null,
                    fallbackUrl = null,
                ),
            ),
        )
        val downloadManager = ModelDownloadManager(
            primaryModelsDir = modelsDir,
            externalModelsDir = null,
            okHttpClient = dummyHttpClient(),
            initialCatalog = listOf(descriptor),
        )
        val engine = FakeEdgeEngine(isBackendAvailable = true, initialState = EngineState.Unloaded)
        val sessionMonitor = CloudSessionMonitor(
            preferencesRepository = prefsRepo,
            authClient = CloudAuthClient(dummyHttpClient(), ioDispatcher = UnconfinedTestDispatcher(testScheduler)),
            hasBundledDefaultEndpoint = true,
            debounceMs = 0L,
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )
        val vm = WorkspaceViewModel(
            edgeEngine = engine,
            preferencesRepository = prefsRepo,
            cloudSessionMonitor = sessionMonitor,
            downloadManager = downloadManager,
        )
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.activeNotice.collect {}
        }
        runCurrent()

        // Case A: Completing local model download clears ENGINE_SETUP notice immediately
        vm.onIntent(
            WorkspaceIntent.ShowNotice(
                WorkspaceNoticeRequest(
                    message = "Download the on-device model first",
                    kind = WorkspaceNoticeKind.ENGINE_SETUP,
                    actionLabel = "Open",
                    action = WorkspaceNoticeAction.OPEN_ENGINE_SHEET,
                ),
            ),
        )
        runCurrent()
        assertNotNull(vm.activeNotice.value)

        File(modelsDir, descriptor.id).apply { mkdirs() }.also { dir ->
            File(dir, "config.json").writeText("{}")
        }
        downloadManager.refreshLocalModelStates()
        awaitNoticeMessage(vm, null)
        assertNull(vm.activeNotice.value)

        // Case B: Switching inference mode clears ENGINE_SETUP notice immediately
        vm.onIntent(
            WorkspaceIntent.ShowNotice(
                WorkspaceNoticeRequest(
                    message = "Cloud not configured · Set up Cloud or download model",
                    kind = WorkspaceNoticeKind.ENGINE_SETUP,
                    actionLabel = "Open",
                    action = WorkspaceNoticeAction.OPEN_ENGINE_SHEET,
                ),
            ),
        )
        runCurrent()
        assertNotNull(vm.activeNotice.value)

        awaitReal { prefsRepo.setInferenceMode(InferenceMode.CLOUD_COLLAB) }
        awaitNoticeMessage(vm, null)
        assertNull(vm.activeNotice.value)

        collector.cancel()
    }

    @Test
    fun distillationNoticesFromRepositoryDriveWorkspaceActiveNoticeWithoutCallbacks() = runTest(testDispatcher) {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("ws_distill_${System.nanoTime()}.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        awaitReal { prefsRepo.setInferenceMode(InferenceMode.CLOUD_COLLAB) }
        awaitReal { prefsRepo.preferencesFlow.first() }

        val itemsFlow = MutableStateFlow<List<MemoryItem>>(emptyList())
        var updatedSource: AnalysisSource? = null
        var updatedBadge: String? = null
        val memStore = object : LocalMemoryStore {
            override fun observeMemories(): Flow<List<MemoryItem>> = itemsFlow
            override fun observeMemoryById(memoryId: String): Flow<MemoryItem?> = flow {}
            override fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> = flow {}
            override suspend fun insertRawMemory(item: MemoryItem) {
                itemsFlow.value = listOf(item)
            }
            override suspend fun updateMemoryAnalysis(
                memoryId: String,
                payload: MemoryAnalysisPayload,
                analysisSource: AnalysisSource,
                telemetryBadge: String,
            ) {
                updatedSource = analysisSource
                updatedBadge = telemetryBadge
            }
            override suspend fun setCalendarSaved(memoryId: String, saved: Boolean) = Unit
            override suspend fun mergeRemoteMemories(remoteItems: List<MemoryItem>): Int = 0
            override suspend fun getAllMemories(): List<MemoryItem> = itemsFlow.value
            override suspend fun countMemories(): Int = itemsFlow.value.size
        }

        var edgeCalled = false
        val readyEdge = object : EdgeAiEngine {
            override val state: StateFlow<EngineState> = MutableStateFlow(
                EngineState.Ready("qwen2.5-0.5b-instruct-mnn", "MNN-LLM (ARM64)", 400L * 1024 * 1024),
            )
            override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)
            override val isBackendAvailable: Boolean = true
            override suspend fun loadModel(modelId: String, modelDirPath: String) = Result.success(Unit)
            override fun streamGenerate(
                systemPrompt: String,
                userPrompt: String,
                maxTokens: Int,
            ): Flow<StreamChunk> = streamGenerate(userPrompt, maxTokens)
            override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {
                edgeCalled = true
                emit(
                    StreamChunk.Completed(
                        fullText = """{"summary":"Distilled locally after cloud error","tags":[],"calendarProposal":null,"relations":[]}""",
                        telemetry = InferenceTelemetry(
                            engineLabel = "MNN-LLM (ARM64)",
                            modelId = "qwen2.5-0.5b-instruct-mnn",
                            ttftMs = 100L,
                            totalMs = 200L,
                            generatedTokens = 12,
                            tokensPerSec = 60.0,
                            residentMemoryMb = 400.0,
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

        val repoJob = SupervisorJob(backgroundScope.coroutineContext[Job])
        val repoScope = CoroutineScope(repoJob + Dispatchers.IO)
        val repo = MemoryRepository(
            store = memStore,
            inferenceRouter = UnifiedInferenceRouter(readyEdge, CloudSseClient(dummyHttpClient()), MemoryAnalyzer()),
            preferencesRepository = prefsRepo,
            backgroundScope = repoScope,
        )
        val sessionMonitor = CloudSessionMonitor(
            preferencesRepository = prefsRepo,
            authClient = CloudAuthClient(dummyHttpClient(), ioDispatcher = UnconfinedTestDispatcher(testScheduler)),
            hasBundledDefaultEndpoint = true,
            debounceMs = 0L,
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )
        val vm = WorkspaceViewModel(
            edgeEngine = readyEdge,
            preferencesRepository = prefsRepo,
            cloudSessionMonitor = sessionMonitor,
            memoryRepository = repo,
        )
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.activeNotice.collect {}
        }
        try {
            runCurrent()

            repo.captureMemory("Test note triggering cloud-to-edge fallback notice")
            val deadlineNs = System.nanoTime() + 5_000_000_000L
            while (vm.activeNotice.value?.message != "Cloud unavailable · Fell back to on-device model") {
                check(System.nanoTime() < deadlineNs) {
                    "Timed out waiting for fallback notice; current=${vm.activeNotice.value} " +
                        "edgeCalled=$edgeCalled source=$updatedSource badge=$updatedBadge"
                }
                Thread.sleep(5L)
                runCurrent()
            }
            advanceTimeBy(2_400L)
            runCurrent()
            assertNotNull(vm.activeNotice.value)
            advanceTimeBy(200L)
            runCurrent()
            assertNull(vm.activeNotice.value)
        } finally {
            repoJob.cancel()
            collector.cancel()
        }
    }
}
