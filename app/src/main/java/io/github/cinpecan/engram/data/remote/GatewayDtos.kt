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
data class ModelManifestResponseDto(
    val schemaVersion: String,
    val models: List<ModelDescriptorDto>,
)

@Serializable
data class AnalysisStreamRequestDto(
    val content: String,
    val nowIso: String,
    val model: String = "gpt-4o-mini",
)

@Serializable
data class AnalysisStreamChunkDto(
    val delta: String,
    val done: Boolean,
    val model: String,
    val latencyMs: Long,
    val error: String? = null,
)
