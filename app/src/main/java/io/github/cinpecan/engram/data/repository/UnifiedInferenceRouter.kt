package io.github.cinpecan.engram.data.repository

import io.github.cinpecan.engram.data.remote.AnalysisStreamRequestDto
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.edgemnn.InferenceTelemetry
import io.github.cinpecan.engram.edgemnn.StreamChunk
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface DistillationEvent {
    data class StreamingPreview(
        val partialText: String,
        val stageLabel: String,
    ) : DistillationEvent

    data class Completed(
        val payload: MemoryAnalysisPayload,
        val source: AnalysisSource,
        val telemetryBadge: String,
    ) : DistillationEvent
}

/**
 * Unified inference router driving streaming memory distillation across Cloud SSE,
 * real on-device MNN runtime, and honest local rule fallback.
 */
class UnifiedInferenceRouter(
    private val edgeEngine: EdgeAiEngine,
    private val cloudSseClient: CloudSseClient,
    private val analyzer: MemoryAnalyzer,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun distillMemory(
        content: String,
        nowMillis: Long,
        mode: InferenceMode,
        serverBaseUrl: String,
    ): Flow<DistillationEvent> = flow {
        var degradedFromCloud = false

        if (mode == InferenceMode.CLOUD_COLLAB) {
            val cloudCompleted = tryCloudStream(
                content = content,
                nowMillis = nowMillis,
                serverBaseUrl = serverBaseUrl,
                onPreview = { partial ->
                    emit(DistillationEvent.StreamingPreview(partial, "云端 SSE 流式提炼中…"))
                },
            )
            if (cloudCompleted != null) {
                emit(cloudCompleted)
                return@flow
            }
            degradedFromCloud = true
        }

        if (edgeEngine.state.value is EngineState.Ready || edgeEngine.isBackendAvailable) {
            val edgeCompleted = tryEdgeStream(
                content = content,
                nowMillis = nowMillis,
                degradedFromCloud = degradedFromCloud,
                onPreview = { partial, stage ->
                    emit(DistillationEvent.StreamingPreview(partial, stage))
                },
            )
            if (edgeCompleted != null) {
                emit(edgeCompleted)
                return@flow
            }
        }

        val fallbackPayload = analyzer.fallbackAnalyze(content, nowMillis)
        val fallbackBadge = if (degradedFromCloud) {
            "云端未连接 · 已用本地提炼"
        } else {
            ""
        }
        emit(
            DistillationEvent.Completed(
                payload = fallbackPayload,
                source = AnalysisSource.LOCAL_RULE_FALLBACK,
                telemetryBadge = fallbackBadge,
            ),
        )
    }

    private suspend fun tryCloudStream(
        content: String,
        nowMillis: Long,
        serverBaseUrl: String,
        onPreview: suspend (String) -> Unit,
    ): DistillationEvent.Completed? = runCatching {
        val startNs = System.nanoTime()
        var firstTokenMs = -1L
        var lastLatencyMs = 1L
        val builder = StringBuilder()
        var chunkCount = 0

        val nowIso = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        val request = AnalysisStreamRequestDto(
            content = content,
            nowIso = nowIso,
        )

        cloudSseClient.streamAnalyze(serverBaseUrl, request).collect { chunk ->
            lastLatencyMs = chunk.latencyMs.coerceAtLeast(1L)
            if (chunk.delta.isNotEmpty()) {
                if (firstTokenMs < 0L) {
                    firstTokenMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                }
                chunkCount++
                builder.append(chunk.delta)
                onPreview(builder.toString())
            }
        }

        val rawText = builder.toString()
        val merged = analyzer.mergeModelJsonOrNull(rawText, content, nowMillis)
            ?: error("Cloud SSE output did not contain valid analysis JSON")

        val totalMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(lastLatencyMs)
        val estimatedTokens = (rawText.length / 2).coerceAtLeast(chunkCount).coerceAtLeast(1)
        val tokPerSec = estimatedTokens.toDouble() * 1000.0 / totalMs.toDouble()
        val ttft = if (firstTokenMs >= 0L) firstTokenMs else totalMs
        val badge = String.format(
            Locale.US,
            "Cloud SSE · TTFT %dms · %.1f tok/s",
            ttft,
            tokPerSec,
        )

        DistillationEvent.Completed(
            payload = merged,
            source = AnalysisSource.CLOUD_SSE,
            telemetryBadge = badge,
        )
    }.getOrNull()

    private suspend fun tryEdgeStream(
        content: String,
        nowMillis: Long,
        degradedFromCloud: Boolean,
        onPreview: suspend (String, String) -> Unit,
    ): DistillationEvent.Completed? = runCatching {
        val prompt = analyzer.buildAnalysisPrompt(content, nowMillis)
        val stageLabel = if (degradedFromCloud) {
            "云端降级 · 端侧 MNN 推理中…"
        } else {
            "端侧 MNN 流式提炼中…"
        }

        val builder = StringBuilder()
        var finalText = ""
        var finalTelemetry: InferenceTelemetry? = null

        edgeEngine.streamGenerate(prompt, maxTokens = 320).collect { chunk ->
            when (chunk) {
                is StreamChunk.Delta -> {
                    builder.append(chunk.text)
                    onPreview(builder.toString(), stageLabel)
                }
                is StreamChunk.Completed -> {
                    finalText = chunk.fullText.ifEmpty { builder.toString() }
                    finalTelemetry = chunk.telemetry
                }
            }
        }

        val telemetry = finalTelemetry?.takeIf { it.isRealMnn } ?: return null
        val merged = analyzer.mergeModelJsonOrNull(finalText, content, nowMillis) ?: return null

        val prefix = if (degradedFromCloud) "云端降级 · MNN" else "MNN 0.5B"
        val badge = String.format(
            Locale.US,
            "%s · TTFT %dms · %.1f tok/s",
            prefix,
            telemetry.ttftMs,
            telemetry.tokensPerSec,
        )

        DistillationEvent.Completed(
            payload = merged,
            source = AnalysisSource.MNN_EDGE,
            telemetryBadge = badge,
        )
    }.getOrNull()
}
