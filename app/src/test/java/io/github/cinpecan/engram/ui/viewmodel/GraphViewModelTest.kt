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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
        override suspend fun setCalendarSaved(memoryId: String, saved: Boolean) = Unit
        override suspend fun mergeRemoteMemories(remoteItems: List<MemoryItem>): Int = 0
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
    fun bindsSingleMemorySelectsEntityAndReplaysGrowth() = runTest {
        val m1 = MemoryItem(
            id = "m1",
            content = "Marcus Cole sent the contract draft to Sarah Jenkins, who briefed Elena Vance",
            summary = "• Marcus sent the contract draft to Sarah\n• Sarah briefed Elena",
            tags = listOf("Legal"),
            createdAtMillis = 2_000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = listOf(
                RelationTriple("r1", "m1", "Marcus Cole", "Sarah Jenkins", "sent draft", false),
                RelationTriple("r2", "m1", "Sarah Jenkins", "Elena Vance", "briefs", false),
            ),
        )
        val m2 = MemoryItem(
            id = "m2",
            content = "Liam Chen submitted the sign-off to Priya Nair",
            summary = "Liam submitted the sign-off",
            tags = listOf("Supply chain"),
            createdAtMillis = 1_500L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = listOf(RelationTriple("r3", "m2", "Liam Chen", "Priya Nair", "submits sign-off", false)),
        )

        val store = FakeStore(listOf(m1, m2))
        val router = UnifiedInferenceRouter(StubEngine(), CloudSseClient(OkHttpClient()), MemoryAnalyzer())
        val repo = MemoryRepository(store, router, backgroundScope = backgroundScope)
        val vm = GraphViewModel(memoryId = "m1", memoryRepository = repo)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.canvasState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.entityCitation.collect {}
        }

        val state = vm.canvasState.first { it.nodes.isNotEmpty() }
        assertEquals("m1", state.memoryId)
        assertEquals(setOf("Marcus Cole", "Sarah Jenkins", "Elena Vance"), state.nodes.map { it.id }.toSet())
        assertEquals(2, state.totalEdgeCount)
        assertNull(vm.entityCitation.value)

        vm.onIntent(GraphIntent.SelectEntity("Sarah Jenkins"))
        val citation = withTimeout(3_000L) {
            vm.entityCitation.first { it != null }
        }
        assertEquals("Sarah Jenkins", citation?.entityName)
        assertEquals(listOf("m1"), citation?.relatedMemories?.map { it.id })

        vm.onIntent(GraphIntent.ClearSelectedEntity)
        assertNull(vm.entityCitation.value)

        vm.onIntent(GraphIntent.ReplayGrowth)
        val finished = withTimeout(3_000L) {
            vm.canvasState.first { !it.isReplaying && it.visibleEdges.size == 2 }
        }
        assertEquals(2, finished.visibleEdges.size)
        assertTrue(finished.nodes.isNotEmpty())
    }
}
