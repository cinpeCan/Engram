package io.github.cinpecan.engram.data.remote

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class ModelFileDto(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val fallbackUrl: String? = null,
)

@Immutable
@Serializable
data class ModelDescriptorDto(
    val id: String,
    val name: String,
    val role: String,
    val quantization: String,
    val sizeBytes: Long,
    val minRamMb: Int,
    val downloadUrl: String,
    val mirrorUrl: String,
    val sha256: String? = null,
    val description: String? = null,
    val files: List<ModelFileDto>,
)

@Serializable
data class AnalysisStreamRequestDto(
    val content: String,
    val nowIso: String,
    val model: String = "gemini-3.5-flash-lite",
)

/** 1:1 with `contracts/analysis-sse.schema.json#/$defs/AnswerContext`. */
@Serializable
data class AnswerContextDto(
    val id: String,
    val text: String,
    val createdAtMillis: Long,
)

/** 1:1 with `contracts/analysis-sse.schema.json#/$defs/AnswerStreamRequest`. */
@Serializable
data class AnswerStreamRequestDto(
    val question: String,
    val nowIso: String,
    val contexts: List<AnswerContextDto>,
    val model: String = "gemini-3.5-flash-lite",
)

@Serializable
data class AnalysisStreamChunkDto(
    val delta: String,
    val done: Boolean,
    val model: String,
    val latencyMs: Long,
    val error: String? = null,
)

@Serializable
data class SyncCalendarProposalDto(
    val title: String,
    val startTimeIso: String,
    val durationMinutes: Int = 60,
    val location: String? = null,
)

@Serializable
data class SyncRelationTripleDto(
    val source: String,
    val target: String,
    val relation: String,
    val bidirectional: Boolean = false,
)

@Serializable
data class SyncMemoryItemDto(
    val id: String,
    val content: String,
    val summary: String,
    val tags: List<String>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val analysisStatus: String,
    val calendarProposal: SyncCalendarProposalDto? = null,
    val calendarSaved: Boolean = false,
    val analysisSource: String,
    val telemetryBadge: String = "",
    val relations: List<SyncRelationTripleDto> = emptyList(),
)

@Serializable
data class MemorySyncRequestDto(
    val syncKey: String,
    val memories: List<SyncMemoryItemDto>,
)

@Serializable
data class MemorySyncResponseDto(
    val syncKey: String,
    val upsertedCount: Int,
    val totalCount: Int,
    val serverTimeMillis: Long,
    val memories: List<SyncMemoryItemDto>,
)

@Serializable
data class AuthStatusResponseDto(
    val status: String = "ok",
    val mode: String = "open",
    val remainingQuota: Int? = null,
)

@Serializable
data class MemoryDeleteRequestDto(
    val syncKey: String,
    val deletedIds: List<String>,
)

@Serializable
data class MemoryDeleteResponseDto(
    val deletedCount: Int = 0,
)
