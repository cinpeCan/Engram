package io.github.cinpecan.engram.data.local

import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryItem
import kotlinx.coroutines.flow.Flow

interface LocalMemoryStore {
    fun observeMemories(): Flow<List<MemoryItem>>
    fun observeMemoryById(memoryId: String): Flow<MemoryItem?>
    fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>>

    suspend fun insertRawMemory(item: MemoryItem)
    suspend fun updateMemoryAnalysis(
        memoryId: String,
        payload: MemoryAnalysisPayload,
        analysisSource: AnalysisSource,
        telemetryBadge: String,
    )
    suspend fun setCalendarSaved(memoryId: String, saved: Boolean)
    suspend fun mergeRemoteMemories(remoteItems: List<MemoryItem>): Int
    suspend fun getAllMemories(): List<MemoryItem>
    suspend fun countMemories(): Int
}
