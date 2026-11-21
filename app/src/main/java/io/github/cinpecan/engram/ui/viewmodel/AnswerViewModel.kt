package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.repository.AnswerEvent
import io.github.cinpecan.engram.data.repository.AnswerRepository
import io.github.cinpecan.engram.domain.rag.RetrievalResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AnswerIntent {
    data object Retry : AnswerIntent
}

@Immutable
data class AnswerStatus(
    val isWorking: Boolean = true,
    val stage: String? = null,
    val badge: String? = null,
    val notice: String? = null,
)

/**
 * Fine-grained stream MVI for the Ask sheet: retrieval (citations + reasoning subgraph), the
 * streaming answer text, and status are separate streams so token updates never recompose the graph.
 */
class AnswerViewModel(
    val question: String,
    private val answerRepository: AnswerRepository,
) : ViewModel() {

    private val _retrieval = MutableStateFlow<RetrievalResult?>(null)
    val retrieval: StateFlow<RetrievalResult?> = _retrieval.asStateFlow()

    private val _answerText = MutableStateFlow("")
    val answerText: StateFlow<String> = _answerText.asStateFlow()

    private val _status = MutableStateFlow(AnswerStatus(stage = STAGE_RETRIEVING))
    val status: StateFlow<AnswerStatus> = _status.asStateFlow()

    private var askJob: Job? = null

    init {
        ask()
    }

    fun onIntent(intent: AnswerIntent) {
        when (intent) {
            AnswerIntent.Retry -> ask()
        }
    }

    private fun ask() {
        askJob?.cancel()
        _answerText.value = ""
        _status.value = AnswerStatus(stage = STAGE_RETRIEVING)
        askJob = viewModelScope.launch {
            try {
                answerRepository.ask(question).collect { event ->
                    when (event) {
                        is AnswerEvent.Retrieved -> _retrieval.value = event.result
                        is AnswerEvent.Streaming -> {
                            _answerText.value = event.textSoFar
                            _status.value = AnswerStatus(isWorking = true, stage = event.stage)
                        }
                        is AnswerEvent.Completed -> {
                            _answerText.value = event.text
                            _status.value = AnswerStatus(isWorking = false, badge = event.badge)
                        }
                        is AnswerEvent.RetrievalOnly -> {
                            _answerText.value = ""
                            _status.value = AnswerStatus(isWorking = false, notice = event.reason)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                _status.value = AnswerStatus(isWorking = false, notice = NOTICE_FAILED)
            }
        }
    }

    companion object {
        const val STAGE_RETRIEVING = "Searching your memories…"
        const val NOTICE_FAILED = "Something went wrong · Tap retry"
    }
}
