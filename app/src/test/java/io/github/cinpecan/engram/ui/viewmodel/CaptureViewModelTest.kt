package io.github.cinpecan.engram.ui.viewmodel

import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeStore(initial: List<MemoryItem> = emptyList()) : LocalMemoryStore {
        private val items = MutableStateFlow(initial)
        val markedCalendarSavedIds = mutableListOf<String>()

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
            embedding: FloatArray?,
        ) {
            items.value = items.value.map { item ->
                if (item.id == memoryId) {
                    item.copy(
                        summary = payload.summary,
                        tags = payload.tags,
                        analysisStatus = MemoryAnalysisStatus.READY,
                        calendarProposal = payload.calendarProposal,
                        relations = payload.relations,
                    )
                } else {
                    item
                }
            }
        }

        override suspend fun markCalendarSaved(memoryId: String) {
            markedCalendarSavedIds.add(memoryId)
            items.value = items.value.map { item ->
                if (item.id == memoryId) item.copy(calendarSaved = true) else item
            }
        }

        override suspend fun getAllMemories(): List<MemoryItem> = items.value
        override suspend fun queryFtsLexicalIds(query: String): List<String> = emptyList()
        override suspend fun loadAllEmbeddings(): Map<String, FloatArray> = emptyMap()
        override suspend fun countMemories(): Int = items.value.size
    }

    private class StubEngine : EdgeAiEngine {
        override val state: StateFlow<EngineState> = MutableStateFlow(EngineState.Unloaded)
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)
        override suspend fun loadModel(modelId: String, modelDirPath: String) = Result.success(Unit)
        override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {
            emit(
                StreamChunk.Completed(
                    fullText = "",
                    telemetry = InferenceTelemetry("stub", "stub", 1, 1, 0, 0.0, 0.0, false),
                ),
            )
        }
        override suspend fun embedText(text: String, dims: Int) = FloatArray(dims)
        override suspend fun unload(reason: UnloadReason): Long = 0L
        override fun cancelActiveGeneration() = Unit
        override fun onTrimMemory(level: Int) = Unit
        override fun currentNativeHeapBytes(): Long = 0L
    }

    @Test
    fun updateInputAndFillSampleUpdateInputTextSynchronously() {
        val store = FakeStore()
        val engine = StubEngine()
        val repo = MemoryRepository(store, engine, MemoryAnalyzer(engine))
        val vm = CaptureViewModel(repo)

        vm.onIntent(CaptureIntent.UpdateInput("测试草稿"))
        assertEquals("测试草稿", vm.inputText.value)

        vm.onIntent(CaptureIntent.FillSample("示例内容"))
        assertEquals("示例内容", vm.inputText.value)
    }

    @Test
    fun submitCapturePersistsMemoryAndClearsInput() = runTest {
        val store = FakeStore()
        val engine = StubEngine()
        val repo = MemoryRepository(
            store = store,
            engine = engine,
            analyzer = MemoryAnalyzer(engine),
            backgroundScope = backgroundScope,
        )
        val vm = CaptureViewModel(repo)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.memories.collect {}
        }

        vm.onIntent(CaptureIntent.UpdateInput("和陈工确认柔性屏采购交期"))
        vm.onIntent(CaptureIntent.SubmitCapture)

        assertEquals("", vm.inputText.value)
        assertFalse(vm.isSubmitting.value)
        assertTrue(vm.memories.value.any { it.content == "和陈工确认柔性屏采购交期" })
    }

    @Test
    fun calendarAddIntentEmitsLaunchEffectAndPersistsOnSuccess() = runTest {
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
        val engine = StubEngine()
        val repo = MemoryRepository(store, engine, MemoryAnalyzer(engine), backgroundScope = backgroundScope)
        val vm = CaptureViewModel(repo)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.memories.collect {}
        }

        vm.onIntent(CaptureIntent.RequestAddCalendar("mem-cal-1", proposal))
        val launchEffect = vm.effects.first()
        assertEquals(CaptureEffect.LaunchCalendarInsert("mem-cal-1", proposal), launchEffect)

        vm.onIntent(
            CaptureIntent.ConfirmCalendarLaunchResult(
                memoryId = "mem-cal-1",
                title = proposal.title,
                launched = false,
            ),
        )
        assertEquals(CaptureEffect.ShowMessage("未检测到可用的系统日历应用"), vm.effects.first())
        assertTrue(store.markedCalendarSavedIds.isEmpty())

        vm.onIntent(
            CaptureIntent.ConfirmCalendarLaunchResult(
                memoryId = "mem-cal-1",
                title = proposal.title,
                launched = true,
            ),
        )
        assertEquals(CaptureEffect.ShowMessage("已拉起系统日历草稿：固件验收会议"), vm.effects.first())
        assertEquals(listOf("mem-cal-1"), store.markedCalendarSavedIds)
        assertTrue(vm.memories.value.single().calendarSaved)
    }
}
