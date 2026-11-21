package io.github.cinpecan.engram.data.repository

import androidx.compose.runtime.Immutable
import io.github.cinpecan.engram.data.calendar.CalendarAnchorTarget
import io.github.cinpecan.engram.data.calendar.SystemCalendarObserver
import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.preferences.EnginePreferences
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.DefaultCloudEndpointProvider
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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val calendarObserver: SystemCalendarObserver? = null,
    private val cloudSessionMonitor: CloudSessionMonitor? = null,
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val seedMutex = Mutex()
    private val activeDistillationJobs = ConcurrentHashMap<String, Job>()

    private val _streamingPreviews = MutableStateFlow<Map<String, StreamingPreviewState>>(emptyMap())
    val streamingPreviews: StateFlow<Map<String, StreamingPreviewState>> = _streamingPreviews.asStateFlow()

    private val syncedCalendarEventsFlow: Flow<Map<String, Long>> by lazy {
        val observer = calendarObserver ?: return@lazy flowOf(emptyMap())
        val targetsFlow = store.observeMemories()
            .map { list ->
                list.mapNotNull { item ->
                    item.calendarProposal?.let { proposal ->
                        CalendarAnchorTarget(memoryId = item.id, proposal = proposal)
                    }
                }
            }
            .distinctUntilChanged()
        observer.observeSyncedEvents(targetsFlow)
    }

    fun observeMemories(): Flow<List<MemoryItem>> {
        val baseFlow = store.observeMemories()
        if (calendarObserver == null) return baseFlow
        return combine(baseFlow, syncedCalendarEventsFlow) { list, syncedMap ->
            list.map { item -> reconcileCalendarItem(item, syncedMap) }
        }.distinctUntilChanged()
    }

    fun observeMemoryById(memoryId: String): Flow<MemoryItem?> {
        val baseFlow = store.observeMemoryById(memoryId)
        if (calendarObserver == null) return baseFlow
        return combine(baseFlow, syncedCalendarEventsFlow) { item, syncedMap ->
            item?.let { reconcileCalendarItem(it, syncedMap) }
        }.distinctUntilChanged()
    }

    fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> =
        store.observeMemoriesForEntity(entityName)

    fun notifyCalendarPermissionChanged() {
        calendarObserver?.notifyPermissionChanged()
    }

    private suspend fun reconcileCalendarItem(
        item: MemoryItem,
        syncedMap: Map<String, Long>,
    ): MemoryItem {
        val observer = calendarObserver ?: return item
        val eventId = syncedMap[item.id]
        val isSynced = eventId != null
        if (observer.hasReadCalendarPermission() && item.calendarSaved != isSynced) {
            store.setCalendarSaved(item.id, isSynced)
        }
        return item.copy(
            calendarSaved = isSynced,
            calendarEventId = eventId,
        )
    }

    suspend fun initializeAndSeedIfNeeded() {
        seedMutex.withLock {
            val hasSeeded = preferencesRepository?.preferencesFlow?.first()?.hasSeededStoryline == true
            if (store.countMemories() == 0 && !hasSeeded) {
                val seeds = SeedStorylineProvider.createInitialStoryline()
                for (seed in seeds) {
                    store.insertRawMemory(seed)
                }
                preferencesRepository?.markStorylineSeeded()
            } else {
                if (!hasSeeded && store.countMemories() > 0) {
                    preferencesRepository?.markStorylineSeeded()
                }
                val existingById = store.getAllMemories().associateBy { it.id }
                val latestSeeds = SeedStorylineProvider.createInitialStoryline()
                for (seed in latestSeeds) {
                    val existing = existingById[seed.id] ?: continue
                    if (existing.analysisSource != AnalysisSource.SEED) continue
                    if (existing.content != seed.content) {
                        // Seed storyline changed between releases: fully replace content,
                        // summary, tags, calendar and relations. Deleted seeds stay deleted
                        // because they are absent from the store.
                        store.insertRawMemory(
                            seed.copy(
                                createdAtMillis = existing.createdAtMillis,
                                updatedAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    } else if (existing.telemetryBadge.isNotEmpty() || existing.summary != seed.summary) {
                        store.updateMemoryAnalysis(
                            memoryId = seed.id,
                            payload = MemoryAnalysisPayload(
                                summary = seed.summary,
                                tags = seed.tags,
                                calendarProposal = existing.calendarProposal ?: seed.calendarProposal,
                                relations = seed.relations,
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

        val job = backgroundScope.launch {
            val prefs = preferencesRepository?.preferencesFlow?.first()
            val mode = prefs?.inferenceMode ?: InferenceMode.OFFLINE_EDGE
            val serverBaseUrl = prefs?.serverBaseUrl.orEmpty()
            val syncKey = prefs?.syncKey.orEmpty()

            _streamingPreviews.update {
                it + (id to StreamingPreviewState(partialText = "", stageLabel = UnifiedInferenceRouter.STAGE_PREPARING))
            }
            try {
                inferenceRouter.distillMemory(
                    content = content,
                    nowMillis = now,
                    mode = mode,
                    serverBaseUrl = serverBaseUrl,
                    authToken = syncKey,
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
                            val touchedCloudQuota = event.source == AnalysisSource.CLOUD_SSE ||
                                event.telemetryBadge == UnifiedInferenceRouter.BADGE_FALLBACK_UNAUTHORIZED ||
                                event.telemetryBadge == UnifiedInferenceRouter.BADGE_FALLBACK_QUOTA ||
                                event.telemetryBadge.startsWith(UnifiedInferenceRouter.BADGE_CLOUD_FALLBACK_PREFIX)
                            if (touchedCloudQuota) {
                                cloudSessionMonitor?.retryVerification()
                            }
                        }
                    }
                }
            } finally {
                _streamingPreviews.update { it - id }
                activeDistillationJobs.remove(id)
            }
        }
        activeDistillationJobs[id] = job
        return initial
    }

    suspend fun deleteMemory(memoryId: String) {
        activeDistillationJobs.remove(memoryId)?.cancel()
        _streamingPreviews.update { it - memoryId }
        val deletedAt = System.currentTimeMillis()
        store.deleteMemory(memoryId, deletedAtMillis = deletedAt)

        if (!memoryId.startsWith("seed-")) {
            val client = syncClient
            val prefs = preferencesRepository?.preferencesFlow?.first()
            val token = DefaultCloudEndpointProvider.resolveBearerTokenOrNull(prefs?.syncKey)
            if (client != null && prefs != null && token != null) {
                backgroundScope.launch {
                    runCatching {
                        client.pushDeletedMemories(
                            baseUrl = prefs.serverBaseUrl,
                            syncKey = token.take(64),
                            deletedIds = listOf(memoryId),
                            authToken = token,
                        )
                    }
                }
            }
        }
    }

    suspend fun restoreMemory(item: MemoryItem) {
        store.restoreMemory(item)
    }

    suspend fun syncWithServer(
        serverBaseUrl: String,
        syncKey: String,
    ): Result<SyncResultSummary> = runCatching {
        val client = syncClient ?: error("Sync client is not initialized")
        val trimmedKey = syncKey.trim()
        val wireSyncKey = trimmedKey.ifEmpty { EnginePreferences.PLACEHOLDER_SYNC_KEY }.take(64)
        val deletedIds = store.getDeletedTombstones().keys
            .filter { !it.startsWith("seed-") }
            .take(500)
        if (deletedIds.isNotEmpty()) {
            runCatching {
                client.pushDeletedMemories(
                    baseUrl = serverBaseUrl,
                    syncKey = wireSyncKey,
                    deletedIds = deletedIds,
                    authToken = trimmedKey,
                )
            }
        }
        val localItems = store.getAllMemories()
            .filter { it.analysisSource != AnalysisSource.SEED }
            .take(500)
        val request = MemorySyncRequestDto(
            syncKey = wireSyncKey,
            memories = localItems.map { it.toSyncDto() },
        )
        val response = client.syncMemories(
            baseUrl = serverBaseUrl,
            requestPayload = request,
            authToken = trimmedKey,
        )
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
