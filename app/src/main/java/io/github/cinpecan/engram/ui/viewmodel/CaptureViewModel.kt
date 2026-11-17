package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.domain.model.MemoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class CaptureUiState(
    val memories: List<MemoryItem> = emptyList(),
    val inputText: String = "",
    val isSubmitting: Boolean = false,
)

class CaptureViewModel(
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    private val inputTextFlow = MutableStateFlow("")
    private val submittingFlow = MutableStateFlow(false)

    val uiState: StateFlow<CaptureUiState> = combine(
        memoryRepository.memoriesFlow,
        inputTextFlow,
        submittingFlow,
    ) { memories, input, submitting ->
        CaptureUiState(
            memories = memories,
            inputText = input,
            isSubmitting = submitting,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = CaptureUiState(),
    )

    fun updateInput(text: String) {
        inputTextFlow.value = text
    }

    fun fillSample(sample: String) {
        inputTextFlow.value = sample
    }

    fun submitCapture() {
        val raw = inputTextFlow.value.trim()
        if (raw.isEmpty() || submittingFlow.value) return
        viewModelScope.launch {
            submittingFlow.value = true
            try {
                memoryRepository.captureMemory(raw)
                inputTextFlow.value = ""
            } finally {
                submittingFlow.value = false
            }
        }
    }

    fun markCalendarSaved(memoryId: String) {
        viewModelScope.launch {
            memoryRepository.markCalendarSaved(memoryId)
        }
    }

    class Factory(
        private val memoryRepository: MemoryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CaptureViewModel(memoryRepository) as T
    }
}
