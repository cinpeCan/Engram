package io.github.cinpecan.engram.data.repository

import io.github.cinpecan.engram.data.remote.AnalysisStreamRequestDto
import io.github.cinpecan.engram.data.remote.CloudGatewayHttpException
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.analyzer.PartialJsonSummaryExtractor
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
import kotlinx.coroutines.CancellationException
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
    private sealed interface CloudAttemptResult {
        data class Success(val event: DistillationEvent.Completed) : CloudAttemptResult
        data class Failure(val error: Throwable) : CloudAttemptResult
    }

    fun distillMemory(
        content: String,
        nowMillis: Long,
        mode: InferenceMode,
        serverBaseUrl: String,
        authToken: String? = null,
    ): Flow<DistillationEvent> = flow {
        var cloudFailure: Throwable? = null

        if (mode == InferenceMode.CLOUD_COLLAB) {
            when (
                val cloudResult = tryCloudStream(
                    content = content,
                    nowMillis = nowMillis,
                    serverBaseUrl = serverBaseUrl,
                    authToken = authToken,
                    onPreview = { preview -> emit(preview) },
                )
            ) {
                is CloudAttemptResult.Success -> {
                    emit(cloudResult.event)
                    return@flow
                }
                is CloudAttemptResult.Failure -> {
                    cloudFailure = cloudResult.error
                }
            }
        }

        val degradedFromCloud = cloudFailure != null
        if (edgeEngine.state.value is EngineState.Ready || edgeEngine.isBackendAvailable) {
            val edgeCompleted = tryEdgeStream(
                content = content,
                nowMillis = nowMillis,
                degradedFromCloud = degradedFromCloud,
                onPreview = { preview -> emit(preview) },
            )
            if (edgeCompleted != null) {
                emit(edgeCompleted)
                return@flow
            }
        }

        val fallbackPayload = analyzer.fallbackAnalyze(content, nowMillis)
        val fallbackBadge = when (val failure = cloudFailure) {
            null -> ""
            is CloudGatewayHttpException -> when (failure.statusCode) {
                401 -> BADGE_FALLBACK_UNAUTHORIZED
                429 -> BADGE_FALLBACK_QUOTA
                else -> BADGE_FALLBACK_OFFLINE
            }
            else -> BADGE_FALLBACK_OFFLINE
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
        authToken: String?,
        onPreview: suspend (DistillationEvent.StreamingPreview) -> Unit,
    ): CloudAttemptResult = try {
        val nowIso = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val request = AnalysisStreamRequestDto(
            content = content,
            nowIso = nowIso,
        )
        val startNs = System.nanoTime()
        var firstTokenMs = -1L
        var lastLatencyMs = 1L
        val builder = StringBuilder()
        var chunkCount = 0
        var modelId = request.model
        val previewer = SummaryPreviewTracker(STAGE_CLOUD_STREAMING)

        cloudSseClient.streamAnalyze(serverBaseUrl, request, authToken = authToken).collect { chunk ->
            lastLatencyMs = chunk.latencyMs.coerceAtLeast(1L)
            if (chunk.model.isNotBlank()) {
                modelId = chunk.model
            }
            if (chunk.delta.isNotEmpty()) {
                if (firstTokenMs < 0L) {
                    firstTokenMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                }
                chunkCount++
                builder.append(chunk.delta)
                previewer.next(builder)?.let { onPreview(it) }
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
            "%s · TTFT %dms · %.1f tok/s",
            humanizeModelName(modelId),
            ttft,
            tokPerSec,
        )

        CloudAttemptResult.Success(
            DistillationEvent.Completed(
                payload = merged,
                source = AnalysisSource.CLOUD_SSE,
                telemetryBadge = badge,
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        CloudAttemptResult.Failure(t)
    }

    private suspend fun tryEdgeStream(
        content: String,
        nowMillis: Long,
        degradedFromCloud: Boolean,
        onPreview: suspend (DistillationEvent.StreamingPreview) -> Unit,
    ): DistillationEvent.Completed? = try {
        val prompt = analyzer.buildAnalysisPrompt(content, nowMillis)
        val previewer = SummaryPreviewTracker(STAGE_EDGE_STREAMING)

        val builder = StringBuilder()
        var finalText = ""
        var finalTelemetry: InferenceTelemetry? = null

        edgeEngine.streamGenerate(prompt, maxTokens = 320).collect { chunk ->
            when (chunk) {
                is StreamChunk.Delta -> {
                    builder.append(chunk.text)
                    previewer.next(builder)?.let { onPreview(it) }
                }
                is StreamChunk.Completed -> {
                    finalText = chunk.fullText.ifEmpty { builder.toString() }
                    finalTelemetry = chunk.telemetry
                }
            }
        }

        val telemetry = finalTelemetry?.takeIf { it.isRealMnn } ?: return null
        val merged = analyzer.mergeModelJsonOrNull(finalText, content, nowMillis) ?: return null

        val modelLabel = humanizeEdgeModelName(telemetry.modelId)
        val badge = if (degradedFromCloud) {
            String.format(
                Locale.US,
                "%s · %s · TTFT %dms · %.1f tok/s",
                BADGE_CLOUD_FALLBACK_PREFIX,
                modelLabel,
                telemetry.ttftMs,
                telemetry.tokensPerSec,
            )
        } else {
            String.format(
                Locale.US,
                "%s · MNN · TTFT %dms · %.1f tok/s",
                modelLabel,
                telemetry.ttftMs,
                telemetry.tokensPerSec,
            )
        }

        DistillationEvent.Completed(
            payload = merged,
            source = AnalysisSource.MNN_EDGE,
            telemetryBadge = badge,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    /**
     * Converts the raw JSON stream into a summary-only typewriter preview, emitting only
     * when the visible text or stage changes. Never leaks raw JSON into the UI.
     */
    private class SummaryPreviewTracker(private val streamingStage: String) {
        private var lastText: String? = null
        private var lastStage: String? = null

        fun next(rawSoFar: CharSequence): DistillationEvent.StreamingPreview? {
            val partial = PartialJsonSummaryExtractor.extract(rawSoFar.toString())
            val text = partial?.text.orEmpty()
            val stage = if (partial?.isClosed == true) STAGE_MAPPING_RELATIONS else streamingStage
            if (text == lastText && stage == lastStage) return null
            lastText = text
            lastStage = stage
            return DistillationEvent.StreamingPreview(partialText = text, stageLabel = stage)
        }
    }

    companion object {
        const val STAGE_PREPARING = "Preparing…"
        const val STAGE_CLOUD_STREAMING = "Streaming from cloud…"
        const val STAGE_EDGE_STREAMING = "Distilling on-device…"
        const val STAGE_MAPPING_RELATIONS = "Mapping relations…"

        const val BADGE_FALLBACK_UNAUTHORIZED = "Access token required · Local extraction"
        const val BADGE_FALLBACK_QUOTA = "Cloud quota reached · Local extraction"
        const val BADGE_FALLBACK_OFFLINE = "Cloud offline · Local extraction"
        const val BADGE_CLOUD_FALLBACK_PREFIX = "Cloud fallback"

        /** Maps a gateway model id to a readable label, e.g. `gemini-3.5-flash-lite` -> `Gemini 3.5 Flash-Lite`. */
        fun humanizeModelName(modelId: String): String {
            val trimmed = modelId.trim()
            if (!trimmed.startsWith("gemini-", ignoreCase = true)) return trimmed.ifEmpty { "Cloud" }
            return trimmed.lowercase(Locale.US)
                .split('-')
                .filter { it.isNotEmpty() }
                .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase(Locale.US) } }
                .replace("Flash Lite", "Flash-Lite")
        }

        /** Maps an on-device model id to a readable label, e.g. `qwen2.5-0.5b-instruct-mnn` -> `Qwen2.5 0.5B`. */
        fun humanizeEdgeModelName(modelId: String): String {
            val lower = modelId.lowercase(Locale.US)
            return when {
                lower.contains("qwen2.5-0.5b") -> "Qwen2.5 0.5B"
                lower.contains("qwen2.5-1.5b") -> "Qwen2.5 1.5B"
                modelId.isBlank() -> "On-device model"
                else -> modelId
            }
        }
    }
}
