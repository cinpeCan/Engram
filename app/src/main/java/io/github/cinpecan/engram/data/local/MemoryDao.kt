package io.github.cinpecan.engram.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Transaction
    @Query("SELECT * FROM memories ORDER BY created_at DESC")
    fun observeMemories(): Flow<List<MemoryWithRelations>>

    @Transaction
    @Query("SELECT * FROM memories WHERE id = :memoryId LIMIT 1")
    fun observeMemoryById(memoryId: String): Flow<MemoryWithRelations?>

    @Transaction
    @Query(
        """
        SELECT DISTINCT m.* FROM memories m
        LEFT JOIN memory_relations r ON m.id = r.memory_id
        WHERE INSTR(LOWER(m.content), LOWER(:entityName)) > 0
           OR r.source = :entityName
           OR r.target = :entityName
        ORDER BY m.created_at DESC
        """,
    )
    fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryWithRelations>>

    @Transaction
    @Query("SELECT * FROM memories ORDER BY created_at DESC")
    suspend fun getAllMemories(): List<MemoryWithRelations>

    @Query("SELECT content FROM memories WHERE id = :memoryId LIMIT 1")
    suspend fun getMemoryContent(memoryId: String): String?

    @Upsert
    suspend fun upsertMemory(entity: MemoryEntity)

    @Query(
        """
        UPDATE memories
        SET summary = :summary,
            tags_json = :tagsJson,
            analysis_status = :status,
            calendar_json = :calendarJson
        WHERE id = :memoryId
        """,
    )
    suspend fun updateAnalysisFields(
        memoryId: String,
        summary: String,
        tagsJson: String,
        status: String,
        calendarJson: String?,
    )

    @Query("UPDATE memories SET calendar_saved = 1 WHERE id = :memoryId")
    suspend fun markCalendarSaved(memoryId: String)

    @Query("DELETE FROM memory_relations WHERE memory_id = :memoryId")
    suspend fun deleteRelationsForMemory(memoryId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelations(relations: List<MemoryRelationEntity>)

    @Query("DELETE FROM memories_fts WHERE memory_id = :memoryId")
    suspend fun deleteFtsForMemory(memoryId: String)

    @Insert
    suspend fun insertFts(fts: MemoryFtsEntity)

    @Upsert
    suspend fun upsertEmbedding(embedding: MemoryEmbeddingEntity)

    @Query("SELECT memory_id, bigram_tokens FROM memories_fts WHERE bigram_tokens MATCH :matchExpr")
    suspend fun queryFtsHits(matchExpr: String): List<FtsHitProjection>

    @Query("SELECT * FROM memory_embeddings")
    suspend fun getAllEmbeddings(): List<MemoryEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun countMemories(): Int
}
