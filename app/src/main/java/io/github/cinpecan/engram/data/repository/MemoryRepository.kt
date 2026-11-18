package io.github.cinpecan.engram.data.repository

import androidx.compose.runtime.Immutable
import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.preferences.EnginePreferences
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.MemorySyncClient
import io.github.cinpecan.engram.data.remote.MemorySyncRequestDto
import io.github.cinpecan.engram.data.remote.SyncCalendarProposalDto
import io.github.cinpecan.engram.data.remote.SyncMemoryItemDto
import io.github.cinpecan.engram.data.remote.SyncRelationTripleDto
import io.github.cinpecan.engram.data.seed.SeedStorylineProvider
import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.model.RelationTriple
import io.github.cinpecan.engram.domain.model.StreamingPreviewState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Immutable
data class SyncResultSummary(
    val uploadedCount: Int,
    val pulledCount: Int,
    val totalRemoteCount: Int,
)

class MemoryRepository(
    private val store: LocalMemoryStore,
    private val inferenceRouter: UnifiedInferenceRouter,
    private val preferencesRepository: EnginePreferencesRepository? = null,
    private val syncClient: MemorySyncClient? = null,
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val seedMutex = Mutex()

    private val _streamingPreviews = MutableStateFlow<Map<String, StreamingPreviewState>>(emptyMap())
    val streamingPreviews: StateFlow<Map<String, StreamingPreviewState>> = _streamingPreviews.asStateFlow()

    fun observeMemories(): Flow<List<MemoryItem>> = store.observeMemories()

    fun observeMemoryById(memoryId: String): Flow<MemoryItem?> = store.observeMemoryById(memoryId)

    fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> =
        store.observeMemoriesForEntity(entityName)

    suspend fun initializeAndSeedIfNeeded() {
        seedMutex.withLock {
            if (store.countMemories() == 0) {
                val seeds = SeedStorylineProvider.createInitialStoryline()
                for (seed in seeds) {
                    store.insertRawMemory(seed)
                }
                preferencesRepository?.markStorylineSeeded()
            } else {
                val existingById = store.getAllMemories().associateBy { it.id }
                val latestSeeds = SeedStorylineProvider.createInitialStoryline()
                for (seed in latestSeeds) {
                    val existing = existingById[seed.id]
                    if (existing != null &&
                        existing.analysisSource == AnalysisSource.SEED &&
                        (existing.telemetryBadge.isNotEmpty() || existing.summary != seed.summary)
                    ) {
                        store.updateMemoryAnalysis(
                            memoryId = seed.id,
                            payload = MemoryAnalysisPayload(
                                summary = seed.summary,
                                tags = seed.tags,
                                calendarProposal = existing.calendarProposal ?: seed.calendarProposal,
                                relations = existing.relations.ifEmpty { seed.relations },
                            ),
                            analysisSource = AnalysisSource.SEED,
                            telemetryBadge = "",
                        )
                    }
                }
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
            analysisSource = AnalysisSource.LOCAL_RULE_FALLBACK,
            telemetryBadge = "",
            updatedAtMillis = now,
        )
        store.insertRawMemory(initial)

        backgroundScope.launch {
            val prefs = preferencesRepository?.preferencesFlow?.first()
            val mode = prefs?.inferenceMode ?: InferenceMode.OFFLINE_EDGE
            val serverBaseUrl = prefs?.serverBaseUrl ?: EnginePreferences.DEFAULT_SERVER_BASE_URL

            _streamingPreviews.update {
                it + (id to StreamingPreviewState(partialText = "", stageLabel = "准备提炼…"))
            }
            try {
                inferenceRouter.distillMemory(
                    content = content,
                    nowMillis = now,
                    mode = mode,
                    serverBaseUrl = serverBaseUrl,
                ).collect { event ->
                    when (event) {
                        is DistillationEvent.StreamingPreview -> {
                            _streamingPreviews.update {
                                it + (id to StreamingPreviewState(event.partialText, event.stageLabel))
                            }
                        }
                        is DistillationEvent.Completed -> {
                            store.updateMemoryAnalysis(
                                memoryId = id,
                                payload = event.payload,
                                analysisSource = event.source,
                                telemetryBadge = event.telemetryBadge,
                            )
                        }
                    }
                }
            } finally {
                _streamingPreviews.update { it - id }
            }
        }
        return initial
    }

    suspend fun markCalendarSaved(memoryId: String) {
        store.markCalendarSaved(memoryId)
    }

    suspend fun syncWithServer(
        serverBaseUrl: String,
        syncKey: String,
    ): Result<SyncResultSummary> = runCatching {
        val client = syncClient ?: error("未初始化同步客户端")
        val cleanedKey = syncKey.trim().ifEmpty { EnginePreferences.DEFAULT_SYNC_KEY }
        val localItems = store.getAllMemories()
            .filter { it.analysisSource != AnalysisSource.SEED }
            .take(500)
        val request = MemorySyncRequestDto(
            syncKey = cleanedKey,
            memories = localItems.map { it.toSyncDto() },
        )
        val response = client.syncMemories(serverBaseUrl, request)
        val pulledApplied = store.mergeRemoteMemories(response.memories.map { it.toDomain() })
        SyncResultSummary(
            uploadedCount = response.upsertedCount,
            pulledCount = pulledApplied,
            totalRemoteCount = response.totalCount,
        )
    }

    private fun MemoryItem.toSyncDto(): SyncMemoryItemDto =
        SyncMemoryItemDto(
            id = id,
            content = content,
            summary = summary,
            tags = tags,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            analysisStatus = analysisStatus.name,
            calendarProposal = calendarProposal?.let {
                SyncCalendarProposalDto(
                    title = it.title,
                    startTimeIso = it.startTimeIso,
                    durationMinutes = it.durationMinutes,
                    location = it.location,
                )
            },
            calendarSaved = calendarSaved,
            analysisSource = analysisSource.name,
            telemetryBadge = telemetryBadge,
            relations = relations.map { rel ->
                SyncRelationTripleDto(
                    source = rel.source,
                    target = rel.target,
                    relation = rel.relation,
                    bidirectional = rel.bidirectional,
                )
            },
        )

    private fun SyncMemoryItemDto.toDomain(): MemoryItem {
        val status = runCatching { MemoryAnalysisStatus.valueOf(analysisStatus) }
            .getOrDefault(MemoryAnalysisStatus.READY)
        val source = runCatching { AnalysisSource.valueOf(analysisSource) }
            .getOrDefault(AnalysisSource.LOCAL_RULE_FALLBACK)
        return MemoryItem(
            id = id,
            content = content,
            summary = summary,
            tags = tags,
            createdAtMillis = createdAtMillis,
            analysisStatus = status,
            calendarProposal = calendarProposal?.let {
                CalendarProposal(
                    title = it.title,
                    startTimeIso = it.startTimeIso,
                    durationMinutes = it.durationMinutes,
                    location = it.location,
                )
            },
            calendarSaved = calendarSaved,
            relations = relations.mapIndexed { idx, rel ->
                RelationTriple(
                    id = "${id}_r$idx",
                    memoryId = id,
                    source = rel.source,
                    target = rel.target,
                    relation = rel.relation,
                    bidirectional = rel.bidirectional,
                )
            },
            analysisSource = source,
            telemetryBadge = telemetryBadge,
            updatedAtMillis = updatedAtMillis,
        )
    }
}
