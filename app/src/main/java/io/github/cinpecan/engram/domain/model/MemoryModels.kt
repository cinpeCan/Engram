package io.github.cinpecan.engram.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CalendarProposal(
    val title: String,
    val startTimeIso: String,
    val durationMinutes: Int = 60,
    val location: String? = null,
)

@Serializable
data class RelationTriple(
    val id: String = "",
    val memoryId: String = "",
    val source: String,
    val target: String,
    val relation: String,
    val bidirectional: Boolean = false,
)

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

data class MemoryItem(
    val id: String,
    val content: String,
    val summary: String,
    val tags: List<String>,
    val createdAtMillis: Long,
    val analysisStatus: MemoryAnalysisStatus,
    val calendarProposal: CalendarProposal?,
    val calendarSaved: Boolean,
    val relations: List<RelationTriple>,
)

enum class InferenceMode {
    OFFLINE_EDGE,
    CLOUD_COLLAB,
}
