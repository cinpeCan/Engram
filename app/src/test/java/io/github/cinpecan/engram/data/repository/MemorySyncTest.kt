package io.github.cinpecan.engram.data.repository

import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.data.remote.MemorySyncClient
import io.github.cinpecan.engram.data.remote.MemorySyncRequestDto
import io.github.cinpecan.engram.data.remote.MemorySyncResponseDto
import io.github.cinpecan.engram.data.remote.SyncMemoryItemDto
import io.github.cinpecan.engram.data.remote.SyncRelationTripleDto
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySyncTest {

    private class InMemoryStore(initial: List<MemoryItem>) : LocalMemoryStore {
        private val items = MutableStateFlow(initial)
        override fun observeMemories(): Flow<List<MemoryItem>> = items
        override fun observeMemoryById(memoryId: String): Flow<MemoryItem?> =
            items.map { list -> list.firstOrNull { it.id == memoryId } }
        override fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> = flow { emit(emptyList()) }
        override suspend fun insertRawMemory(item: MemoryItem) {
            items.value = listOf(item) + items.value
        }
        override suspend fun updateMemoryAnalysis(
            memoryId: String,
            payload: MemoryAnalysisPayload,
            analysisSource: AnalysisSource,
            telemetryBadge: String,
        ) = Unit
        override suspend fun markCalendarSaved(memoryId: String) = Unit

        override suspend fun mergeRemoteMemories(remoteItems: List<MemoryItem>): Int {
            val map = items.value.associateBy { it.id }.toMutableMap()
            var applied = 0
            for (remote in remoteItems) {
                val existing = map[remote.id]
                val shouldApply = existing == null || remote.updatedAtMillis > existing.updatedAtMillis
                if (shouldApply) {
                    map[remote.id] = remote
                    applied++
                }
            }
            items.value = map.values.sortedByDescending { it.createdAtMillis }
            return applied
        }

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
    fun syncExcludesSeedItemsUploadsLocalAndMergesRemoteUpdates() = runTest {
        val seed = MemoryItem(
            id = "seed-1",
            content = "Seed note",
            summary = "Seed summary",
            tags = emptyList(),
            createdAtMillis = 100L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = emptyList(),
            analysisSource = AnalysisSource.SEED,
            updatedAtMillis = 100L,
        )
        val localNote = MemoryItem(
            id = "mem_local",
            content = "本地录入笔记",
            summary = "本地提炼摘要",
            tags = listOf("项目推进"),
            createdAtMillis = 2_000L,
            analysisStatus = MemoryAnalysisStatus.READY,
            calendarProposal = null,
            calendarSaved = false,
            relations = emptyList(),
            analysisSource = AnalysisSource.MNN_EDGE,
            updatedAtMillis = 2_500L,
        )

        var capturedReq: MemorySyncRequestDto? = null
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val responseDto = MemorySyncResponseDto(
            syncKey = "engram-demo",
            upsertedCount = 1,
            totalCount = 2,
            serverTimeMillis = 9_999L,
            memories = listOf(
                SyncMemoryItemDto(
                    id = "mem_local",
                    content = "本地录入笔记",
                    summary = "本地提炼摘要",
                    tags = listOf("项目推进"),
                    createdAtMillis = 2_000L,
                    updatedAtMillis = 2_000L,
                    analysisStatus = "READY",
                    calendarProposal = null,
                    calendarSaved = false,
                    analysisSource = "MNN_EDGE",
                    telemetryBadge = "",
                    relations = emptyList(),
                ),
                SyncMemoryItemDto(
                    id = "mem_remote",
                    content = "另一台手机录的备忘",
                    summary = "另一台手机录的备忘",
                    tags = listOf("日程提醒"),
                    createdAtMillis = 3_000L,
                    updatedAtMillis = 3_100L,
                    analysisStatus = "READY",
                    calendarProposal = null,
                    calendarSaved = true,
                    analysisSource = "CLOUD_SSE",
                    telemetryBadge = "Cloud SSE · TTFT 90ms",
                    relations = listOf(
                        SyncRelationTripleDto("老李", "张姐", "对齐条款", bidirectional = true),
                    ),
                ),
            ),
        )

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                capturedReq = json.decodeFromString<MemorySyncRequestDto>(buf.readUtf8())

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.encodeToString(responseDto).toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val store = InMemoryStore(listOf(localNote, seed))
        val repo = MemoryRepository(
            store = store,
            inferenceRouter = UnifiedInferenceRouter(StubEngine(), CloudSseClient(mockHttp), MemoryAnalyzer()),
            syncClient = MemorySyncClient(mockHttp, json),
            backgroundScope = backgroundScope,
        )

        val summary = repo.syncWithServer("http://127.0.0.1:8787", "engram-demo").getOrThrow()
        assertEquals(listOf("mem_local"), capturedReq?.memories?.map { it.id })
        assertEquals(1, summary.uploadedCount)
        assertEquals(1, summary.pulledCount)
        assertEquals(2, summary.totalRemoteCount)

        val merged = store.getAllMemories().associateBy { it.id }
        assertTrue("mem_remote" in merged)
        assertEquals(2_500L, merged["mem_local"]?.updatedAtMillis)
        assertEquals(1, merged["mem_remote"]?.relations?.size)
        assertTrue(merged["mem_remote"]?.calendarSaved == true)
    }
}
