package io.github.cinpecan.engram.edgemnn

sealed interface StreamChunk {
    data class Delta(val text: String) : StreamChunk
    data class Completed(val fullText: String, val telemetry: InferenceTelemetry) : StreamChunk
}
