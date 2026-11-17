package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.repository.AskStreamEvent
import io.github.cinpecan.engram.data.repository.UnifiedInferenceRouter
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.domain.rag.RagSearchResult
import io.github.cinpecan.engram.edgemnn.InferenceTelemetry
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class AskConversationTurn(
    val id: String,
    val query: String,
    val citations: List<RagSearchResult> = emptyList(),
    val answerText: String = "",
    val isStreaming: Boolean = true,
    val telemetry: InferenceTelemetry? = null,
)

@Immutable
data class AskUiState(
    val mode: InferenceMode = InferenceMode.OFFLINE_EDGE,
    val serverBaseUrl: String = "",
    val queryInput: String = "",
    val turns: List<AskConversationTurn> = emptyList(),
    val isAsking: Boolean = false,
)

class AskViewModel(
    private val inferenceRouter: UnifiedInferenceRouter,
    private val preferencesRepository: EnginePreferencesRepository,
) : ViewModel() {

    private val queryInputFlow = MutableStateFlow("")
    private val turnsFlow = MutableStateFlow<List<AskConversationTurn>>(emptyList())
    private val isAskingFlow = MutableStateFlow(false)
    private var activeAskJob: Job? = null

    val uiState: StateFlow<AskUiState> = combine(
        preferencesRepository.preferencesFlow,
        queryInputFlow,
        turnsFlow,
        isAskingFlow,
    ) { prefs, input, turns, asking ->
        AskUiState(
            mode = prefs.inferenceMode,
            serverBaseUrl = prefs.serverBaseUrl,
            queryInput = input,
            turns = turns,
            isAsking = asking,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AskUiState(),
    )

    fun setMode(mode: InferenceMode) {
        viewModelScope.launch {
            preferencesRepository.setInferenceMode(mode)
        }
    }

    fun updateQuery(query: String) {
        queryInputFlow.value = query
    }

    fun submitQuery(overrideQuery: String? = null) {
        val raw = (overrideQuery ?: queryInputFlow.value).trim()
        if (raw.isEmpty()) return

        activeAskJob?.cancel()
        if (overrideQuery == null) {
            queryInputFlow.value = ""
        }

        val turnId = UUID.randomUUID().toString().take(8)
        turnsFlow.update { current ->
            current + AskConversationTurn(id = turnId, query = raw, isStreaming = true)
        }

        activeAskJob = viewModelScope.launch {
            isAskingFlow.value = true
            try {
                val prefs = preferencesRepository.preferencesFlow.first()
                inferenceRouter.ask(
                    query = raw,
                    mode = prefs.inferenceMode,
                    serverBaseUrl = prefs.serverBaseUrl,
                ).collect { event ->
                    when (event) {
                        is AskStreamEvent.CitationsReady -> updateTurn(turnId) {
                            it.copy(citations = event.citations)
                        }
                        is AskStreamEvent.TokenDelta -> updateTurn(turnId) {
                            it.copy(answerText = it.answerText + event.delta)
                        }
                        is AskStreamEvent.Completed -> updateTurn(turnId) {
                            it.copy(
                                answerText = event.fullText.ifEmpty { it.answerText },
                                isStreaming = false,
                                telemetry = event.telemetry,
                            )
                        }
                    }
                }
            } finally {
                isAskingFlow.value = false
            }
        }
    }

    private fun updateTurn(
        turnId: String,
        transform: (AskConversationTurn) -> AskConversationTurn,
    ) {
        turnsFlow.update { list ->
            list.map { turn -> if (turn.id == turnId) transform(turn) else turn }
        }
    }

    class Factory(
        private val inferenceRouter: UnifiedInferenceRouter,
        private val preferencesRepository: EnginePreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AskViewModel(inferenceRouter, preferencesRepository) as T
    }
}
