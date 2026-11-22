package io.github.cinpecan.engram.data.repository

import androidx.compose.runtime.Immutable
import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.AnswerContextDto
import io.github.cinpecan.engram.data.remote.AnswerStreamRequestDto
import io.github.cinpecan.engram.data.remote.CloudGatewayHttpException
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.domain.analyzer.EdgePromptTemplates
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.domain.rag.Citation
import io.github.cinpecan.engram.domain.rag.GraphRagRetriever
import io.github.cinpecan.engram.domain.rag.RetrievalResult
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.edgemnn.InferenceTelemetry
import io.github.cinpecan.engram.edgemnn.StreamChunk
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

enum class AnswerSource { CLOUD, EDGE }

sealed interface AnswerEvent {
    /** Emitted first, before any generation, so citations and the reasoning graph render instantly. */
    @Immutable
    data class Retrieved(val result: RetrievalResult) : AnswerEvent

    @Immutable
    data class Streaming(val textSoFar: String, val stage: String) : AnswerEvent

    @Immutable
    data class Completed(val text: String, val source: AnswerSource, val badge: String) : AnswerEvent

    /** No generator available (or nothing matched): show retrieved memories only, never a fabricated answer. */
    @Immutable
    data class RetrievalOnly(val reason: String) : AnswerEvent
}

/**
 * "Ask" pipeline: on-device GraphRAG retrieval (FTS4 porter + 2-hop entity graph + RRF), then a
 * cited answer streamed from the cloud gateway, falling back to on-device MNN, and finally to an
 * honest retrieval-only result.
 */
