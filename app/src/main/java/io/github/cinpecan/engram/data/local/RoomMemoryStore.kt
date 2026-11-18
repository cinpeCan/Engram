package io.github.cinpecan.engram.data.local

import androidx.room.withTransaction
import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.model.RelationTriple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local Single Source of Truth (SSOT) backed by Jetpack Room (WAL + @Relation)
 * exposing cold table-tracked [Flow]s for on-demand subscription.
 */
class RoomMemoryStore(
    private val db: EngramDatabase,
    private val dao: MemoryDao = db.memoryDao(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LocalMemoryStore {

    override fun observeMemories(): Flow<List<MemoryItem>> =
        dao.observeMemories().map { list -> list.map { it.toDomain() } }

    override fun observeMemoryById(memoryId: String): Flow<MemoryItem?> =
        dao.observeMemoryById(memoryId).map { it?.toDomain() }

    override fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> =
        dao.observeMemoriesForEntity(entityName).map { list -> list.map { it.toDomain() } }

    override suspend fun insertRawMemory(item: MemoryItem) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                dao.upsertMemory(item.toEntity())
                replaceRelations(item.id, item.relations)
            }
        }
    }

    override suspend fun updateMemoryAnalysis(
        memoryId: String,
        payload: MemoryAnalysisPayload,
        analysisSource: AnalysisSource,
        telemetryBadge: String,
    ) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val content = dao.getMemoryContent(memoryId)
                if (content != null) {
                    dao.updateAnalysisFields(
                        memoryId = memoryId,
                        summary = payload.summary,
                        tagsJson = json.encodeToString(payload.tags),
                        status = MemoryAnalysisStatus.READY.name,
                        calendarJson = payload.calendarProposal?.let { json.encodeToString(it) },
                        analysisSource = analysisSource.name,
                        telemetryBadge = telemetryBadge,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    val normalizedRelations = payload.relations.mapIndexed { idx, rel ->
                        rel.copy(
                            id = rel.id.ifBlank { "${memoryId}_r$idx" },
                            memoryId = memoryId,
                        )
                    }
                    replaceRelations(memoryId, normalizedRelations)
                }
            }
        }
    }

    override suspend fun setCalendarSaved(memoryId: String, saved: Boolean) {
        withContext(Dispatchers.IO) {
            dao.setCalendarSaved(
                memoryId = memoryId,
                saved = saved,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun mergeRemoteMemories(remoteItems: List<MemoryItem>): Int =
        withContext(Dispatchers.IO) {
            var applied = 0
            db.withTransaction {
                for (remote in remoteItems) {
                    val existing = dao.getMemoryEntity(remote.id)
                    val shouldApply = when {
                        existing == null -> true
                        remote.updatedAtMillis > existing.updatedAtMillis -> true
                        remote.updatedAtMillis == existing.updatedAtMillis &&
                            remote.analysisStatus == MemoryAnalysisStatus.READY &&
                            existing.analysisStatus != MemoryAnalysisStatus.READY.name -> true
                        else -> false
                    }
                    if (shouldApply) {
                        dao.upsertMemory(remote.toEntity())
                        val normalizedRelations = remote.relations.mapIndexed { idx, rel ->
                            rel.copy(
                                id = rel.id.ifBlank { "${remote.id}_r$idx" },
                                memoryId = remote.id,
                            )
                        }
                        replaceRelations(remote.id, normalizedRelations)
                        applied++
                    }
                }
            }
            applied
        }

    override suspend fun getAllMemories(): List<MemoryItem> = withContext(Dispatchers.IO) {
        dao.getAllMemories().map { it.toDomain() }
    }

    override suspend fun countMemories(): Int = withContext(Dispatchers.IO) {
        dao.countMemories()
    }

    private suspend fun replaceRelations(
        memoryId: String,
        relations: List<RelationTriple>,
    ) {
        dao.deleteRelationsForMemory(memoryId)
        if (relations.isNotEmpty()) {
            dao.insertRelations(
                relations.mapIndexed { idx, rel ->
                    MemoryRelationEntity(
                        id = rel.id.ifBlank { "${memoryId}_r$idx" },
                        memoryId = memoryId,
                        source = rel.source,
                        target = rel.target,
                        relation = rel.relation,
                        bidirectional = rel.bidirectional,
                    )
                },
            )
        }
    }

    private fun MemoryItem.toEntity(): MemoryEntity =
        MemoryEntity(
            id = id,
            content = content,
            summary = summary,
            tagsJson = json.encodeToString(tags),
            createdAtMillis = createdAtMillis,
            analysisStatus = analysisStatus.name,
            calendarJson = calendarProposal?.let { json.encodeToString(it) },
            calendarSaved = calendarSaved,
            analysisSource = analysisSource.name,
            telemetryBadge = telemetryBadge,
            updatedAtMillis = updatedAtMillis,
        )

    private fun MemoryWithRelations.toDomain(): MemoryItem {
        val tags = runCatching { json.decodeFromString<List<String>>(memory.tagsJson) }
            .getOrDefault(emptyList())
        val status = runCatching { MemoryAnalysisStatus.valueOf(memory.analysisStatus) }
            .getOrDefault(MemoryAnalysisStatus.PENDING)
        val source = runCatching { AnalysisSource.valueOf(memory.analysisSource) }
            .getOrDefault(AnalysisSource.LOCAL_RULE_FALLBACK)
        val calendarProposal = memory.calendarJson?.let { raw ->
            runCatching { json.decodeFromString<CalendarProposal>(raw) }.getOrNull()
        }
        val resolvedUpdatedAt = if (memory.updatedAtMillis > 0L) {
            memory.updatedAtMillis
        } else {
            memory.createdAtMillis
        }
        return MemoryItem(
            id = memory.id,
            content = memory.content,
            summary = memory.summary,
            tags = tags,
            createdAtMillis = memory.createdAtMillis,
            analysisStatus = status,
            calendarProposal = calendarProposal,
            calendarSaved = memory.calendarSaved,
            relations = relations.map { rel ->
                RelationTriple(
                    id = rel.id,
                    memoryId = rel.memoryId,
                    source = rel.source,
                    target = rel.target,
                    relation = rel.relation,
                    bidirectional = rel.bidirectional,
                )
            },
            analysisSource = source,
            telemetryBadge = memory.telemetryBadge,
            updatedAtMillis = resolvedUpdatedAt,
        )
    }
}
