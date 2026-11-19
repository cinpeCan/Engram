package io.github.cinpecan.engram.ui.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.cinpecan.engram.data.calendar.CalendarAnchorTarget
import io.github.cinpecan.engram.data.calendar.SystemCalendarObserver
import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.data.repository.UnifiedInferenceRouter
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.edgemnn.InferenceTelemetry
import io.github.cinpecan.engram.edgemnn.StreamChunk
import io.github.cinpecan.engram.edgemnn.UnloadReason
import io.github.cinpecan.engram.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            items.value = listOf(item) + items.value
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

        override suspend fun restoreMemory(item: MemoryItem) {
            tombstones.remove(item.id)
            if (items.value.none { it.id == item.id }) {
                items.value = (listOf(item) + items.value).sortedByDescending { it.createdAtMillis }
            }
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

        vm.onIntent(CaptureIntent.UpdateInput("测试草稿"))
        assertEquals("测试草稿", vm.inputText.value)

        vm.onIntent(CaptureIntent.FillSample("示例内容"))
        assertEquals("示例内容", vm.inputText.value)
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

        vm.onIntent(CaptureIntent.UpdateInput("和陈工确认柔性屏采购交期"))
        vm.onIntent(CaptureIntent.SubmitCapture)

        val readyList = withTimeout(5_000L) {
            vm.memories.first { list ->
                list.any {
                    it.content == "和陈工确认柔性屏采购交期" &&
                        it.analysisStatus == MemoryAnalysisStatus.READY
                }
            }
        }

        assertEquals("", vm.inputText.value)
        assertFalse(vm.isSubmitting.value)
        val saved = readyList.first { it.content == "和陈工确认柔性屏采购交期" }
        assertEquals(MemoryAnalysisStatus.READY, saved.analysisStatus)
        assertEquals(AnalysisSource.LOCAL_RULE_FALLBACK, saved.analysisSource)
        assertTrue(vm.streamingPreviews.value.isEmpty())
    }

    @Test
    fun calendarStateReactsToSystemProviderWithoutOptimisticWrites() = runTest {
        val proposal = CalendarProposal(
            title = "固件验收会议",
            startTimeIso = "2026-11-18T10:30:00",
            durationMinutes = 60,
            location = "5号楼会议室",
        )
        val seed = MemoryItem(
            id = "mem-cal-1",
            content = "明天上午10:30在5号楼会议室开固件验收会议",
            summary = "明日 10:30 固件验收会议",
            tags = listOf("日程提醒"),
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
        assertEquals(CaptureEffect.ShowMessage("未检测到可用的系统日历应用"), vm.effects.first())

        // Permission signal propagates
        vm.onIntent(CaptureIntent.OnCalendarPermissionResult(granted = true))
        assertEquals(1, observer.permissionNotifyCount)
    }

    @Test
    fun deleteAndUndoRestoresCardAndDeletingAllSeedsPreventsReseed() = runTest {
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

        // Delete first card and restore via Undo
        val firstCard = initialMemories.first()
        vm.onIntent(CaptureIntent.DeleteMemory(firstCard))
        val undoEffect = vm.effects.first() as CaptureEffect.ShowUndoDelete
        assertEquals("已删除印迹", undoEffect.message)
        withTimeout(2_000L) { vm.memories.first { it.size == 2 } }

        vm.onIntent(CaptureIntent.UndoDeleteMemory)
        withTimeout(2_000L) { vm.memories.first { it.size == 3 } }

        // Delete all seed cards; re-running initializeAndSeedIfNeeded must NOT resurrect them
        for (item in initialMemories) {
            vm.onIntent(CaptureIntent.DeleteMemory(item))
        }
        withTimeout(2_000L) { vm.memories.first { it.isEmpty() } }
        repo.initializeAndSeedIfNeeded()
        assertEquals(0, store.countMemories())
    }
}
