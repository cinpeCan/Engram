package io.github.cinpecan.engram.data.repository

import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.remote.CloudSseClient
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedInferenceRouterTest {

    private class FakeLocalStore(initial: List<MemoryItem>) : LocalMemoryStore {
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

        override suspend fun insertRawMemory(item: MemoryItem) {
            items.value = listOf(item) + items.value
        }

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

    private class FakeEdgeEngine : EdgeAiEngine {
        var lastPrompt: String = ""
        override val state: StateFlow<EngineState> = MutableStateFlow(EngineState.Ready("qwen", "MNN", 256L))
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)
        override suspend fun loadModel(modelId: String, modelDirPath: String) = Result.success(Unit)
        override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {
            lastPrompt = prompt
            emit(StreamChunk.Delta("密码是 Admin@9527"))
            emit(
                StreamChunk.Completed(
                    fullText = "密码是 Admin@9527",
                    telemetry = InferenceTelemetry("MNN Edge Runtime", "qwen", 15, 30, 6, 200.0, 256.0, false),
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
    fun emitsCitationsBeforeStreamingOfflineAnswerAndFallsBackWhenCloudUnreachable() = runTest {
        val seedItem = MemoryItem(
            id = "seed-1",
            content = "调试后台账号 test_panel，初始密码 Admin@9527",
            summary = "调试凭证",
            tags = listOf("凭证备份"),
            createdAtMillis = 1000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = emptyList(),
        )
        val store = FakeLocalStore(listOf(seedItem))
        val engine = FakeEdgeEngine()
        val repo = MemoryRepository(store, engine, MemoryAnalyzer(engine))
        val router = UnifiedInferenceRouter(repo, engine, CloudSseClient(OkHttpClient()))

        val offlineEvents = router.ask("测试密码是多少？", InferenceMode.OFFLINE_EDGE, "http://127.0.0.1:1").toList()
        val citations = offlineEvents.filterIsInstance<AskStreamEvent.CitationsReady>().single()
        val completed = offlineEvents.filterIsInstance<AskStreamEvent.Completed>().single()

        assertEquals("seed-1", citations.citations.first().memory.id)
        assertTrue(engine.lastPrompt.contains("Admin@9527"))
        assertEquals("密码是 Admin@9527", completed.fullText)

        // Cloud mode targeting closed local port should degrade gracefully to edge engine
        val fallbackEvents = router.ask("测试密码是多少？", InferenceMode.CLOUD_COLLAB, "http://127.0.0.1:1").toList()
        val deltas = fallbackEvents.filterIsInstance<AskStreamEvent.TokenDelta>().joinToString("") { it.delta }
        assertTrue(deltas.contains("已自动降级为端侧 MNN 离线推理"))
    }
}
