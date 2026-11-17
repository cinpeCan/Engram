package io.github.cinpecan.engram.ui.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.data.repository.UnifiedInferenceRouter
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
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
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeStore(initial: List<MemoryItem>) : LocalMemoryStore {
        private val items = MutableStateFlow(initial)
        override fun observeMemories(): Flow<List<MemoryItem>> = items
        override fun observeMemoryById(memoryId: String): Flow<MemoryItem?> =
            items.map { list -> list.firstOrNull { it.id == memoryId } }
        override fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> =
            items.map { emptyList() }
        override suspend fun insertRawMemory(item: MemoryItem) = Unit
        override suspend fun updateMemoryAnalysis(
            memoryId: String,
            payload: MemoryAnalysisPayload,
            embedding: FloatArray?,
        ) = Unit
        override suspend fun markCalendarSaved(memoryId: String) = Unit
        override suspend fun getAllMemories(): List<MemoryItem> = items.value
        override suspend fun queryFtsLexicalIds(query: String): List<String> = items.value.map { it.id }
        override suspend fun loadAllEmbeddings(): Map<String, FloatArray> =
            items.value.associate { it.id to FloatArray(16) { idx -> if (idx == 0) 1f else 0f } }
        override suspend fun countMemories(): Int = items.value.size
    }

    private class StubEngine : EdgeAiEngine {
        override val state: StateFlow<EngineState> = MutableStateFlow(EngineState.Ready("qwen", "MNN", 256L))
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)
        override suspend fun loadModel(modelId: String, modelDirPath: String) = Result.success(Unit)
        override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {
            emit(StreamChunk.Delta("密码是 Admin@9527"))
            emit(
                StreamChunk.Completed(
                    fullText = "密码是 Admin@9527",
                    telemetry = InferenceTelemetry("MNN Edge Runtime", "qwen", 12, 24, 6, 250.0, 256.0, false),
                ),
            )
        }
        override suspend fun embedText(text: String, dims: Int) = FloatArray(dims) { if (it == 0) 1f else 0f }
        override suspend fun unload(reason: UnloadReason): Long = 0L
        override fun cancelActiveGeneration() = Unit
        override fun onTrimMemory(level: Int) = Unit
        override fun currentNativeHeapBytes(): Long = 256L * 1024 * 1024
    }

    @Test
    fun selectModeAndSubmitQueryStreamCitationsAndAnswerSeparatelyFromInput() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("ask_prefs.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        val seedItem = MemoryItem(
            id = "seed-1",
            content = "调试后台账号 test_panel，初始密码 Admin@9527",
            summary = "调试凭证",
            tags = listOf("凭证备份"),
            createdAtMillis = 1_000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = emptyList(),
        )
        val store = FakeStore(listOf(seedItem))
        val engine = StubEngine()
        val memoryRepo = MemoryRepository(store, engine, MemoryAnalyzer(engine), backgroundScope = backgroundScope)
        val router = UnifiedInferenceRouter(memoryRepo, engine, CloudSseClient(OkHttpClient()))
        val vm = AskViewModel(router, prefsRepo)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.inferenceMode.collect {}
        }

        vm.onIntent(AskIntent.SelectMode(InferenceMode.CLOUD_COLLAB))
        assertEquals(
            InferenceMode.CLOUD_COLLAB,
            vm.inferenceMode.first { it == InferenceMode.CLOUD_COLLAB },
        )

        vm.onIntent(AskIntent.SelectMode(InferenceMode.OFFLINE_EDGE))
        assertEquals(
            InferenceMode.OFFLINE_EDGE,
            vm.inferenceMode.first { it == InferenceMode.OFFLINE_EDGE },
        )

        vm.onIntent(AskIntent.UpdateQuery("测试密码是多少？"))
        assertEquals("测试密码是多少？", vm.queryInput.value)
        assertTrue(vm.turns.value.isEmpty())

        vm.onIntent(AskIntent.SubmitQuery())
        assertEquals("", vm.queryInput.value)

        val completedTurns = vm.turns.first { list -> list.size == 1 && !list[0].isStreaming }
        assertFalse(vm.isAsking.first { !it })

        val turn = completedTurns.single()
        assertEquals("测试密码是多少？", turn.query)
        assertEquals("seed-1", turn.citations.first().memory.id)
        assertEquals("密码是 Admin@9527", turn.answerText)
        assertNotNull(turn.telemetry)
    }
}
