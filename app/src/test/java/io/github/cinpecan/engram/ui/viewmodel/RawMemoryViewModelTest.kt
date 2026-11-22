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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RawMemoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private class FakeStore(initial: List<MemoryItem>) : LocalMemoryStore {
        private val items = MutableStateFlow(initial)
        override fun observeMemories(): Flow<List<MemoryItem>> = items
        override fun observeMemoryById(memoryId: String): Flow<MemoryItem?> =
            items.map { list -> list.firstOrNull { it.id == memoryId } }
        override fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> = flow { emit(emptyList()) }
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
    fun observesRequestedMemoryAndHandlesMissingId() = runTest(testDispatcher) {
        val m1 = MemoryItem(
            id = "m1",
            content = "This is the unprocessed source text, which may be long and contain credentials.",
            summary = "Credentials saved (see source)",
            tags = listOf("Credentials"),
            createdAtMillis = 1_000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = emptyList(),
        )
        val store = FakeStore(listOf(m1))
        val router = UnifiedInferenceRouter(StubEngine(), CloudSseClient(OkHttpClient()), MemoryAnalyzer())
        val repo = MemoryRepository(store, router, backgroundScope = backgroundScope)

        val vmReady = RawMemoryViewModel("m1", repo)
        val ready = withTimeout(3_000L) {
            vmReady.uiState.first { it is RawMemoryUiState.Ready }
        } as RawMemoryUiState.Ready
        assertEquals(m1.content, ready.memory.content)

        val vmMissing = RawMemoryViewModel("nonexistent", repo)
        val missing = withTimeout(3_000L) {
            vmMissing.uiState.first { it !is RawMemoryUiState.Loading }
        }
        assertTrue(missing is RawMemoryUiState.NotFound)

        // Copying source flips isCopied -> true and resets automatically after COPY_CONFIRM_MS
        val copyCollector = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            vmReady.isCopied.collect {}
        }
        runCurrent()
        assertFalse(vmReady.isCopied.value)
        vmReady.onIntent(RawMemoryIntent.OnSourceCopied)
        runCurrent()
        assertTrue(vmReady.isCopied.value)
        advanceTimeBy(RawMemoryViewModel.COPY_CONFIRM_MS + 100L)
        runCurrent()
        assertFalse(vmReady.isCopied.value)
        copyCollector.cancel()
    }
}
