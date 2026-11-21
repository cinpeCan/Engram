package io.github.cinpecan.engram.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "memories",
    indices = [Index(value = ["created_at"])],
)
data class MemoryEntity(
    @PrimaryKey
    val id: String,
    val content: String,
    val summary: String,
    @ColumnInfo(name = "tags_json")
    val tagsJson: String,
    @ColumnInfo(name = "created_at")
    val createdAtMillis: Long,
    @ColumnInfo(name = "analysis_status")
    val analysisStatus: String,
    @ColumnInfo(name = "calendar_json")
    val calendarJson: String?,
    @ColumnInfo(name = "calendar_saved")
    val calendarSaved: Boolean,
    @ColumnInfo(name = "analysis_source")
    val analysisSource: String = "LOCAL_RULE_FALLBACK",
    @ColumnInfo(name = "telemetry_badge")
    val telemetryBadge: String = "",
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAtMillis: Long = createdAtMillis,
)

@Entity(
    tableName = "memory_relations",
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memory_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["memory_id"]),
        Index(value = ["source"]),
        Index(value = ["target"]),
    ],
)
data class MemoryRelationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "memory_id")
    val memoryId: String,
    val source: String,
    val target: String,
    val relation: String,
    val bidirectional: Boolean,
)

data class MemoryWithRelations(
    @Embedded
    val memory: MemoryEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "memory_id",
    )
    val relations: List<MemoryRelationEntity>,
)

@Entity(tableName = "deleted_memories")
data class DeletedMemoryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "deleted_at")
    val deletedAtMillis: Long,
)

/**
 * External-content FTS4 index over [MemoryEntity] using SQLite's `porter` stemmer, kept in sync
 * by Room-generated triggers. Powers the lexical channel of on-device GraphRAG ("Ask").
 */
@Fts4(contentEntity = MemoryEntity::class, tokenizer = FtsOptions.TOKENIZER_PORTER)
@Entity(tableName = "memories_fts")
data class MemoryFtsEntity(
    val content: String,
    val summary: String,
)
