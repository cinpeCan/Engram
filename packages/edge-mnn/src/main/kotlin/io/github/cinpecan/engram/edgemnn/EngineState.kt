package io.github.cinpecan.engram.edgemnn

sealed interface EngineState {
    data object Unloaded : EngineState

    data class Loading(val modelId: String) : EngineState

    data class Ready(
        val modelId: String,
        val backendName: String,
        val residentMemoryBytes: Long,
        val modelFootprintBytes: Long = 0L,
    ) : EngineState

    data class Generating(
        val modelId: String,
        val backendName: String,
    ) : EngineState

    data class Releasing(val reason: UnloadReason) : EngineState

    data class Error(val message: String) : EngineState
}
