package io.github.cinpecan.engram.edgemnn

data class InferenceTelemetry(
    val engineLabel: String,
    val modelId: String,
    val ttftMs: Long,
    val totalMs: Long,
    val generatedTokens: Int,
    val tokensPerSec: Double,
    val residentMemoryMb: Double,
    val isRealMnn: Boolean,
)
