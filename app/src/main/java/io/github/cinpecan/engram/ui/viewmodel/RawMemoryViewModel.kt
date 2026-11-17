package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.domain.model.MemoryItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Immutable
sealed interface RawMemoryUiState {
    data object Loading : RawMemoryUiState
    data class Ready(val memory: MemoryItem) : RawMemoryUiState
    data object NotFound : RawMemoryUiState
}

class RawMemoryViewModel(
    memoryId: String,
    memoryRepository: MemoryRepository,
) : ViewModel() {

    val uiState: StateFlow<RawMemoryUiState> = memoryRepository.observeMemoryById(memoryId)
        .map { item -> if (item != null) RawMemoryUiState.Ready(item) else RawMemoryUiState.NotFound }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = RawMemoryUiState.Loading,
        )
}
