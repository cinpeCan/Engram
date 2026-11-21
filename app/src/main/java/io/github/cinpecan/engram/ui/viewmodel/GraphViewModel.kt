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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface GraphIntent {
    data object ReplayGrowth : GraphIntent
    data object ToggleUnfold : GraphIntent
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
data class GraphCanvasState(
    val memoryId: String? = null,
    val nodes: List<GraphNodeUi> = emptyList(),
    val visibleEdges: List<RelationTriple> = emptyList(),
    val totalEdgeCount: Int = 0,
    val isReplaying: Boolean = false,
    val isUnfoldEnabled: Boolean = false,
)

@Immutable
data class EntityCitationState(
    val entityName: String,
    val relatedMemories: List<MemoryItem> = emptyList(),
)

class GraphViewModel(
    private val memoryId: String,
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    private val replayEdgeLimitFlow = MutableStateFlow(Int.MAX_VALUE)
    private val isReplayingFlow = MutableStateFlow(false)
    private val isUnfoldEnabledFlow = MutableStateFlow(false)
    private val selectedEntityFlow = MutableStateFlow<String?>(null)

    private var replayJob: Job? = null

    private val targetMemoryFlow = memoryRepository.observeMemoryById(memoryId)
        .distinctUntilChanged()

    val canvasState: StateFlow<GraphCanvasState> = combine(
        targetMemoryFlow,
        replayEdgeLimitFlow,
        isReplayingFlow,
        isUnfoldEnabledFlow,
    ) { targetMemory, edgeLimit, replaying, unfoldEnabled ->
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
            memoryId = targetMemory?.id ?: memoryId,
            nodes = nodes,
            visibleEdges = visibleEdges,
            totalEdgeCount = allSanitizedEdges.size,
            isReplaying = replaying,
            isUnfoldEnabled = unfoldEnabled,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = GraphCanvasState(memoryId = memoryId),
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
            GraphIntent.ReplayGrowth -> replayStreamingGrowth()
            GraphIntent.ToggleUnfold -> isUnfoldEnabledFlow.value = !isUnfoldEnabledFlow.value
            is GraphIntent.SelectEntity -> selectedEntityFlow.value = intent.entityName
            GraphIntent.ClearSelectedEntity -> selectedEntityFlow.value = null
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
