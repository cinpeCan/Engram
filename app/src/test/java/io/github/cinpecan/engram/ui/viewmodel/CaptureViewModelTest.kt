package io.github.cinpecan.engram.ui.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.cinpecan.engram.data.calendar.CalendarAnchorTarget
import io.github.cinpecan.engram.data.calendar.SystemCalendarObserver
import io.github.cinpecan.engram.data.local.LocalMemoryStore
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
import io.github.cinpecan.engram.data.seed.SeedStorylineProvider
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.model.RelationTriple
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeStore(initial: List<MemoryItem> = emptyList()) : LocalMemoryStore {
        private val items = MutableStateFlow(initial)
        private val tombstones = LinkedHashMap<String, Long>()
        val savedStateUpdates = mutableListOf<Pair<String, Boolean>>()

        override fun observeMemories(): Flow<List<MemoryItem>> = items
        override fun observeMemoryById(memoryId: String): Flow<MemoryItem?> =
            items.map { list -> list.firstOrNull { it.id == memoryId } }
        override fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> =
            items.map { emptyList() }

        override suspend fun insertRawMemory(item: MemoryItem) {
            items.value = listOf(item) + items.value.filterNot { it.id == item.id }
        }

        override suspend fun updateMemoryAnalysis(
            memoryId: String,
            payload: MemoryAnalysisPayload,
            analysisSource: AnalysisSource,
            telemetryBadge: String,
        ) {
            items.value = items.value.map { item ->
                if (item.id == memoryId) {
                    item.copy(
                        summary = payload.summary,
                        tags = payload.tags,
                        analysisStatus = MemoryAnalysisStatus.READY,
                        calendarProposal = payload.calendarProposal,
                        relations = payload.relations,
                        analysisSource = analysisSource,
                        telemetryBadge = telemetryBadge,
                    )
                } else {
                    item
                }
            }
        }

        override suspend fun setCalendarSaved(memoryId: String, saved: Boolean) {
            savedStateUpdates.add(memoryId to saved)
            items.value = items.value.map { item ->
                if (item.id == memoryId) item.copy(calendarSaved = saved) else item
            }
        }

        override suspend fun mergeRemoteMemories(remoteItems: List<MemoryItem>): Int = 0
        override suspend fun getAllMemories(): List<MemoryItem> = items.value
        override suspend fun countMemories(): Int = items.value.size

        override suspend fun deleteMemory(memoryId: String, deletedAtMillis: Long) {
            items.value = items.value.filterNot { it.id == memoryId }
            tombstones[memoryId] = deletedAtMillis
        }

        override suspend fun getDeletedTombstones(): Map<String, Long> = tombstones.toMap()
    }

    private class FakeCalendarObserver(
        var granted: Boolean = true,
    ) : SystemCalendarObserver {
        val syncedEvents = MutableStateFlow<Map<String, Long>>(emptyMap())
        var permissionNotifyCount = 0

        override fun hasReadCalendarPermission(): Boolean = granted

        override fun notifyPermissionChanged() {
            permissionNotifyCount++
        }

        override fun observeSyncedEvents(
            targetsFlow: Flow<List<CalendarAnchorTarget>>,
        ): Flow<Map<String, Long>> = syncedEvents
    }

    private class StubEngine : EdgeAiEngine {
        override val state: StateFlow<EngineState> = MutableStateFlow(EngineState.Unloaded)
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)
        override val isBackendAvailable: Boolean = false
        override suspend fun loadModel(modelId: String, modelDirPath: String) =
            Result.failure<Unit>(IllegalStateException("unavailable"))
        override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {}
        override suspend fun unload(reason: UnloadReason): Long = 0L
        override fun cancelActiveGeneration() = Unit
        override fun onTrimMemory(level: Int) = Unit
        override fun currentMemoryBytes(): Long = 0L
    }

    private fun buildRouter(engine: EdgeAiEngine = StubEngine()): UnifiedInferenceRouter =
        UnifiedInferenceRouter(
            edgeEngine = engine,
            cloudSseClient = CloudSseClient(OkHttpClient()),
            analyzer = MemoryAnalyzer(),
        )

    @Test
    fun updateInputAndFillSampleUpdateInputTextSynchronously() {
        val store = FakeStore()
        val repo = MemoryRepository(store, buildRouter())
        val vm = CaptureViewModel(repo)

        vm.onIntent(CaptureIntent.UpdateInput("Test draft"))
        assertEquals("Test draft", vm.inputText.value)

        vm.onIntent(CaptureIntent.FillSample("Sample content"))
        assertEquals("Sample content", vm.inputText.value)
    }

    @Test
    fun trailingQuestionMarkAsksInsteadOfSavingAMemory() = runTest {
        val store = FakeStore()
        val repo = MemoryRepository(
            store = store,
            inferenceRouter = buildRouter(),
            backgroundScope = backgroundScope,
        )
        val vm = CaptureViewModel(repo)

        assertTrue(isAskQuery("Why is the deposit on hold?"))
        assertTrue(isAskQuery("Why is the deposit on hold\uFF1F  "))
        assertFalse(isAskQuery("?"))
        assertFalse(isAskQuery("Ship the module tomorrow"))

        vm.onIntent(CaptureIntent.UpdateInput("  Why is the deposit on hold?  "))
        vm.onIntent(CaptureIntent.SubmitCapture)

        val effects = withTimeout(5_000L) { vm.effects.take(2).toList() }
        assertEquals(CaptureEffect.DismissNotice, effects[0])
        assertEquals(CaptureEffect.OpenAnswer("Why is the deposit on hold?"), effects[1])
        assertEquals("", vm.inputText.value)
        assertTrue("questions must never be persisted", store.getAllMemories().isEmpty())
    }

    @Test
    fun submitCapturePersistsMemoryClearsInputAndCleansStreamingPreview() = runTest {
        val store = FakeStore()
        val repo = MemoryRepository(
            store = store,
            inferenceRouter = buildRouter(),
            backgroundScope = backgroundScope,
        )
        val vm = CaptureViewModel(repo)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.memories.collect {}
        }

        vm.onIntent(CaptureIntent.UpdateInput("Confirm the Micro-OLED delivery date with Liam Chen"))
        vm.onIntent(CaptureIntent.SubmitCapture)

        val readyList = withTimeout(5_000L) {
            vm.memories.first { list ->
                list.any {
                    it.content == "Confirm the Micro-OLED delivery date with Liam Chen" &&
                        it.analysisStatus == MemoryAnalysisStatus.READY
                }
            }
        }

        assertEquals("", vm.inputText.value)
        assertFalse(vm.isSubmitting.value)
        val saved = readyList.first { it.content == "Confirm the Micro-OLED delivery date with Liam Chen" }
        assertEquals(MemoryAnalysisStatus.READY, saved.analysisStatus)
        assertEquals(AnalysisSource.LOCAL_RULE_FALLBACK, saved.analysisSource)
        assertTrue(vm.streamingPreviews.value.isEmpty())
    }

    @Test
    fun calendarStateReactsToSystemProviderWithoutOptimisticWrites() = runTest {
        val proposal = CalendarProposal(
            title = "Firmware sign-off meeting",
            startTimeIso = "2026-11-18T10:30:00",
            durationMinutes = 60,
            location = "Building A, Room 501",
        )
        val seed = MemoryItem(
            id = "mem-cal-1",
            content = "Tomorrow at 10:30 AM in Building A, Room 501: firmware sign-off meeting",
            summary = "Tomorrow 10:30 AM · Firmware sign-off meeting",
            tags = listOf("Follow-up"),
            createdAtMillis = 1_000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = proposal,
            calendarSaved = false,
            relations = emptyList(),
        )
        val store = FakeStore(listOf(seed))
        val observer = FakeCalendarObserver(granted = true)
        val repo = MemoryRepository(
            store = store,
            inferenceRouter = buildRouter(),
            calendarObserver = observer,
            backgroundScope = backgroundScope,
        )
        val vm = CaptureViewModel(repo)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.memories.collect {}
        }

        // Tapping add emits OpenCalendarInsert and does NOT optimistically flip calendarSaved
        vm.onIntent(CaptureIntent.RequestCalendarAction("mem-cal-1", proposal, existingEventId = null))
        assertEquals(CaptureEffect.OpenCalendarInsert("mem-cal-1", proposal), vm.effects.first())
        vm.onIntent(
            CaptureIntent.OnCalendarActionLaunched(
                title = proposal.title,
                launched = true,
                openedExisting = false,
                hasReadPermission = true,
            ),
        )
        assertFalse(vm.memories.value.single().calendarSaved)
        assertNull(vm.memories.value.single().calendarEventId)
        assertTrue(store.savedStateUpdates.isEmpty())

        // Simulated System Calendar insert notification flips state reactively
        observer.syncedEvents.value = mapOf("mem-cal-1" to 108L)
        withTimeout(2_000L) {
            vm.memories.first { it.single().calendarSaved && it.single().calendarEventId == 108L }
        }
        advanceUntilIdle()
        assertEquals(true, store.savedStateUpdates.lastOrNull()?.second)

        // Tapping synced card now opens existing system event
        vm.onIntent(CaptureIntent.RequestCalendarAction("mem-cal-1", proposal, existingEventId = 108L))
        assertEquals(CaptureEffect.OpenCalendarEvent(108L, proposal), vm.effects.first())

        // Simulated deletion in System Calendar automatically reverts state
        observer.syncedEvents.value = emptyMap()
        withTimeout(2_000L) {
            vm.memories.first { !it.single().calendarSaved && it.single().calendarEventId == null }
        }
        advanceUntilIdle()
        assertEquals(false, store.savedStateUpdates.lastOrNull()?.second)

        // Missing calendar app emits user-facing notice
        vm.onIntent(
            CaptureIntent.OnCalendarActionLaunched(
                title = proposal.title,
                launched = false,
                openedExisting = false,
                hasReadPermission = false,
            ),
        )
        assertEquals(CaptureEffect.ShowMessage("No calendar app found"), vm.effects.first())

        // Permission signal propagates
        vm.onIntent(CaptureIntent.OnCalendarPermissionResult(granted = true))
        assertEquals(1, observer.permissionNotifyCount)
    }

    @Test
    fun deleteRemovesCardAndDeletingAllSeedsPreventsReseed() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("capture_seed_prefs.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        val store = FakeStore()
        val repo = MemoryRepository(
            store = store,
            inferenceRouter = buildRouter(),
            preferencesRepository = prefsRepo,
            backgroundScope = backgroundScope,
        )
        repo.initializeAndSeedIfNeeded()
        assertEquals(3, store.countMemories())

        val vm = CaptureViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.memories.collect {}
        }
        val initialMemories = withTimeout(2_000L) {
            vm.memories.first { it.size == 3 }
        }

        // Delete first card directly via tombstone without Undo notice
        val firstCard = initialMemories.first()
        vm.onIntent(CaptureIntent.DeleteMemory(firstCard))
        withTimeout(2_000L) { vm.memories.first { it.size == 2 } }

        // Delete all seed cards; re-running initializeAndSeedIfNeeded must NOT resurrect them
        for (item in initialMemories.drop(1)) {
            vm.onIntent(CaptureIntent.DeleteMemory(item))
        }
        withTimeout(2_000L) { vm.memories.first { it.isEmpty() } }
        repo.initializeAndSeedIfNeeded()
        assertEquals(0, store.countMemories())
    }

    @Test
    fun legacySeedContentIsFullyReplacedButDeletedSeedsStayDeleted() = runTest {
        val legacySeed = MemoryItem(
            id = "seed-2",
            content = "Legacy seed note",
            summary = "Legacy summary",
            tags = listOf("Legacy"),
            createdAtMillis = 42L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = listOf(RelationTriple("seed-2_r0", "seed-2", "Old A", "Old B", "stale edge")),
            analysisSource = AnalysisSource.SEED,
        )
        val userNote = MemoryItem(
            id = "mem_user",
            content = "User note",
            summary = "User note",
            tags = emptyList(),
            createdAtMillis = 50L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = emptyList(),
        )
        // seed-1 and seed-3 were deleted by the user (absent from the store).
        val store = FakeStore(listOf(legacySeed, userNote))
        val repo = MemoryRepository(
            store = store,
            inferenceRouter = buildRouter(),
            backgroundScope = backgroundScope,
        )
        repo.initializeAndSeedIfNeeded()

        val all = store.getAllMemories()
        assertEquals(setOf("seed-2", "mem_user"), all.map { it.id }.toSet())
        val migrated = all.first { it.id == "seed-2" }
        val expected = SeedStorylineProvider.createInitialStoryline().first { it.id == "seed-2" }
        assertEquals(expected.content, migrated.content)
        assertEquals(expected.summary, migrated.summary)
        assertEquals(expected.tags, migrated.tags)
        assertEquals(expected.relations.map { it.source to it.target }, migrated.relations.map { it.source to it.target })
        assertEquals(42L, migrated.createdAtMillis)
        assertEquals(AnalysisSource.SEED, migrated.analysisSource)
    }

    private class ControllableEdgeEngine(
        override val isBackendAvailable: Boolean = true,
        initialState: EngineState = EngineState.Unloaded,
    ) : EdgeAiEngine {
        val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<EngineState> = mutableState
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)
        var configuredDir: String? = null

        override fun configureDefaultModel(modelId: String, modelDirPath: String) {
            configuredDir = modelDirPath
        }

        override suspend fun loadModel(modelId: String, modelDirPath: String): Result<Unit> = Result.success(Unit)

        override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {
            val json = """{"summary":"• Local distillation ok","tags":[],"calendarProposal":null,"relations":[]}"""
            emit(StreamChunk.Delta(json))
            emit(
                StreamChunk.Completed(
                    fullText = json,
                    telemetry = InferenceTelemetry(
                        engineLabel = "MNN-LLM (ARM64)",
                        modelId = "qwen2.5-0.5b-instruct-mnn",
                        ttftMs = 120L,
                        totalMs = 300L,
                        generatedTokens = 10,
                        tokensPerSec = 33.3,
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

    private fun testDownloadManager(
        modelsRoot: File,
        createModelFiles: Boolean,
    ): ModelDownloadManager {
        val modelId = "qwen2.5-0.5b-instruct-mnn"
        if (createModelFiles) {
            val dir = File(modelsRoot, modelId).apply { mkdirs() }
            File(dir, "config.json").writeText("{}")
        }
        val descriptor = ModelDescriptorDto(
            id = modelId,
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
        return ModelDownloadManager(
            primaryModelsDir = modelsRoot,
            externalModelsDir = null,
            okHttpClient = OkHttpClient(),
            initialCatalog = listOf(descriptor),
        )
    }

    private suspend fun <T> awaitReal(timeoutMs: Long = 5_000L, block: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMs) { block() }
        }

    @Test
    fun offlineModeBlocksUnreadyLocalModelAndEmitsScrollToTopWhenReady() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("offline_gate_${System.nanoTime()}.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        awaitReal { prefsRepo.setInferenceMode(InferenceMode.OFFLINE_EDGE) }
        val modelsRoot = tempFolder.newFolder("models_${System.nanoTime()}")
        val engine = ControllableEdgeEngine(isBackendAvailable = true, initialState = EngineState.Unloaded)
        val store = FakeStore()
        val router = buildRouter(engine)
        val repo = MemoryRepository(store, router, preferencesRepository = prefsRepo, backgroundScope = backgroundScope)

        // 1. Not downloaded -> blocked with Open action and draft preserved
        val vmNotDownloaded = CaptureViewModel(
            memoryRepository = repo,
            edgeEngine = engine,
            preferencesRepository = prefsRepo,
            downloadManager = testDownloadManager(modelsRoot, createModelFiles = false),
        )
        vmNotDownloaded.onIntent(CaptureIntent.UpdateInput("Keep this draft"))
        vmNotDownloaded.onIntent(CaptureIntent.SubmitCapture)
        val err1 = awaitReal { vmNotDownloaded.effects.first() } as CaptureEffect.ShowEngineNotice
        assertEquals("Download the on-device model first", err1.message)
        assertEquals("Open", err1.actionLabel)
        assertEquals("Keep this draft", vmNotDownloaded.inputText.value)
        assertEquals(0, store.countMemories())

        // 2. Downloaded on disk (even while EngineState.Unloaded) -> configures model dir, auto-loads, accepts & scrolls to top
        val dlDownloaded = testDownloadManager(modelsRoot, createModelFiles = true)
        val vmDownloadedUnloaded = CaptureViewModel(
            memoryRepository = repo,
            edgeEngine = engine,
            preferencesRepository = prefsRepo,
            downloadManager = dlDownloaded,
        )
        val memCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vmDownloadedUnloaded.memories.collect {}
        }
        vmDownloadedUnloaded.onIntent(CaptureIntent.UpdateInput("Lazy auto-load note"))
        vmDownloadedUnloaded.onIntent(CaptureIntent.SubmitCapture)
        val lazyEffects = awaitReal { vmDownloadedUnloaded.effects.take(2).toList() }
        assertEquals(CaptureEffect.DismissNotice, lazyEffects[0])
        val lazyScroll = lazyEffects[1] as CaptureEffect.ScrollToTop
        assertNotNull(engine.configuredDir)
        assertEquals("", vmDownloadedUnloaded.inputText.value)
        val inserted = awaitReal {
            vmDownloadedUnloaded.memories.first { list -> list.any { it.id == lazyScroll.memoryId && it.analysisStatus == MemoryAnalysisStatus.READY } }
                .first { it.id == lazyScroll.memoryId }
        }
        assertEquals(AnalysisSource.MNN_EDGE, inserted.analysisSource)
        memCollector.cancel()
    }

    @Test
    fun cloudModeDegradesWithNoticeWhenLocalReadyAndBlocksWhenBothUnavailable() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("cloud_gate_${System.nanoTime()}.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        awaitReal {
            prefsRepo.setInferenceMode(InferenceMode.CLOUD_COLLAB)
            prefsRepo.updateSyncConfig("http://127.0.0.1:8787", "")
        }
        var cloudHits = 0
        val countingHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                cloudHits++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val store = FakeStore()
        val emptyModelsDir = tempFolder.newFolder("empty_models_${System.nanoTime()}")

        // Case A: Cloud unconfigured & no local model -> blocked
        val noEdge = ControllableEdgeEngine(isBackendAvailable = true, initialState = EngineState.Unloaded)
        val noEdgeRouter = UnifiedInferenceRouter(noEdge, CloudSseClient(countingHttp), MemoryAnalyzer())
        val repo = MemoryRepository(store, noEdgeRouter, preferencesRepository = prefsRepo, backgroundScope = backgroundScope)
        val blockedVm = CaptureViewModel(
            memoryRepository = repo,
            edgeEngine = noEdge,
            preferencesRepository = prefsRepo,
            downloadManager = testDownloadManager(emptyModelsDir, createModelFiles = false),
        )
        blockedVm.onIntent(CaptureIntent.UpdateInput("Unconfigured note"))
        blockedVm.onIntent(CaptureIntent.SubmitCapture)
        val blockedNotice = awaitReal { blockedVm.effects.first() } as CaptureEffect.ShowEngineNotice
        assertTrue(blockedNotice.message.startsWith("Cloud not configured"))
        assertEquals("Open", blockedNotice.actionLabel)
        assertEquals("Unconfigured note", blockedVm.inputText.value)
        assertEquals(0, store.countMemories())
        assertEquals(0, cloudHits)

        // Case B: Cloud InvalidToken + local Ready -> emits degrade notice + ScrollToTop, skips cloud HTTP
        val validLookingToken = listOf("eng", "69200000", "team01", "c".repeat(24)).joinToString("_")
        awaitReal { prefsRepo.updateSyncConfig("http://127.0.0.1:8787", validLookingToken) }
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val sessionMonitor = CloudSessionMonitor(
            preferencesRepository = prefsRepo,
            authClient = CloudAuthClient(countingHttp, ioDispatcher = testDispatcher),
            hasBundledDefaultEndpoint = true,
            debounceMs = 0L,
            scope = CoroutineScope(backgroundScope.coroutineContext + testDispatcher),
        )
        val monitorCollector = backgroundScope.launch(testDispatcher) {
            sessionMonitor.state.collect {}
        }
        awaitReal { sessionMonitor.state.first { it is CloudSessionState.InvalidToken } }
        val hitsBeforeSubmit = cloudHits

        val readyEdge = ControllableEdgeEngine(isBackendAvailable = true, initialState = EngineState.Ready("qwen2.5-0.5b-instruct-mnn", "MNN", 400L))
        val downloadedDir = tempFolder.newFolder("ready_models_${System.nanoTime()}")
        val readyRouter = UnifiedInferenceRouter(readyEdge, CloudSseClient(countingHttp), MemoryAnalyzer())
        val readyRepo = MemoryRepository(store, readyRouter, preferencesRepository = prefsRepo, backgroundScope = backgroundScope)
        val degradeVm = CaptureViewModel(
            memoryRepository = readyRepo,
            edgeEngine = readyEdge,
            preferencesRepository = prefsRepo,
            cloudSessionMonitor = sessionMonitor,
            downloadManager = testDownloadManager(downloadedDir, createModelFiles = true),
        )
        val degradeCollector = backgroundScope.launch(testDispatcher) { degradeVm.memories.collect {} }

        degradeVm.onIntent(CaptureIntent.UpdateInput("Fallback with local ready"))
        degradeVm.onIntent(CaptureIntent.SubmitCapture)
        val effects = awaitReal { degradeVm.effects.take(2).toList() }
        val degradeNotice = effects[0] as CaptureEffect.ShowMessage
        assertEquals("Cloud access token invalid · Using on-device model", degradeNotice.message)
        val scroll = effects[1] as CaptureEffect.ScrollToTop
        assertEquals("", degradeVm.inputText.value)
        assertEquals(hitsBeforeSubmit, cloudHits)
        val saved = awaitReal {
            degradeVm.memories.first { list -> list.any { it.id == scroll.memoryId && it.analysisStatus == MemoryAnalysisStatus.READY } }
                .first { it.id == scroll.memoryId }
        }
        assertEquals(AnalysisSource.MNN_EDGE, saved.analysisSource)
        assertTrue(saved.telemetryBadge.startsWith("Cloud fallback · Qwen2.5 0.5B · "))
        degradeCollector.cancel()
        monitorCollector.cancel()
    }

    @Test
    fun captureUiStateTracksDeleteBubbleAndFallsBackQuietlyWithoutBadgeOrNotice() = runTest {
        val flakeyEngine = object : EdgeAiEngine {
            override val state: StateFlow<EngineState> = MutableStateFlow(
                EngineState.Ready("qwen2.5-0.5b-instruct-mnn", "MNN-LLM (ARM64)", 400L * 1024 * 1024),
            )
            override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)
            override val isBackendAvailable: Boolean = true
            override suspend fun loadModel(modelId: String, modelDirPath: String) = Result.success(Unit)
            override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {
                val text = "{\"summary\":\"unterminated loop..."
                emit(StreamChunk.Delta(text))
                emit(
                    StreamChunk.Completed(
                        fullText = text,
                        telemetry = InferenceTelemetry(
                            engineLabel = "MNN-LLM (ARM64)",
                            modelId = "qwen2.5-0.5b-instruct-mnn",
                            ttftMs = 150L,
                            totalMs = 420L,
                            generatedTokens = 14,
                            tokensPerSec = 33.3,
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

        val store = FakeStore()
        val repo = MemoryRepository(store, buildRouter(flakeyEngine), backgroundScope = backgroundScope)
        val vm = CaptureViewModel(memoryRepository = repo, edgeEngine = flakeyEngine)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.onIntent(CaptureIntent.UpdateInput("Check sample shipment tomorrow at 3pm"))
        vm.onIntent(CaptureIntent.SubmitCapture)

        val fallbackCard = awaitReal {
            vm.uiState.first { ui ->
                ui.memories.any {
                    it.content == "Check sample shipment tomorrow at 3pm" &&
                        it.analysisStatus == MemoryAnalysisStatus.READY &&
                        it.analysisSource == AnalysisSource.LOCAL_RULE_FALLBACK
                }
            }.memories.first { it.content == "Check sample shipment tomorrow at 3pm" }
        }
        assertEquals("", fallbackCard.telemetryBadge)

        vm.onIntent(CaptureIntent.SetDeleteBubbleMemory(fallbackCard.id))
        assertEquals(fallbackCard.id, vm.uiState.value.activeDeleteBubbleMemoryId)
        vm.onIntent(CaptureIntent.SetDeleteBubbleMemory(null))
        assertNull(vm.uiState.value.activeDeleteBubbleMemoryId)
        collector.cancel()
    }

    @Test
    fun offlineModeColdStartAskConfiguresDefaultModelBeforeOpeningAnswerSheet() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("offline_ask_cold_${System.nanoTime()}.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        awaitReal { prefsRepo.setInferenceMode(InferenceMode.OFFLINE_EDGE) }
        val modelsRoot = tempFolder.newFolder("models_ask_${System.nanoTime()}")
        val engine = ControllableEdgeEngine(isBackendAvailable = true, initialState = EngineState.Unloaded)
        val repo = MemoryRepository(
            store = FakeStore(),
            inferenceRouter = buildRouter(engine),
            preferencesRepository = prefsRepo,
            backgroundScope = backgroundScope,
        )
        val vm = CaptureViewModel(
            memoryRepository = repo,
            edgeEngine = engine,
            preferencesRepository = prefsRepo,
            downloadManager = testDownloadManager(modelsRoot, createModelFiles = true),
        )

        vm.onIntent(CaptureIntent.UpdateInput("\u6211\u8981\u5403\u4EC0\u4E48\uFF1F"))
        vm.onIntent(CaptureIntent.SubmitCapture)

        val effects = awaitReal { vm.effects.take(2).toList() }
        assertEquals(CaptureEffect.DismissNotice, effects[0])
        assertEquals(CaptureEffect.OpenAnswer("\u6211\u8981\u5403\u4EC0\u4E48\uFF1F"), effects[1])
        assertNotNull(engine.configuredDir)
        assertEquals("", vm.inputText.value)
    }
}
