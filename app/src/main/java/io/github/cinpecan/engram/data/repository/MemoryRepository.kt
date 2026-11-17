package io.github.cinpecan.engram.data.repository

import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.seed.SeedStorylineProvider
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.rag.RagSearchResult
import io.github.cinpecan.engram.domain.rag.RrfHybridScorer
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MemoryRepository(
    private val store: LocalMemoryStore,
    private val engine: EdgeAiEngine,
    private val analyzer: MemoryAnalyzer,
    private val preferencesRepository: EnginePreferencesRepository? = null,
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val seedMutex = Mutex()

    fun observeMemories(): Flow<List<MemoryItem>> = store.observeMemories()

    fun observeMemoryById(memoryId: String): Flow<MemoryItem?> = store.observeMemoryById(memoryId)

    fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> =
        store.observeMemoriesForEntity(entityName).map { directMatches ->
            directMatches.ifEmpty {
                searchHybrid(entityName, topK = 3).map { it.memory }
            }
        }

    suspend fun initializeAndSeedIfNeeded() {
        seedMutex.withLock {
            if (store.countMemories() == 0) {
                val seeds = SeedStorylineProvider.createInitialStoryline()
                for (seed in seeds) {
                    store.insertRawMemory(seed)
                    val vec = runCatching { engine.embedText(seed.content) }.getOrNull()
                    store.updateMemoryAnalysis(
                        memoryId = seed.id,
                        payload = MemoryAnalysisPayload(
                            summary = seed.summary,
                            tags = seed.tags,
                            calendarProposal = seed.calendarProposal,
                            relations = seed.relations,
                        ),
                        embedding = vec,
                    )
                }
                preferencesRepository?.markStorylineSeeded()
            }
        }
    }

    suspend fun captureMemory(rawText: String): MemoryItem? {
        val content = rawText.trim()
        if (content.isEmpty()) return null

        val now = System.currentTimeMillis()
        val id = "mem_${UUID.randomUUID().toString().take(8)}"
        val initialSummary = content.replace(Regex("\\s+"), " ").let {
            if (it.length > 64) it.take(64) + "…" else it
        }
        val initial = MemoryItem(
            id = id,
            content = content,
            summary = initialSummary,
            tags = emptyList(),
            createdAtMillis = now,
            analysisStatus = MemoryAnalysisStatus.PENDING,
            calendarProposal = null,
            calendarSaved = false,
            relations = emptyList(),
        )
        store.insertRawMemory(initial)

        backgroundScope.launch {
            val payload = analyzer.analyze(content, now)
            val embedding = runCatching { engine.embedText(content) }.getOrNull()
            store.updateMemoryAnalysis(
                memoryId = id,
                payload = payload,
                embedding = embedding,
            )
        }
        return initial
    }

    suspend fun markCalendarSaved(memoryId: String) {
        store.markCalendarSaved(memoryId)
    }

    suspend fun searchHybrid(query: String, topK: Int = 4): List<RagSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val allMemories = store.getAllMemories()
        val lexicalIds = store.queryFtsLexicalIds(trimmed)
        val embeddings = store.loadAllEmbeddings()
        val queryVec = runCatching { engine.embedText(trimmed) }.getOrElse { FloatArray(128) }

        return RrfHybridScorer.score(
            memories = allMemories,
            lexicalOrderedIds = lexicalIds,
            queryEmbedding = queryVec,
            memoryEmbeddings = embeddings,
            topK = topK,
        )
    }

    suspend fun findMemoriesForEntity(entityName: String): List<MemoryItem> {
        val all = store.getAllMemories()
        val directMatches = all.filter { item ->
            item.content.contains(entityName, ignoreCase = true) ||
                item.relations.any { it.source == entityName || it.target == entityName }
        }
        if (directMatches.isNotEmpty()) return directMatches
        return searchHybrid(entityName, topK = 3).map { it.memory }
    }
}
