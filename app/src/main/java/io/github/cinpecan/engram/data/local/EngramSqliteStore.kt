package io.github.cinpecan.engram.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.model.RelationTriple
import io.github.cinpecan.engram.domain.rag.CjkBigramTokenizer
import io.github.cinpecan.engram.domain.rag.VectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local Single Source of Truth (SSOT) backed by SQLite WAL + FTS4 CJK bigrams + BLOB vectors.
 */
class EngramSqliteStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION), LocalMemoryStore {

    private val _memoriesFlow = MutableStateFlow<List<MemoryItem>>(emptyList())
    override val memoriesFlow: StateFlow<List<MemoryItem>> = _memoriesFlow.asStateFlow()

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memories (
                id TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                summary TEXT NOT NULL,
                tags_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                analysis_status TEXT NOT NULL,
                calendar_json TEXT,
                calendar_saved INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_memories_created ON memories(created_at DESC)",
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts4(
                memory_id UNINDEXED,
                bigram_tokens
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_embeddings (
                memory_id TEXT PRIMARY KEY,
                dims INTEGER NOT NULL,
                vector_blob BLOB NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_relations (
                id TEXT PRIMARY KEY,
                memory_id TEXT NOT NULL,
                source TEXT NOT NULL,
                target TEXT NOT NULL,
                relation TEXT NOT NULL,
                bidirectional INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_relations_memory ON memory_relations(memory_id)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Initial v1 schema
    }

    override suspend fun refresh() {
        val items = getAllMemories()
        _memoriesFlow.value = items
    }

    override suspend fun insertRawMemory(item: MemoryItem) {
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put("id", item.id)
                    put("content", item.content)
                    put("summary", item.summary)
                    put("tags_json", json.encodeToString(item.tags))
                    put("created_at", item.createdAtMillis)
                    put("analysis_status", item.analysisStatus.name)
                    put(
                        "calendar_json",
                        item.calendarProposal?.let { json.encodeToString(it) },
                    )
                    put("calendar_saved", if (item.calendarSaved) 1 else 0)
                }
                db.insertWithOnConflict("memories", null, values, SQLiteDatabase.CONFLICT_REPLACE)

                replaceFtsEntryLocked(
                    db = db,
                    memoryId = item.id,
                    searchableText = buildSearchableText(
                        content = item.content,
                        summary = item.summary,
                        tags = item.tags,
                        relations = item.relations,
                    ),
                )
                replaceRelationsLocked(db, item.id, item.relations)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            _memoriesFlow.value = loadMemoriesLocked(db)
        }
    }

    override suspend fun updateMemoryAnalysis(
        memoryId: String,
        payload: MemoryAnalysisPayload,
        embedding: FloatArray?,
    ) {
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val content = queryContentLocked(db, memoryId)
                if (content != null) {
                    val values = ContentValues().apply {
                        put("summary", payload.summary)
                        put("tags_json", json.encodeToString(payload.tags))
                        put("analysis_status", MemoryAnalysisStatus.READY.name)
                        put(
                            "calendar_json",
                            payload.calendarProposal?.let { json.encodeToString(it) },
                        )
                    }
                    db.update("memories", values, "id = ?", arrayOf(memoryId))

                    val normalizedRelations = payload.relations.mapIndexed { idx, rel ->
                        rel.copy(
                            id = rel.id.ifBlank { "${memoryId}_r$idx" },
                            memoryId = memoryId,
                        )
                    }
                    replaceRelationsLocked(db, memoryId, normalizedRelations)
                    replaceFtsEntryLocked(
                        db = db,
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
                    val embValues = ContentValues().apply {
                        put("memory_id", memoryId)
                        put("dims", normalizedVec.size)
                        put("vector_blob", VectorMath.toByteArray(normalizedVec))
                    }
                    db.insertWithOnConflict(
                        "memory_embeddings",
                        null,
                        embValues,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            _memoriesFlow.value = loadMemoriesLocked(db)
        }
    }

    override suspend fun markCalendarSaved(memoryId: String) {
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("calendar_saved", 1)
            }
            db.update("memories", values, "id = ?", arrayOf(memoryId))
            _memoriesFlow.value = loadMemoriesLocked(db)
        }
    }

    override suspend fun getAllMemories(): List<MemoryItem> = withContext(Dispatchers.IO) {
        loadMemoriesLocked(readableDatabase)
    }

    override suspend fun queryFtsLexicalIds(query: String): List<String> = withContext(Dispatchers.IO) {
        val matchExpr = CjkBigramTokenizer.buildFts4MatchExpression(query) ?: return@withContext emptyList()
        val queryTokens = CjkBigramTokenizer.tokenizeQuery(query)
        val db = readableDatabase
        val hits = ArrayList<Pair<String, Int>>()

        try {
            db.rawQuery(
                "SELECT memory_id, bigram_tokens FROM memories_fts WHERE bigram_tokens MATCH ?",
                arrayOf(matchExpr),
            ).use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow("memory_id")
                val tokensIdx = cursor.getColumnIndexOrThrow("bigram_tokens")
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIdx)
                    val docTokens = cursor.getString(tokensIdx).orEmpty()
                    val overlapScore = queryTokens.count { qt -> docTokens.contains(qt) }
                    hits.add(id to overlapScore)
                }
            }
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        hits.sortedByDescending { it.second }.map { it.first }
    }

    override suspend fun loadAllEmbeddings(): Map<String, FloatArray> = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val result = HashMap<String, FloatArray>()
        db.rawQuery(
            "SELECT memory_id, vector_blob FROM memory_embeddings",
            null,
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("memory_id")
            val blobIdx = cursor.getColumnIndexOrThrow("vector_blob")
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx)
                val bytes = cursor.getBlob(blobIdx)
                val vec = VectorMath.toFloatArray(bytes)
                if (vec.isNotEmpty()) {
                    result[id] = vec
                }
            }
        }
        result
    }

    override suspend fun countMemories(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM memories", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun queryContentLocked(db: SQLiteDatabase, memoryId: String): String? {
        db.rawQuery(
            "SELECT content FROM memories WHERE id = ? LIMIT 1",
            arrayOf(memoryId),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun replaceFtsEntryLocked(
        db: SQLiteDatabase,
        memoryId: String,
        searchableText: String,
    ) {
        db.delete("memories_fts", "memory_id = ?", arrayOf(memoryId))
        val ftsValues = ContentValues().apply {
            put("memory_id", memoryId)
            put("bigram_tokens", CjkBigramTokenizer.tokenizeForIndex(searchableText))
        }
        db.insert("memories_fts", null, ftsValues)
    }

    private fun replaceRelationsLocked(
        db: SQLiteDatabase,
        memoryId: String,
        relations: List<RelationTriple>,
    ) {
        db.delete("memory_relations", "memory_id = ?", arrayOf(memoryId))
        relations.forEachIndexed { idx, rel ->
            val values = ContentValues().apply {
                put("id", rel.id.ifBlank { "${memoryId}_r$idx" })
                put("memory_id", memoryId)
                put("source", rel.source)
                put("target", rel.target)
                put("relation", rel.relation)
                put("bidirectional", if (rel.bidirectional) 1 else 0)
            }
            db.insert("memory_relations", null, values)
        }
    }

    private fun loadMemoriesLocked(db: SQLiteDatabase): List<MemoryItem> {
        val relationsByMemory = HashMap<String, MutableList<RelationTriple>>()
        db.rawQuery(
            "SELECT id, memory_id, source, target, relation, bidirectional FROM memory_relations",
            null,
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val memIdx = cursor.getColumnIndexOrThrow("memory_id")
            val srcIdx = cursor.getColumnIndexOrThrow("source")
            val tgtIdx = cursor.getColumnIndexOrThrow("target")
            val relIdx = cursor.getColumnIndexOrThrow("relation")
            val biIdx = cursor.getColumnIndexOrThrow("bidirectional")
            while (cursor.moveToNext()) {
                val memId = cursor.getString(memIdx)
                relationsByMemory.getOrPut(memId) { mutableListOf() }.add(
                    RelationTriple(
                        id = cursor.getString(idIdx),
                        memoryId = memId,
                        source = cursor.getString(srcIdx),
                        target = cursor.getString(tgtIdx),
                        relation = cursor.getString(relIdx),
                        bidirectional = cursor.getInt(biIdx) != 0,
                    ),
                )
            }
        }

        val items = ArrayList<MemoryItem>()
        db.rawQuery(
            """
            SELECT id, content, summary, tags_json, created_at, analysis_status, calendar_json, calendar_saved
            FROM memories
            ORDER BY created_at DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val contentIdx = cursor.getColumnIndexOrThrow("content")
            val summaryIdx = cursor.getColumnIndexOrThrow("summary")
            val tagsIdx = cursor.getColumnIndexOrThrow("tags_json")
            val createdIdx = cursor.getColumnIndexOrThrow("created_at")
            val statusIdx = cursor.getColumnIndexOrThrow("analysis_status")
            val calJsonIdx = cursor.getColumnIndexOrThrow("calendar_json")
            val calSavedIdx = cursor.getColumnIndexOrThrow("calendar_saved")

            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx)
                val tagsJson = cursor.getString(tagsIdx)
                val tags = runCatching { json.decodeFromString<List<String>>(tagsJson) }
                    .getOrDefault(emptyList())
                val statusName = cursor.getString(statusIdx)
                val status = runCatching { MemoryAnalysisStatus.valueOf(statusName) }
                    .getOrDefault(MemoryAnalysisStatus.PENDING)
                val calJson = cursor.getString(calJsonIdx)
                val calendarProposal = calJson?.let { raw ->
                    runCatching { json.decodeFromString<CalendarProposal>(raw) }.getOrNull()
                }
                items.add(
                    MemoryItem(
                        id = id,
                        content = cursor.getString(contentIdx),
                        summary = cursor.getString(summaryIdx),
                        tags = tags,
                        createdAtMillis = cursor.getLong(createdIdx),
                        analysisStatus = status,
                        calendarProposal = calendarProposal,
                        calendarSaved = cursor.getInt(calSavedIdx) != 0,
                        relations = relationsByMemory[id].orEmpty(),
                    ),
                )
            }
        }
        return items
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

    companion object {
        private const val DB_NAME = "engram_memory.db"
        private const val DB_VERSION = 1
    }
}
