package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.model.RelationTriple
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class GraphNodeUi(
    val id: String,
    val label: String,
    val degree: Int,
)

@Immutable
data class GraphUiState(
    val memoryTitle: String = "",
    val memorySummary: String = "",
    val nodes: List<GraphNodeUi> = emptyList(),
    val visibleEdges: List<RelationTriple> = emptyList(),
    val totalEdgeCount: Int = 0,
    val isReplaying: Boolean = false,
    val selectedEntity: String? = null,
    val relatedMemories: List<MemoryItem> = emptyList(),
)

class GraphViewModel(
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    private val activeMemoryIdFlow = MutableStateFlow<String?>(null)
    private val replayEdgeLimitFlow = MutableStateFlow(Int.MAX_VALUE)
    private val isReplayingFlow = MutableStateFlow(false)
    private val selectedEntityFlow = MutableStateFlow<String?>(null)
    private val relatedMemoriesFlow = MutableStateFlow<List<MemoryItem>>(emptyList())

    private var replayJob: Job? = null

    val uiState: StateFlow<GraphUiState> = combine(
        memoryRepository.memoriesFlow,
        activeMemoryIdFlow,
        replayEdgeLimitFlow,
        isReplayingFlow,
        selectedEntityFlow,
        relatedMemoriesFlow,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val memories = values[0] as List<MemoryItem>
        val requestedId = values[1] as String?
        val edgeLimit = values[2] as Int
        val replaying = values[3] as Boolean
        val selectedEntity = values[4] as String?
        @Suppress("UNCHECKED_CAST")
        val relatedMemories = values[5] as List<MemoryItem>

        val targetMemory = memories.firstOrNull { it.id == requestedId }
            ?: memories.firstOrNull { it.relations.isNotEmpty() }
            ?: memories.firstOrNull()

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

        GraphUiState(
            memoryTitle = targetMemory?.summary?.take(28) ?: "2D 关系脉络",
            memorySummary = targetMemory?.content.orEmpty(),
            nodes = nodes,
            visibleEdges = visibleEdges,
            totalEdgeCount = allSanitizedEdges.size,
            isReplaying = replaying,
            selectedEntity = selectedEntity,
            relatedMemories = relatedMemories,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = GraphUiState(),
    )

    fun bindMemory(memoryId: String?) {
        if (activeMemoryIdFlow.value != memoryId) {
            replayJob?.cancel()
            isReplayingFlow.value = false
            replayEdgeLimitFlow.value = Int.MAX_VALUE
            selectedEntityFlow.value = null
            relatedMemoriesFlow.value = emptyList()
            activeMemoryIdFlow.value = memoryId
        }
    }

    fun replayStreamingGrowth() {
        val total = uiState.value.totalEdgeCount
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

    fun selectEntity(entityName: String) {
        selectedEntityFlow.value = entityName
        viewModelScope.launch {
            relatedMemoriesFlow.value = memoryRepository.findMemoriesForEntity(entityName)
        }
    }

    fun clearSelectedEntity() {
        selectedEntityFlow.value = null
        relatedMemoriesFlow.value = emptyList()
    }

    class Factory(
        private val memoryRepository: MemoryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GraphViewModel(memoryRepository) as T
    }
}
