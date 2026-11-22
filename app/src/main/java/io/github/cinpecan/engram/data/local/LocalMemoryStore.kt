package io.github.cinpecan.engram.data.local

import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.rag.QueryTokenizer
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

    suspend fun deleteMemory(memoryId: String, deletedAtMillis: Long = System.currentTimeMillis()) = Unit
    suspend fun getDeletedTombstones(): Map<String, Long> = emptyMap()

    /**
     * Lexical candidate ids for [question]. Room uses FTS4 + porter; this default is a naive
     * stemmed-overlap scan used by in-memory test fakes.
     */
    suspend fun searchLexicalIds(question: String, limit: Int = 24): List<String> {
        val queryStems = QueryTokenizer.stemmedTokens(question)
        if (queryStems.isEmpty()) return emptyList()
        return getAllMemories()
            .filter { memory ->
                val doc = QueryTokenizer.stemmedTokens("${memory.content} ${memory.summary}")
                queryStems.any { it in doc }
            }
            .sortedByDescending { it.createdAtMillis }
            .take(limit)
            .map { it.id }
    }
}
