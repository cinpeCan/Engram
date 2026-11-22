package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.data.remote.ModelDownloadState
import io.github.cinpecan.engram.data.repository.CloudSessionMonitor
import io.github.cinpecan.engram.data.repository.CloudSessionState
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.model.StreamingPreviewState
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class CaptureUiState(
    val memories: List<MemoryItem> = emptyList(),
    val streamingPreviews: Map<String, StreamingPreviewState> = emptyMap(),
    val inputText: String = "",
    val isSubmitting: Boolean = false,
    val activeDeleteBubbleMemoryId: String? = null,
)

sealed interface CaptureIntent {
    data class UpdateInput(val text: String) : CaptureIntent
    data class FillSample(val sample: String) : CaptureIntent
    data object SubmitCapture : CaptureIntent
    data class SetDeleteBubbleMemory(val memoryId: String?) : CaptureIntent
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
}

sealed interface CaptureEffect {
    data class OpenCalendarInsert(val memoryId: String, val proposal: CalendarProposal) : CaptureEffect
    data class OpenCalendarEvent(val eventId: Long, val proposal: CalendarProposal) : CaptureEffect
    data class ShowMessage(val message: String) : CaptureEffect
    data class ShowEngineNotice(val message: String, val actionLabel: String? = null) : CaptureEffect
    data object DismissNotice : CaptureEffect
    data class ScrollToTop(val memoryId: String) : CaptureEffect
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
    private val edgeEngine: EdgeAiEngine? = null,
    private val preferencesRepository: EnginePreferencesRepository? = null,
    private val cloudSessionMonitor: CloudSessionMonitor? = null,
    private val downloadManager: ModelDownloadManager? = null,
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

    private val _activeDeleteBubbleMemoryId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<CaptureUiState> = combine(
        memories,
        streamingPreviews,
        _inputText,
        _isSubmitting,
        _activeDeleteBubbleMemoryId,
    ) { list, previews, input, submitting, deleteBubbleId ->
        CaptureUiState(
            memories = list,
            streamingPreviews = previews,
            inputText = input,
            isSubmitting = submitting,
            activeDeleteBubbleMemoryId = deleteBubbleId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = CaptureUiState(),
    )

    private val effectChannel = Channel<CaptureEffect>(Channel.BUFFERED)
    val effects: Flow<CaptureEffect> = effectChannel.receiveAsFlow()

    fun onIntent(intent: CaptureIntent) {
        when (intent) {
            is CaptureIntent.UpdateInput -> _inputText.value = intent.text
            is CaptureIntent.FillSample -> _inputText.value = intent.sample
            CaptureIntent.SubmitCapture -> submitCapture()
            is CaptureIntent.SetDeleteBubbleMemory -> _activeDeleteBubbleMemoryId.value = intent.memoryId
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
        }
    }

    private fun deleteMemory(memory: MemoryItem) {
        _activeDeleteBubbleMemoryId.value = null
        viewModelScope.launch {
            memoryRepository.deleteMemory(memory.id)
        }
    }

    private fun submitCapture() {
        val raw = _inputText.value.trim()
        if (raw.isEmpty() || _isSubmitting.value) return
        val isAsk = isAskQuery(raw)
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                var skipCloudAttempt = false
                var emittedPreFlightNotice = false
                if (preferencesRepository != null && edgeEngine != null) {
                    val prefs = preferencesRepository.preferencesFlow.first()
                    val engineState = edgeEngine.state.value
                    val localReadyOrLoading = engineState is EngineState.Ready ||
                        engineState is EngineState.Generating ||
                        engineState is EngineState.Loading
                    val localDownloadedDir = if (edgeEngine.isBackendAvailable) {
                        downloadManager?.resolveReadyModelDirPath(prefs.selectedModelId)
                    } else {
                        null
                    }
                    if (!localReadyOrLoading && localDownloadedDir != null) {
                        edgeEngine.configureDefaultModel(prefs.selectedModelId, localDownloadedDir)
                    }

                    when (prefs.inferenceMode) {
                        InferenceMode.OFFLINE_EDGE -> {
                            if (!localReadyOrLoading && localDownloadedDir == null && !isAsk) {
                                val isDownloading = downloadManager
                                    ?.downloadStates
                                    ?.value
                                    ?.get(prefs.selectedModelId) is ModelDownloadState.Downloading
                                val notice = when {
                                    !edgeEngine.isBackendAvailable -> CaptureEffect.ShowEngineNotice(
                                        message = "On-device runtime unavailable \u00B7 Switch to Cloud",
                                        actionLabel = "Settings",
                                    )
                                    isDownloading -> CaptureEffect.ShowEngineNotice(
                                        message = "Model download in progress\u2026",
                                        actionLabel = "Open",
                                    )
                                    else -> CaptureEffect.ShowEngineNotice(
                                        message = "Download the on-device model first",
                                        actionLabel = "Open",
                                    )
                                }
                                effectChannel.send(notice)
                                return@launch
                            }
                        }
                        InferenceMode.CLOUD_COLLAB -> {
                            val cloudState = cloudSessionMonitor?.state?.value
                            val passesFilter = CloudSessionMonitor.passesLengthFilter(
                                serverBaseUrl = prefs.serverBaseUrl,
                                syncKey = prefs.syncKey,
                            )
                            val cloudReadyOrVerifying = passesFilter && (
                                cloudState == null ||
                                    cloudState is CloudSessionState.Ready ||
                                    cloudState is CloudSessionState.Verifying ||
                                    cloudState is CloudSessionState.IdleUnconfigured
                                )
                            if (!cloudReadyOrVerifying) {
                                val cloudReason = when {
                                    !passesFilter || cloudState is CloudSessionState.IdleUnconfigured ->
                                        "Cloud not configured"
                                    cloudState is CloudSessionState.InvalidToken ->
                                        "Cloud access token invalid"
                                    cloudState is CloudSessionState.QuotaExhausted ->
                                        "Cloud quota reached"
                                    else -> "Cloud offline"
                                }
                                when {
                                    localReadyOrLoading -> {
                                        skipCloudAttempt = true
                                        emittedPreFlightNotice = true
                                        effectChannel.send(
                                            CaptureEffect.ShowMessage("$cloudReason \u00B7 Using on-device model"),
                                        )
                                    }
                                    localDownloadedDir != null -> {
                                        edgeEngine.configureDefaultModel(prefs.selectedModelId, localDownloadedDir)
                                        skipCloudAttempt = true
                                        emittedPreFlightNotice = true
                                        effectChannel.send(
                                            CaptureEffect.ShowMessage("$cloudReason \u00B7 Using on-device model"),
                                        )
                                    }
                                    !isAsk -> {
                                        effectChannel.send(
                                            CaptureEffect.ShowEngineNotice(
                                                message = "$cloudReason \u00B7 Set up Cloud or download model",
                                                actionLabel = "Open",
                                            ),
                                        )
                                        return@launch
                                    }
                                }
                            }
                        }
                    }
                }

                if (isAsk) {
                    if (!emittedPreFlightNotice) {
                        effectChannel.send(CaptureEffect.DismissNotice)
                    }
                    _inputText.value = ""
                    effectChannel.send(CaptureEffect.OpenAnswer(raw))
                } else {
                    val created = memoryRepository.captureMemory(
                        rawText = raw,
                        skipCloudAttempt = skipCloudAttempt,
                    )
                    if (created != null) {
                        if (!emittedPreFlightNotice) {
                            effectChannel.send(CaptureEffect.DismissNotice)
                        }
                        _inputText.value = ""
                        effectChannel.send(CaptureEffect.ScrollToTop(created.id))
                    }
                }
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
