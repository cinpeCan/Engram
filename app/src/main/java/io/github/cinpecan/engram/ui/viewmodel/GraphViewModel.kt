package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.model.RelationTriple
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface GraphIntent {
    data class BindMemory(val memoryId: String?) : GraphIntent
    data object ReplayGrowth : GraphIntent
    data class SelectEntity(val entityName: String) : GraphIntent
    data object ClearSelectedEntity : GraphIntent
}

@Immutable
data class GraphNodeUi(
    val id: String,
    val label: String,
    val degree: Int,
)

@Immutable
data class GraphMemoryOption(
    val id: String,
    val summarySnippet: String,
    val edgeCount: Int,
)

@Immutable
data class GraphCanvasState(
    val activeMemoryId: String? = null,
    val memorySummary: String = "2D 关系脉络",
    val nodes: List<GraphNodeUi> = emptyList(),
    val visibleEdges: List<RelationTriple> = emptyList(),
    val totalEdgeCount: Int = 0,
    val isReplaying: Boolean = false,
)

@Immutable
data class EntityCitationState(
    val entityName: String,
    val relatedMemories: List<MemoryItem> = emptyList(),
)

class GraphViewModel(
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    private val activeMemoryIdFlow = MutableStateFlow<String?>(null)
    private val replayEdgeLimitFlow = MutableStateFlow(Int.MAX_VALUE)
    private val isReplayingFlow = MutableStateFlow(false)
    private val selectedEntityFlow = MutableStateFlow<String?>(null)

    private var replayJob: Job? = null

    private val memoriesSource = memoryRepository.observeMemories()
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            replay = 1,
        )

    val availableMemories: StateFlow<List<GraphMemoryOption>> = memoriesSource
        .map { memories ->
            memories
                .filter { it.relations.isNotEmpty() }
                .map { item ->
                    val count = MemoryAnalyzer.sanitizeRelations(item.relations).size
                    GraphMemoryOption(
                        id = item.id,
                        summarySnippet = item.summary.ifBlank { item.content }.take(16),
                        edgeCount = count,
                    )
                }
        }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    private val targetMemoryFlow = combine(
        memoriesSource,
        activeMemoryIdFlow,
    ) { memories, requestedId ->
        memories.firstOrNull { it.id == requestedId }
            ?: memories.firstOrNull { it.relations.isNotEmpty() }
            ?: memories.firstOrNull()
    }.distinctUntilChanged()

    val canvasState: StateFlow<GraphCanvasState> = combine(
        targetMemoryFlow,
        replayEdgeLimitFlow,
        isReplayingFlow,
    ) { targetMemory, edgeLimit, replaying ->
        val allSanitizedEdges = targetMemory?.let { MemoryAnalyzer.sanitizeRelations(it.relations) }.orEmpty()
        val visibleEdges = allSanitizedEdges.take(edgeLimit)

        val degreeMap = LinkedHashMap<String, Int>()
        visibleEdges.forEach { rel ->
            degreeMap[rel.source] = (degreeMap[rel.source] ?: 0) + 1
            degreeMap[rel.target] = (degreeMap[rel.target] ?: 0) + 1
        }
        val nodes = degreeMap.entries.map { (id, deg) ->
            GraphNodeUi(id = id, label = id, degree = deg)
        }

        GraphCanvasState(
            activeMemoryId = targetMemory?.id,
            memorySummary = targetMemory?.summary?.ifBlank { targetMemory.content } ?: "2D 关系脉络",
            nodes = nodes,
            visibleEdges = visibleEdges,
            totalEdgeCount = allSanitizedEdges.size,
            isReplaying = replaying,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = GraphCanvasState(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val entityCitation: StateFlow<EntityCitationState?> = selectedEntityFlow
        .flatMapLatest { entity ->
            if (entity == null) {
                flowOf(null)
            } else {
                memoryRepository.observeMemoriesForEntity(entity)
                    .map { memories -> EntityCitationState(entityName = entity, relatedMemories = memories) }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = null,
        )

    fun onIntent(intent: GraphIntent) {
        when (intent) {
            is GraphIntent.BindMemory -> bindMemory(intent.memoryId)
            GraphIntent.ReplayGrowth -> replayStreamingGrowth()
            is GraphIntent.SelectEntity -> selectedEntityFlow.value = intent.entityName
            GraphIntent.ClearSelectedEntity -> selectedEntityFlow.value = null
        }
    }

    private fun bindMemory(memoryId: String?) {
        if (activeMemoryIdFlow.value != memoryId) {
            replayJob?.cancel()
            isReplayingFlow.value = false
            replayEdgeLimitFlow.value = Int.MAX_VALUE
            selectedEntityFlow.value = null
            activeMemoryIdFlow.value = memoryId
        }
    }

    private fun replayStreamingGrowth() {
        val total = canvasState.value.totalEdgeCount
        if (total <= 1) return
        replayJob?.cancel()
        replayJob = viewModelScope.launch {
            isReplayingFlow.value = true
            selectedEntityFlow.value = null
            for (count in 1..total) {
                replayEdgeLimitFlow.value = count
                delay(200L)
            }
            replayEdgeLimitFlow.value = Int.MAX_VALUE
            isReplayingFlow.value = false
        }
    }
}
