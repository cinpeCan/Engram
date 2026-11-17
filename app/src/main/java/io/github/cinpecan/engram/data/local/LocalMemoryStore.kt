package io.github.cinpecan.engram.data.local

import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryItem
import kotlinx.coroutines.flow.StateFlow

interface LocalMemoryStore {
    val memoriesFlow: StateFlow<List<MemoryItem>>

    suspend fun refresh()
    suspend fun insertRawMemory(item: MemoryItem)
    suspend fun updateMemoryAnalysis(
        memoryId: String,
        payload: MemoryAnalysisPayload,
        embedding: FloatArray?,
    )
    suspend fun markCalendarSaved(memoryId: String)
    suspend fun getAllMemories(): List<MemoryItem>
    suspend fun queryFtsLexicalIds(query: String): List<String>
    suspend fun loadAllEmbeddings(): Map<String, FloatArray>
    suspend fun countMemories(): Int
}
