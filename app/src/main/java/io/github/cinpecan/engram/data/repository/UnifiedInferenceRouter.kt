package io.github.cinpecan.engram.data.repository

import io.github.cinpecan.engram.data.remote.ChatStreamRequestDto
import io.github.cinpecan.engram.data.remote.CitationItemDto
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.domain.rag.RagSearchResult
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.InferenceTelemetry
import io.github.cinpecan.engram.edgemnn.StreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface AskStreamEvent {
    data class CitationsReady(val citations: List<RagSearchResult>) : AskStreamEvent
    data class TokenDelta(val delta: String) : AskStreamEvent
    data class Completed(val fullText: String, val telemetry: InferenceTelemetry) : AskStreamEvent
}

/**
 * Coordinates local Hybrid RAG citation lookup with either on-device MNN streaming
 * or same-repo FastAPI SSE cloud fallback, emitting unified telemetry.
 */
class UnifiedInferenceRouter(
    private val memoryRepository: MemoryRepository,
    private val edgeEngine: EdgeAiEngine,
    private val cloudSseClient: CloudSseClient,
) {
    fun ask(
        query: String,
        mode: InferenceMode,
        serverBaseUrl: String,
    ): Flow<AskStreamEvent> = flow {
        val citations = memoryRepository.searchHybrid(query, topK = 4)
        emit(AskStreamEvent.CitationsReady(citations))

        if (mode == InferenceMode.CLOUD_COLLAB) {
            val cloudResult = streamFromCloud(query, citations, serverBaseUrl) { delta ->
                emit(AskStreamEvent.TokenDelta(delta))
            }
            if (cloudResult != null) {
                emit(AskStreamEvent.Completed(cloudResult.first, cloudResult.second))
                return@flow
            }
            emit(AskStreamEvent.TokenDelta("[云端网关暂不可达，已自动降级为端侧 MNN 离线推理]\n\n"))
        }

        val groundedPrompt = buildGroundedPrompt(query, citations)
        var finalText = ""
        var finalTelemetry: InferenceTelemetry? = null

        edgeEngine.streamGenerate(groundedPrompt, maxTokens = 512).collect { chunk ->
            when (chunk) {
                is StreamChunk.Delta -> emit(AskStreamEvent.TokenDelta(chunk.text))
                is StreamChunk.Completed -> {
                    finalText = chunk.fullText
                    finalTelemetry = chunk.telemetry
                }
            }
        }

        val telemetry = finalTelemetry ?: InferenceTelemetry(
            engineLabel = "MNN Edge Runtime",
            modelId = "engram-edge-default",
            ttftMs = 0L,
            totalMs = 1L,
            generatedTokens = 0,
            tokensPerSec = 0.0,
            nativeHeapMb = edgeEngine.currentNativeHeapBytes() / (1024.0 * 1024.0),
            isRealMnn = false,
        )
        emit(AskStreamEvent.Completed(finalText, telemetry))
    }

    private suspend fun streamFromCloud(
        query: String,
        citations: List<RagSearchResult>,
        serverBaseUrl: String,
        onDelta: suspend (String) -> Unit,
    ): Pair<String, InferenceTelemetry>? = runCatching {
        val startNs = System.nanoTime()
        var firstTokenMs = -1L
        var lastLatencyMs = 1L
        var activeModel = "engram-cloud-fallback"
        val builder = StringBuilder()
        var chunkCount = 0

        val request = ChatStreamRequestDto(
            query = query,
            citations = citations.map { CitationItemDto(it.memory.id, it.memory.content) },
            model = activeModel,
            stream = true,
        )

        cloudSseClient.streamChat(serverBaseUrl, request).collect { chunk ->
            activeModel = chunk.model
            lastLatencyMs = chunk.latencyMs.coerceAtLeast(1L)
            if (chunk.delta.isNotEmpty()) {
                if (firstTokenMs < 0L) {
                    firstTokenMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                }
                chunkCount++
                builder.append(chunk.delta)
                onDelta(chunk.delta)
            }
        }

        val totalMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(lastLatencyMs)
        val estimatedTokens = (builder.length / 2).coerceAtLeast(chunkCount)
        val telemetry = InferenceTelemetry(
            engineLabel = "Cloud Gateway SSE",
            modelId = activeModel,
            ttftMs = if (firstTokenMs >= 0L) firstTokenMs else totalMs,
            totalMs = totalMs,
            generatedTokens = estimatedTokens,
            tokensPerSec = estimatedTokens.toDouble() * 1000.0 / totalMs.toDouble(),
            nativeHeapMb = edgeEngine.currentNativeHeapBytes() / (1024.0 * 1024.0),
            isRealMnn = false,
        )
        builder.toString() to telemetry
    }.getOrNull()

    private fun buildGroundedPrompt(
        query: String,
        citations: List<RagSearchResult>,
    ): String {
        val contextLines = if (citations.isEmpty()) {
            "(当前无命中记忆)"
        } else {
            citations.mapIndexed { idx, res ->
                "[记忆${idx + 1}] ${res.memory.content}"
            }.joinToString("\n")
        }
        return """
你是 Engram 端侧白盒记忆助手。请严格依据以下本地记忆回答用户问题，禁止编造未提及的信息。
$contextLines

用户问题：$query
简洁回答：
        """.trimIndent()
    }
}
