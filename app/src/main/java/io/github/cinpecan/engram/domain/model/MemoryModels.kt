package io.github.cinpecan.engram.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class CalendarProposal(
    val title: String,
    val startTimeIso: String,
    val durationMinutes: Int = 60,
    val location: String? = null,
)

@Immutable
@Serializable
data class RelationTriple(
    val id: String = "",
    val memoryId: String = "",
    val source: String,
    val target: String,
    val relation: String,
    val bidirectional: Boolean = false,
)

@Immutable
@Serializable
data class MemoryAnalysisPayload(
    val summary: String,
    val tags: List<String>,
    val calendarProposal: CalendarProposal? = null,
    val relations: List<RelationTriple> = emptyList(),
)

enum class MemoryAnalysisStatus {
    PENDING,
    READY,
}

enum class AnalysisSource {
    MNN_EDGE,
    CLOUD_SSE,
    LOCAL_RULE_FALLBACK,
    SEED,
}

@Immutable
data class StreamingPreviewState(
    val partialText: String,
    val stageLabel: String,
)

@Immutable
data class MemoryItem(
    val id: String,
    val content: String,
    val summary: String,
    val tags: List<String>,
    val createdAtMillis: Long,
    val analysisStatus: MemoryAnalysisStatus,
    val calendarProposal: CalendarProposal?,
    val calendarSaved: Boolean,
    val calendarEventId: Long? = null,
    val relations: List<RelationTriple>,
    val analysisSource: AnalysisSource = AnalysisSource.LOCAL_RULE_FALLBACK,
    val telemetryBadge: String = "",
    val updatedAtMillis: Long = createdAtMillis,
)

enum class InferenceMode {
    OFFLINE_EDGE,
    CLOUD_COLLAB,
}
