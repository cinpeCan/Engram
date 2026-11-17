package io.github.cinpecan.engram.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.model.StreamingPreviewState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface CaptureIntent {
    data class UpdateInput(val text: String) : CaptureIntent
    data class FillSample(val sample: String) : CaptureIntent
    data object SubmitCapture : CaptureIntent
    data class RequestAddCalendar(val memoryId: String, val proposal: CalendarProposal) : CaptureIntent
    data class ConfirmCalendarLaunchResult(
        val memoryId: String,
        val title: String,
        val launched: Boolean,
    ) : CaptureIntent
}

sealed interface CaptureEffect {
    data class LaunchCalendarInsert(val memoryId: String, val proposal: CalendarProposal) : CaptureEffect
    data class ShowMessage(val message: String) : CaptureEffect
}

class CaptureViewModel(
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    val memories: StateFlow<List<MemoryItem>> = memoryRepository.observeMemories()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    val streamingPreviews: StateFlow<Map<String, StreamingPreviewState>> =
        memoryRepository.streamingPreviews

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val effectChannel = Channel<CaptureEffect>(Channel.BUFFERED)
    val effects: Flow<CaptureEffect> = effectChannel.receiveAsFlow()

    fun onIntent(intent: CaptureIntent) {
        when (intent) {
            is CaptureIntent.UpdateInput -> _inputText.value = intent.text
            is CaptureIntent.FillSample -> _inputText.value = intent.sample
            CaptureIntent.SubmitCapture -> submitCapture()
            is CaptureIntent.RequestAddCalendar -> {
                effectChannel.trySend(
                    CaptureEffect.LaunchCalendarInsert(
                        memoryId = intent.memoryId,
                        proposal = intent.proposal,
                    ),
                )
            }
            is CaptureIntent.ConfirmCalendarLaunchResult -> handleCalendarLaunchResult(intent)
        }
    }

    private fun submitCapture() {
        val raw = _inputText.value.trim()
        if (raw.isEmpty() || _isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                memoryRepository.captureMemory(raw)
                _inputText.value = ""
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    private fun handleCalendarLaunchResult(result: CaptureIntent.ConfirmCalendarLaunchResult) {
        viewModelScope.launch {
            if (result.launched) {
                memoryRepository.markCalendarSaved(result.memoryId)
                effectChannel.send(CaptureEffect.ShowMessage("已拉起系统日历草稿：${result.title}"))
            } else {
                effectChannel.send(CaptureEffect.ShowMessage("未检测到可用的系统日历应用"))
            }
        }
    }
}
