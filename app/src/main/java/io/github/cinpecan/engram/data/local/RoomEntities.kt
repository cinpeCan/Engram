package io.github.cinpecan.engram.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
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

@Entity(
    tableName = "memory_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memory_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MemoryEmbeddingEntity(
    @PrimaryKey
    @ColumnInfo(name = "memory_id")
    val memoryId: String,
    val dims: Int,
    @ColumnInfo(name = "vector_blob", typeAffinity = ColumnInfo.BLOB)
    val vectorBlob: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEmbeddingEntity) return false
        return memoryId == other.memoryId &&
            dims == other.dims &&
            vectorBlob.contentEquals(other.vectorBlob)
    }

    override fun hashCode(): Int {
        var result = memoryId.hashCode()
        result = 31 * result + dims
        result = 31 * result + vectorBlob.contentHashCode()
        return result
    }
}

@Fts4(notIndexed = ["memory_id"])
@Entity(tableName = "memories_fts")
data class MemoryFtsEntity(
    @ColumnInfo(name = "memory_id")
    val memoryId: String,
    @ColumnInfo(name = "bigram_tokens")
    val bigramTokens: String,
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

data class FtsHitProjection(
    @ColumnInfo(name = "memory_id")
    val memoryId: String,
    @ColumnInfo(name = "bigram_tokens")
    val bigramTokens: String,
)
