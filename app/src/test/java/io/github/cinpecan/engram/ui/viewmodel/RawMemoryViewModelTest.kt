package io.github.cinpecan.engram.ui.viewmodel

import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.edgemnn.InferenceTelemetry
import io.github.cinpecan.engram.edgemnn.StreamChunk
import io.github.cinpecan.engram.edgemnn.UnloadReason
import io.github.cinpecan.engram.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RawMemoryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class DeferredLocalStore(
        private val responses: Map<String, CompletableDeferred<MemoryItem?>>,
    ) : LocalMemoryStore {
        override fun observeMemories(): Flow<List<MemoryItem>> = flow { emit(emptyList()) }
        override fun observeMemoryById(memoryId: String): Flow<MemoryItem?> = flow {
            val result = responses[memoryId]?.await()
            emit(result)
        }
        override fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> = flow { emit(emptyList()) }
        override suspend fun insertRawMemory(item: MemoryItem) = Unit
        override suspend fun updateMemoryAnalysis(
            memoryId: String,
            payload: MemoryAnalysisPayload,
            embedding: FloatArray?,
        ) = Unit
        override suspend fun markCalendarSaved(memoryId: String) = Unit
        override suspend fun getAllMemories(): List<MemoryItem> = emptyList()
        override suspend fun queryFtsLexicalIds(query: String): List<String> = emptyList()
        override suspend fun loadAllEmbeddings(): Map<String, FloatArray> = emptyMap()
        override suspend fun countMemories(): Int = 0
    }

    private class StubEngine : EdgeAiEngine {
        override val state: StateFlow<EngineState> = MutableStateFlow(EngineState.Unloaded)
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)
        override suspend fun loadModel(modelId: String, modelDirPath: String) = Result.success(Unit)
        override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {}
        override suspend fun embedText(text: String, dims: Int) = FloatArray(dims)
        override suspend fun unload(reason: UnloadReason): Long = 0L
        override fun cancelActiveGeneration() = Unit
        override fun onTrimMemory(level: Int) = Unit
        override fun currentNativeHeapBytes(): Long = 0L
    }

    @Test
    fun uiStateStartsLoadingAndTransitionsToReadyOrNotFound() = runTest {
        val foundDeferred = CompletableDeferred<MemoryItem?>()
        val missingDeferred = CompletableDeferred<MemoryItem?>()
        val store = DeferredLocalStore(
            mapOf(
                "mem-1" to foundDeferred,
                "mem-missing" to missingDeferred,
            ),
        )
        val engine = StubEngine()
        val repo = MemoryRepository(store, engine, MemoryAnalyzer(engine), backgroundScope = backgroundScope)

        val readyVm = RawMemoryViewModel("mem-1", repo)
        val missingVm = RawMemoryViewModel("mem-missing", repo)

        assertEquals(RawMemoryUiState.Loading, readyVm.uiState.value)
        assertEquals(RawMemoryUiState.Loading, missingVm.uiState.value)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            readyVm.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            missingVm.uiState.collect {}
        }

        // Still Loading while DB query is in flight
        assertEquals(RawMemoryUiState.Loading, readyVm.uiState.value)
        assertEquals(RawMemoryUiState.Loading, missingVm.uiState.value)

        val expectedMemory = MemoryItem(
            id = "mem-1",
            content = "原始印迹内容",
            summary = "摘要",
            tags = emptyList(),
            createdAtMillis = 1_000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = emptyList(),
        )
        foundDeferred.complete(expectedMemory)
        missingDeferred.complete(null)

        assertEquals(RawMemoryUiState.Ready(expectedMemory), readyVm.uiState.value)
        assertEquals(RawMemoryUiState.NotFound, missingVm.uiState.value)
    }
}
