package io.github.cinpecan.engram.ui.viewmodel

import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.data.repository.UnifiedInferenceRouter
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.model.AnalysisSource
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GraphViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeStore(initial: List<MemoryItem>) : LocalMemoryStore {
        private val items = MutableStateFlow(initial)
        override fun observeMemories(): Flow<List<MemoryItem>> = items
        override fun observeMemoryById(memoryId: String): Flow<MemoryItem?> =
            items.map { list -> list.firstOrNull { it.id == memoryId } }
        override fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> =
            items.map { list ->
                list.filter { item ->
                    item.content.contains(entityName, ignoreCase = true) ||
                        item.relations.any { it.source == entityName || it.target == entityName }
                }
            }
        override suspend fun insertRawMemory(item: MemoryItem) = Unit
        override suspend fun updateMemoryAnalysis(
            memoryId: String,
            payload: MemoryAnalysisPayload,
            analysisSource: AnalysisSource,
            telemetryBadge: String,
        ) = Unit
        override suspend fun markCalendarSaved(memoryId: String) = Unit
        override suspend fun getAllMemories(): List<MemoryItem> = items.value
        override suspend fun countMemories(): Int = items.value.size
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

    @Test
    fun bindMemoryAndSelectEntityDriveCanvasAndOnDemandCitations() = runTest {
        val m1 = MemoryItem(
            id = "m1",
            content = "老李向张姐提交合同草案",
            summary = "老李提交合同草案",
            tags = listOf("商务合同"),
            createdAtMillis = 2_000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = listOf(RelationTriple("r1", "m1", "老李", "张姐", "提交草案", false)),
        )
        val m2 = MemoryItem(
            id = "m2",
            content = "陈工向刘敏提交验收单",
            summary = "陈工提交验收单",
            tags = listOf("项目推进"),
            createdAtMillis = 1_500L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = listOf(RelationTriple("r2", "m2", "陈工", "刘敏", "验收", false)),
        )
        val m3NoRel = MemoryItem(
            id = "m3",
            content = "今天阳光很好",
            summary = "今天阳光很好",
            tags = emptyList(),
            createdAtMillis = 1_000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = emptyList(),
        )

        val store = FakeStore(listOf(m1, m2, m3NoRel))
        val router = UnifiedInferenceRouter(StubEngine(), CloudSseClient(OkHttpClient()), MemoryAnalyzer())
        val repo = MemoryRepository(store, router, backgroundScope = backgroundScope)
        val vm = GraphViewModel(repo)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.availableMemories.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.canvasState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.entityCitation.collect {}
        }

        assertEquals(listOf("m1", "m2"), vm.availableMemories.value.map { it.id })
        assertEquals("m1", vm.canvasState.value.activeMemoryId)
        assertNull(vm.entityCitation.value)

        vm.onIntent(GraphIntent.BindMemory("m2"))
        assertEquals("m2", vm.canvasState.value.activeMemoryId)
        assertEquals(setOf("陈工", "刘敏"), vm.canvasState.value.nodes.map { it.id }.toSet())
        assertEquals(1, vm.canvasState.value.visibleEdges.size)

        vm.onIntent(GraphIntent.SelectEntity("张姐"))
        val citation = vm.entityCitation.value
        assertEquals("张姐", citation?.entityName)
        assertEquals(listOf("m1"), citation?.relatedMemories?.map { it.id })

        vm.onIntent(GraphIntent.ClearSelectedEntity)
        assertNull(vm.entityCitation.value)
        assertTrue(vm.canvasState.value.nodes.isNotEmpty())
    }
}