class AnswerRepository(
    private val store: LocalMemoryStore,
    private val edgeEngine: EdgeAiEngine,
    private val cloudSseClient: CloudSseClient,
    private val preferencesRepository: EnginePreferencesRepository? = null,
    private val cloudSessionMonitor: CloudSessionMonitor? = null,
    private val retriever: GraphRagRetriever = GraphRagRetriever(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val analyzer: MemoryAnalyzer = MemoryAnalyzer(zoneId = zoneId),
) {

    fun ask(question: String): Flow<AnswerEvent> = flow {
        val trimmed = question.trim()
        val memories = store.getAllMemories()
        val lexicalHits = store.searchLexicalIds(trimmed)
        val retrieval = retriever.retrieve(trimmed, memories, lexicalHits)
        emit(AnswerEvent.Retrieved(retrieval))

        if (retrieval.isEmpty) {
            emit(AnswerEvent.RetrievalOnly(REASON_NO_MATCH))
            return@flow
        }

        val prefs = preferencesRepository?.preferencesFlow?.first()
        val mode = prefs?.inferenceMode ?: InferenceMode.OFFLINE_EDGE

        var cloudFailure: Throwable? = null
        if (mode == InferenceMode.CLOUD_COLLAB) {
            val monitorState = cloudSessionMonitor?.state?.value
            val passesFilter = prefs != null &&
                CloudSessionMonitor.passesLengthFilter(prefs.serverBaseUrl, prefs.syncKey)
            val preKnownFailure: Throwable? = when {
                cloudSessionMonitor == null -> null
                monitorState is CloudSessionState.InvalidToken ->
                    CloudGatewayHttpException(401)
                monitorState is CloudSessionState.QuotaExhausted ->
                    CloudGatewayHttpException(429)
                monitorState is CloudSessionState.IdleUnconfigured && !passesFilter ->
                    CloudGatewayHttpException(401)
                else -> null
            }
            if (preKnownFailure != null) {
                cloudFailure = preKnownFailure
                emit(AnswerEvent.Streaming(textSoFar = "", stage = STAGE_EDGE))
            } else {
                try {
                    val completed = streamFromCloud(
                        question = trimmed,
                        citations = retrieval.citations,
                        serverBaseUrl = prefs?.serverBaseUrl.orEmpty(),
                        authToken = prefs?.syncKey,
                    )
                    cloudSessionMonitor?.retryVerification()
                    emit(completed)
                    return@flow
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    cloudFailure = t
                    if (t is CloudGatewayHttpException && (t.statusCode == 401 || t.statusCode == 429)) {
                        cloudSessionMonitor?.retryVerification()
                    }
                    emit(AnswerEvent.Streaming(textSoFar = "", stage = STAGE_EDGE))
                }
            }
        }

        if (edgeEngine.state.value is EngineState.Ready || edgeEngine.isBackendAvailable) {
            val edgeCompleted = streamFromEdge(trimmed, retrieval.citations, degraded = cloudFailure != null)
            if (edgeCompleted != null) {
                emit(edgeCompleted)
                return@flow
            }
        }

        emit(AnswerEvent.RetrievalOnly(retrievalOnlyReason(cloudFailure, mode)))
    }

    private suspend fun FlowCollector<AnswerEvent>.streamFromCloud(
        question: String,
        citations: List<Citation>,
        serverBaseUrl: String,
        authToken: String?,
    ): AnswerEvent.Completed {
        val request = AnswerStreamRequestDto(
            question = question.take(MAX_QUESTION_CHARS),
            nowIso = nowIsoWithOffset(),
            contexts = citations.take(MAX_CONTEXTS).map { citation ->
                AnswerContextDto(
                    id = citation.memoryId,
                    text = citation.contextText
                        .ifBlank { citation.headline }
                        .take(GraphRagRetriever.MAX_CONTEXT_CHARS)
                        .ifBlank { "-" },
                    createdAtMillis = citation.createdAtMillis,
                )
            },
        )
        val startNs = System.nanoTime()
        var firstTokenMs = -1L
        var lastLatencyMs = 1L
        var chunkCount = 0
        var modelId = request.model
        val builder = StringBuilder()
        emit(AnswerEvent.Streaming(textSoFar = "", stage = STAGE_CLOUD))
        cloudSseClient.streamAnswer(serverBaseUrl, request, authToken = authToken).collect { chunk ->
            lastLatencyMs = chunk.latencyMs.coerceAtLeast(1L)
            if (chunk.model.isNotBlank()) modelId = chunk.model
            if (chunk.delta.isNotEmpty()) {
                if (firstTokenMs < 0L) {
                    firstTokenMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                }
                chunkCount++
                builder.append(chunk.delta)
                emit(AnswerEvent.Streaming(textSoFar = analyzer.redactSensitiveDetails(builder.toString()), stage = STAGE_CLOUD))
            }
        }
        val text = analyzer.redactSensitiveDetails(builder.toString().trim())
        if (text.isEmpty()) error("Empty cloud answer")
        val totalMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(lastLatencyMs)
        val estimatedTokens = (text.length / 2).coerceAtLeast(chunkCount).coerceAtLeast(1)
        val tokPerSec = estimatedTokens.toDouble() * 1000.0 / totalMs.toDouble()
        val ttft = if (firstTokenMs >= 0L) firstTokenMs else totalMs
        val badge = String.format(
            Locale.US,
            "%s · TTFT %dms · %.1f tok/s",
            UnifiedInferenceRouter.humanizeModelName(modelId),
            ttft,
            tokPerSec,
        )
        return AnswerEvent.Completed(text = text, source = AnswerSource.CLOUD, badge = badge)
    }

    private suspend fun FlowCollector<AnswerEvent>.streamFromEdge(
        question: String,
        citations: List<Citation>,
        degraded: Boolean,
    ): AnswerEvent.Completed? = try {
        val nowIso = nowIsoWithOffset()
        val combinedUserPrompt = if (EdgePromptTemplates.isChineseIntent(question)) {
            buildCjkEdgePrompt(question, citations, nowIso)
        } else {
            "${buildEdgeSystemPrompt(nowIso)}\n\n${buildEdgeUserPrompt(question, citations)}"
        }
        val builder = StringBuilder()
        var finalText = ""
        var telemetry: InferenceTelemetry? = null
        emit(AnswerEvent.Streaming(textSoFar = "", stage = STAGE_EDGE))
        edgeEngine.streamGenerate(
            systemPrompt = "",
            userPrompt = combinedUserPrompt,
            maxTokens = EDGE_MAX_TOKENS,
        ).collect { chunk ->
            when (chunk) {
                is StreamChunk.Delta -> {
                    builder.append(chunk.text)
                    emit(
                        AnswerEvent.Streaming(
                            textSoFar = analyzer.redactSensitiveDetails(builder.toString()),
                            stage = STAGE_EDGE,
                        ),
                    )
                }
                is StreamChunk.Completed -> {
                    finalText = chunk.fullText.ifEmpty { builder.toString() }
                    telemetry = chunk.telemetry
                }
            }
        }
        val realTelemetry = telemetry?.takeIf { it.isRealMnn }
        val text = analyzer.redactSensitiveDetails(finalText.trim())
        if (realTelemetry == null || text.isEmpty()) {
            null
        } else {
            val label = UnifiedInferenceRouter.humanizeEdgeModelName(realTelemetry.modelId)
            val badge = if (degraded) {
                String.format(
                    Locale.US,
                    "%s · %s · TTFT %dms · %.1f tok/s",
                    UnifiedInferenceRouter.BADGE_CLOUD_FALLBACK_PREFIX,
                    label,
                    realTelemetry.ttftMs,
                    realTelemetry.tokensPerSec,
                )
            } else {
                String.format(
                    Locale.US,
                    "%s · MNN · TTFT %dms · %.1f tok/s",
                    label,
                    realTelemetry.ttftMs,
                    realTelemetry.tokensPerSec,
                )
            }
            AnswerEvent.Completed(text = text, source = AnswerSource.EDGE, badge = badge)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private fun nowIsoWithOffset(): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(nowMillis()), zoneId)
            .truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun buildEdgeSystemPrompt(nowIso: String): String = """
        You are Engram, a private memory assistant. Current local time: $nowIso.
        Answer the user's question using ONLY the numbered memory snippets provided.
        Always cite snippet numbers inline at the end of sentences, e.g. [1] or [2].
        If the snippets do not contain the answer, say so briefly and do not invent facts.
        Be concise: at most 4 sentences. Never reveal passwords or secrets.
        Answer in the same language as the question.
    """.trimIndent()

    private fun buildEdgeUserPrompt(question: String, citations: List<Citation>): String {
        val snippets = formatSnippets(citations)
        return """
            Memory snippets:
            $snippets

            Question: $question
        """.trimIndent()
    }

    private fun buildCjkEdgePrompt(
        question: String,
        citations: List<Citation>,
        nowIso: String,
    ): String {
        val snippets = formatSnippets(citations)
        return "\u4F60\u662F\u5370\u8FF9\uFF08Engram\uFF09\u79C1\u5BC6\u8BB0\u5FC6\u52A9\u624B\u3002\u5F53\u524D\u65F6\u95F4\uFF1A$nowIso\u3002\n" +
            "\u8BF7\u4EC5\u6839\u636E\u4E0B\u65B9\u7F16\u53F7\u8BB0\u5FC6\u7247\u6BB5\u7528\u4E2D\u6587\u7B80\u6D01\u4F5C\u7B54\uFF0C\u5728\u53E5\u672B\u7528 [1]\u3001[2] \u6807\u6CE8\u5F15\u7528\u7F16\u53F7\uFF1B\u82E5\u7247\u6BB5\u672A\u63D0\u53CA\u8BF7\u76F4\u63A5\u8BF4\u660E\uFF0C\u4E25\u7981\u7F16\u9020\u4E8B\u5B9E\u6216\u6CC4\u9732\u660E\u6587\u5BC6\u7801\uFF08\u4E0D\u8D85\u8FC73\u53E5\u8BDD\uFF09\u3002\n\n" +
            "\u8BB0\u5FC6\u7247\u6BB5\uFF1A\n$snippets\n\n\u95EE\u9898\uFF1A$question"
    }

    private fun formatSnippets(citations: List<Citation>): String {
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        return citations.joinToString("\n") { citation ->
            val date = Instant.ofEpochMilli(citation.createdAtMillis).atZone(zoneId).toLocalDate().format(dateFormatter)
            val text = citation.contextText.ifBlank { citation.headline }
                .replace(Regex("\\s+"), " ")
                .take(EDGE_SNIPPET_CHARS)
            "[${citation.index}] ($date) $text"
        }
    }

    private fun retrievalOnlyReason(cloudFailure: Throwable?, mode: InferenceMode): String =
        when {
            cloudFailure is CloudGatewayHttpException && cloudFailure.statusCode == 401 -> REASON_UNAUTHORIZED
            cloudFailure is CloudGatewayHttpException && cloudFailure.statusCode == 429 -> REASON_QUOTA
            cloudFailure != null -> REASON_CLOUD_OFFLINE
            mode == InferenceMode.CLOUD_COLLAB -> REASON_CLOUD_OFFLINE
            else -> REASON_NO_MODEL
        }

    companion object {
        const val STAGE_CLOUD = "Answering from cloud…"
        const val STAGE_EDGE = "Answering on-device…"

        const val REASON_NO_MATCH = "Nothing in your memories matches this yet."
        const val REASON_NO_MODEL = "No model loaded · Showing matched memories"
        const val REASON_UNAUTHORIZED = "Access token required · Showing matched memories"
        const val REASON_QUOTA = "Cloud quota reached · Showing matched memories"
        const val REASON_CLOUD_OFFLINE = "Cloud offline · Showing matched memories"

        private const val MAX_QUESTION_CHARS = 500
        private const val MAX_CONTEXTS = 8
        private const val EDGE_MAX_TOKENS = 256
        private const val EDGE_SNIPPET_CHARS = 360
    }
}
