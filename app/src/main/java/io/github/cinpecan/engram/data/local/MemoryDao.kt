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

    @Query("SELECT * FROM memories WHERE id = :memoryId LIMIT 1")
    suspend fun getMemoryEntity(memoryId: String): MemoryEntity?

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
            calendar_json = :calendarJson,
            analysis_source = :analysisSource,
            telemetry_badge = :telemetryBadge,
            updated_at = :updatedAtMillis
        WHERE id = :memoryId
        """,
    )
    suspend fun updateAnalysisFields(
        memoryId: String,
        summary: String,
        tagsJson: String,
        status: String,
        calendarJson: String?,
        analysisSource: String,
        telemetryBadge: String,
        updatedAtMillis: Long,
    )

    @Query("UPDATE memories SET calendar_saved = :saved, updated_at = :updatedAtMillis WHERE id = :memoryId")
    suspend fun setCalendarSaved(memoryId: String, saved: Boolean, updatedAtMillis: Long)

    @Query("DELETE FROM memory_relations WHERE memory_id = :memoryId")
    suspend fun deleteRelationsForMemory(memoryId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelations(relations: List<MemoryRelationEntity>)

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun countMemories(): Int

    @Query("DELETE FROM memories WHERE id = :memoryId")
    suspend fun deleteMemoryById(memoryId: String)

    @Upsert
    suspend fun upsertDeletedTombstone(entity: DeletedMemoryEntity)

    @Query("DELETE FROM deleted_memories WHERE id = :memoryId")
    suspend fun clearDeletedTombstone(memoryId: String)

    @Query("SELECT * FROM deleted_memories")
    suspend fun getAllDeletedTombstones(): List<DeletedMemoryEntity>
}
