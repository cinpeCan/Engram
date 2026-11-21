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
    data class RequestCalendarAction(
        val memoryId: String,
        val proposal: CalendarProposal,
        val existingEventId: Long?,
    ) : CaptureIntent

    data class OnCalendarPermissionResult(val granted: Boolean) : CaptureIntent
    data class OnCalendarActionLaunched(
        val title: String,
        val launched: Boolean,
        val openedExisting: Boolean,
        val hasReadPermission: Boolean,
    ) : CaptureIntent

    data class DeleteMemory(val memory: MemoryItem) : CaptureIntent
    data object UndoDeleteMemory : CaptureIntent
}

sealed interface CaptureEffect {
    data class OpenCalendarInsert(val memoryId: String, val proposal: CalendarProposal) : CaptureEffect
    data class OpenCalendarEvent(val eventId: Long, val proposal: CalendarProposal) : CaptureEffect
    data class ShowMessage(val message: String) : CaptureEffect
    data class ShowUndoDelete(val message: String) : CaptureEffect
    data class OpenAnswer(val question: String) : CaptureEffect
}

/** A trailing question mark (ASCII or full-width) turns the composer into "Ask your memories". */
fun isAskQuery(text: String): Boolean {
    val trimmed = text.trimEnd()
    if (trimmed.length < 2) return false
    val last = trimmed.last()
    return last == '?' || last == '\uFF1F'
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

    private var lastDeletedMemory: MemoryItem? = null

    fun onIntent(intent: CaptureIntent) {
        when (intent) {
            is CaptureIntent.UpdateInput -> _inputText.value = intent.text
            is CaptureIntent.FillSample -> _inputText.value = intent.sample
            CaptureIntent.SubmitCapture -> submitCapture()
            is CaptureIntent.RequestCalendarAction -> {
                if (intent.existingEventId != null) {
                    effectChannel.trySend(
                        CaptureEffect.OpenCalendarEvent(
                            eventId = intent.existingEventId,
                            proposal = intent.proposal,
                        ),
                    )
                } else {
                    effectChannel.trySend(
                        CaptureEffect.OpenCalendarInsert(
                            memoryId = intent.memoryId,
                            proposal = intent.proposal,
                        ),
                    )
                }
            }
            is CaptureIntent.OnCalendarPermissionResult -> {
                memoryRepository.notifyCalendarPermissionChanged()
            }
            is CaptureIntent.OnCalendarActionLaunched -> handleCalendarActionLaunched(intent)
            is CaptureIntent.DeleteMemory -> deleteMemory(intent.memory)
            CaptureIntent.UndoDeleteMemory -> undoDeleteMemory()
        }
    }

    private fun deleteMemory(memory: MemoryItem) {
        lastDeletedMemory = memory
        viewModelScope.launch {
            memoryRepository.deleteMemory(memory.id)
            effectChannel.send(CaptureEffect.ShowUndoDelete("Memory deleted"))
        }
    }

    private fun undoDeleteMemory() {
        val toRestore = lastDeletedMemory ?: return
        lastDeletedMemory = null
        viewModelScope.launch {
            memoryRepository.restoreMemory(toRestore)
        }
    }

    private fun submitCapture() {
        val raw = _inputText.value.trim()
        if (raw.isEmpty() || _isSubmitting.value) return
        if (isAskQuery(raw)) {
            _inputText.value = ""
            effectChannel.trySend(CaptureEffect.OpenAnswer(raw))
            return
        }
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

    private fun handleCalendarActionLaunched(result: CaptureIntent.OnCalendarActionLaunched) {
        viewModelScope.launch {
            if (!result.launched) {
                effectChannel.send(CaptureEffect.ShowMessage("No calendar app found"))
            } else if (!result.openedExisting && !result.hasReadPermission) {
                effectChannel.send(
                    CaptureEffect.ShowMessage("Opened a calendar draft (grant calendar access to track the saved state)"),
                )
            }
        }
    }
}
