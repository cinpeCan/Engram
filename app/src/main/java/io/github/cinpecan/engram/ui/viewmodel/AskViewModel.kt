package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AskIntent {
    data class SelectMode(val mode: InferenceMode) : AskIntent
    data class UpdateQuery(val query: String) : AskIntent
    data class SubmitQuery(val overrideQuery: String? = null) : AskIntent
}

@Immutable
data class AskConversationTurn(
    val id: String,
    val query: String,
    val citations: List<RagSearchResult> = emptyList(),
    val answerText: String = "",
    val isStreaming: Boolean = true,
    val telemetry: InferenceTelemetry? = null,
)

class AskViewModel(
    private val inferenceRouter: UnifiedInferenceRouter,
    private val preferencesRepository: EnginePreferencesRepository,
) : ViewModel() {

    val inferenceMode: StateFlow<InferenceMode> = preferencesRepository.preferencesFlow
        .map { it.inferenceMode }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = InferenceMode.OFFLINE_EDGE,
        )

    private val _turns = MutableStateFlow<List<AskConversationTurn>>(emptyList())
    val turns: StateFlow<List<AskConversationTurn>> = _turns.asStateFlow()

    private val _queryInput = MutableStateFlow("")
    val queryInput: StateFlow<String> = _queryInput.asStateFlow()

    private val _isAsking = MutableStateFlow(false)
    val isAsking: StateFlow<Boolean> = _isAsking.asStateFlow()

    private var activeAskJob: Job? = null

    fun onIntent(intent: AskIntent) {
        when (intent) {
            is AskIntent.SelectMode -> {
                viewModelScope.launch {
                    preferencesRepository.setInferenceMode(intent.mode)
                }
            }
            is AskIntent.UpdateQuery -> _queryInput.value = intent.query
            is AskIntent.SubmitQuery -> submitQuery(intent.overrideQuery)
        }
    }

    private fun submitQuery(overrideQuery: String? = null) {
        val raw = (overrideQuery ?: _queryInput.value).trim()
        if (raw.isEmpty()) return

        activeAskJob?.cancel()
        if (overrideQuery == null) {
            _queryInput.value = ""
        }

        val turnId = UUID.randomUUID().toString().take(8)
        _turns.update { current ->
            current + AskConversationTurn(id = turnId, query = raw, isStreaming = true)
        }

        activeAskJob = viewModelScope.launch {
            _isAsking.value = true
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
                _isAsking.value = false
            }
        }
    }

    private fun updateTurn(
        turnId: String,
        transform: (AskConversationTurn) -> AskConversationTurn,
    ) {
        _turns.update { list ->
            list.map { turn -> if (turn.id == turnId) transform(turn) else turn }
        }
    }
}
