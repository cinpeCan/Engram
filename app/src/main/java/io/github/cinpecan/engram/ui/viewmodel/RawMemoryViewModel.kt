package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.domain.model.MemoryItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface RawMemoryIntent {
    data object OnSourceCopied : RawMemoryIntent
}

@Immutable
sealed interface RawMemoryUiState {
    data object Loading : RawMemoryUiState
    data class Ready(val memory: MemoryItem) : RawMemoryUiState
    data object NotFound : RawMemoryUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class RawMemoryViewModel(
    memoryId: String,
    memoryRepository: MemoryRepository,
) : ViewModel() {

    private val copySignals = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val uiState: StateFlow<RawMemoryUiState> = memoryRepository.observeMemoryById(memoryId)
        .map { item -> if (item != null) RawMemoryUiState.Ready(item) else RawMemoryUiState.NotFound }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = RawMemoryUiState.Loading,
        )

    val isCopied: StateFlow<Boolean> = copySignals
        .flatMapLatest {
            flow {
                emit(true)
                delay(COPY_CONFIRM_MS)
                emit(false)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = false,
        )

    fun onIntent(intent: RawMemoryIntent) {
        when (intent) {
            RawMemoryIntent.OnSourceCopied -> copySignals.tryEmit(Unit)
        }
    }

    companion object {
        const val COPY_CONFIRM_MS: Long = 1_800L
    }
}
