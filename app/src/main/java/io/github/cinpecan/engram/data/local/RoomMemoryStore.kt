package io.github.cinpecan.engram.data.local

import androidx.room.withTransaction
import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.model.RelationTriple
import io.github.cinpecan.engram.domain.rag.CjkBigramTokenizer
import io.github.cinpecan.engram.domain.rag.VectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local Single Source of Truth (SSOT) backed by Jetpack Room (WAL + FTS4 CJK bigrams + BLOB vectors)
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
                dao.upsertMemory(
                    MemoryEntity(
                        id = item.id,
                        content = item.content,
                        summary = item.summary,
                        tagsJson = json.encodeToString(item.tags),
                        createdAtMillis = item.createdAtMillis,
                        analysisStatus = item.analysisStatus.name,
                        calendarJson = item.calendarProposal?.let { json.encodeToString(it) },
                        calendarSaved = item.calendarSaved,
                    ),
                )
                replaceRelations(item.id, item.relations)
                replaceFtsEntry(
                    memoryId = item.id,
                    searchableText = buildSearchableText(
                        content = item.content,
                        summary = item.summary,
                        tags = item.tags,
                        relations = item.relations,
                    ),
                )
            }
        }
    }

    override suspend fun updateMemoryAnalysis(
        memoryId: String,
        payload: MemoryAnalysisPayload,
        embedding: FloatArray?,
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
                    )
                    val normalizedRelations = payload.relations.mapIndexed { idx, rel ->
                        rel.copy(
                            id = rel.id.ifBlank { "${memoryId}_r$idx" },
                            memoryId = memoryId,
                        )
                    }
                    replaceRelations(memoryId, normalizedRelations)
                    replaceFtsEntry(
                        memoryId = memoryId,
                        searchableText = buildSearchableText(
                            content = content,
                            summary = payload.summary,
                            tags = payload.tags,
                            relations = normalizedRelations,
                        ),
                    )
                }

                if (embedding != null && embedding.isNotEmpty()) {
                    val normalizedVec = VectorMath.l2Normalize(embedding)
                    dao.upsertEmbedding(
                        MemoryEmbeddingEntity(
                            memoryId = memoryId,
                            dims = normalizedVec.size,
                            vectorBlob = VectorMath.toByteArray(normalizedVec),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun markCalendarSaved(memoryId: String) {
        withContext(Dispatchers.IO) {
            dao.markCalendarSaved(memoryId)
        }
    }

    override suspend fun getAllMemories(): List<MemoryItem> = withContext(Dispatchers.IO) {
        dao.getAllMemories().map { it.toDomain() }
    }

    override suspend fun queryFtsLexicalIds(query: String): List<String> = withContext(Dispatchers.IO) {
        val matchExpr = CjkBigramTokenizer.buildFts4MatchExpression(query) ?: return@withContext emptyList()
        val queryTokens = CjkBigramTokenizer.tokenizeQuery(query)
        val rows = runCatching { dao.queryFtsHits(matchExpr) }.getOrElse { emptyList() }
        rows
            .map { hit ->
                val overlapScore = queryTokens.count { qt -> hit.bigramTokens.contains(qt) }
                hit.memoryId to overlapScore
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    override suspend fun loadAllEmbeddings(): Map<String, FloatArray> = withContext(Dispatchers.IO) {
        val entities = dao.getAllEmbeddings()
        val result = HashMap<String, FloatArray>(entities.size)
        for (entity in entities) {
            val vec = VectorMath.toFloatArray(entity.vectorBlob)
            if (vec.isNotEmpty()) {
                result[entity.memoryId] = vec
            }
        }
        result
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

    private suspend fun replaceFtsEntry(
        memoryId: String,
        searchableText: String,
    ) {
        dao.deleteFtsForMemory(memoryId)
        dao.insertFts(
            MemoryFtsEntity(
                memoryId = memoryId,
                bigramTokens = CjkBigramTokenizer.tokenizeForIndex(searchableText),
            ),
        )
    }

    private fun MemoryWithRelations.toDomain(): MemoryItem {
        val tags = runCatching { json.decodeFromString<List<String>>(memory.tagsJson) }
            .getOrDefault(emptyList())
        val status = runCatching { MemoryAnalysisStatus.valueOf(memory.analysisStatus) }
            .getOrDefault(MemoryAnalysisStatus.PENDING)
        val calendarProposal = memory.calendarJson?.let { raw ->
            runCatching { json.decodeFromString<CalendarProposal>(raw) }.getOrNull()
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
        )
    }

    private fun buildSearchableText(
        content: String,
        summary: String,
        tags: List<String>,
        relations: List<RelationTriple>,
    ): String {
        val sb = StringBuilder(content)
        if (summary.isNotBlank()) sb.append(' ').append(summary)
        if (tags.isNotEmpty()) sb.append(' ').append(tags.joinToString(" "))
        relations.forEach { rel ->
            sb.append(' ').append(rel.source).append(' ').append(rel.relation).append(' ').append(rel.target)
        }
        return sb.toString()
    }
}
