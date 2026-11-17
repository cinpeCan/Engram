package io.github.cinpecan.engram.edgemnn

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface EdgeAiEngine {
    val state: StateFlow<EngineState>
    val lastTelemetry: StateFlow<InferenceTelemetry?>
    val isBackendAvailable: Boolean

    fun configureDefaultModel(modelId: String, modelDirPath: String) = Unit
    suspend fun loadModel(modelId: String, modelDirPath: String): Result<Unit>
    fun streamGenerate(prompt: String, maxTokens: Int = 512): Flow<StreamChunk>
    suspend fun unload(reason: UnloadReason = UnloadReason.USER_REQUESTED): Long
    fun cancelActiveGeneration()
    fun onTrimMemory(level: Int)
    fun currentMemoryBytes(): Long
}
