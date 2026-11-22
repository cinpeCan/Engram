package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.preferences.EnginePreferences
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.data.repository.CloudSessionMonitor
import io.github.cinpecan.engram.data.repository.CloudSessionState
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class WorkspaceNoticeKind(val timeoutMs: Long) {
    INFO(2_500L),
    ENGINE_SETUP(3_500L),
}

enum class WorkspaceNoticeAction {
    OPEN_ENGINE_SHEET,
}

@Immutable
data class WorkspaceNoticeRequest(
    val message: String,
    val kind: WorkspaceNoticeKind = WorkspaceNoticeKind.INFO,
    val actionLabel: String? = null,
    val action: WorkspaceNoticeAction? = null,
)

@Immutable
data class WorkspaceNoticeUi(
    val id: Long,
    val message: String,
    val kind: WorkspaceNoticeKind,
    val actionLabel: String? = null,
    val action: WorkspaceNoticeAction? = null,
)

enum class TopBarDotTone {
    LOADING,
    GENERATING,
    ERROR,
    READY,
    IDLE,
}

@Immutable
data class TopBarStatusUi(
    val label: String = "On-device",
    val tone: TopBarDotTone = TopBarDotTone.IDLE,
)

sealed interface WorkspaceIntent {
    data class ShowNotice(val request: WorkspaceNoticeRequest) : WorkspaceIntent
    data object DismissNotice : WorkspaceIntent
    data object PerformNoticeAction : WorkspaceIntent
    data class OnOverlayPresenceChanged(val isOverlayOpen: Boolean) : WorkspaceIntent
}

sealed interface WorkspaceEffect {
    data object OpenEngineSheet : WorkspaceEffect
}

/**
 * Fine-grained MVI ViewModel managing root workspace status telemetry and time-bounded,
 * state-reactive transient notices without Android Accessibility indefinite timeout hijacking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModel(
    private val edgeEngine: EdgeAiEngine,
    private val preferencesRepository: EnginePreferencesRepository,
    private val cloudSessionMonitor: CloudSessionMonitor,
    private val downloadManager: ModelDownloadManager? = null,
    private val memoryRepository: MemoryRepository? = null,
) : ViewModel() {

    private val nextNoticeId = AtomicLong(0L)
    private val noticeRequests = MutableSharedFlow<WorkspaceNoticeUi?>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var latestActiveNotice: WorkspaceNoticeUi? = null

    private val effectChannel = Channel<WorkspaceEffect>(Channel.BUFFERED)
    val effects: Flow<WorkspaceEffect> = effectChannel.receiveAsFlow()

    init {
        if (memoryRepository != null) {
            viewModelScope.launch {
                memoryRepository.distillationNotices.collect { message ->
                    onIntent(WorkspaceIntent.ShowNotice(WorkspaceNoticeRequest(message = message)))
                }
            }
        }
    }

    val topBarStatus: StateFlow<TopBarStatusUi> = combine(
        edgeEngine.state,
        preferencesRepository.preferencesFlow
            .onStart { emit(EnginePreferences()) }
            .map { it.inferenceMode }
            .distinctUntilChanged(),
        cloudSessionMonitor.state,
    ) { engineState, inferenceMode, cloudSessionState ->
        resolveTopBarStatus(
            engineState = engineState,
            inferenceMode = inferenceMode,
            cloudSessionState = cloudSessionState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TopBarStatusUi(),
    )

    val activeNotice: StateFlow<WorkspaceNoticeUi?> = noticeRequests
        .flatMapLatest { notice ->
            latestActiveNotice = notice
            if (notice == null) {
                flowOf<WorkspaceNoticeUi?>(null)
            } else {
                flow {
                    emit(notice)
                    if (notice.kind == WorkspaceNoticeKind.ENGINE_SETUP) {
                        withTimeoutOrNull(notice.kind.timeoutMs) {
                            awaitReadinessShift()
                        }
                    } else {
                        delay(notice.kind.timeoutMs)
                    }
                    if (latestActiveNotice?.id == notice.id) {
                        latestActiveNotice = null
                    }
                    emit(null)
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = null,
        )

    fun onIntent(intent: WorkspaceIntent) {
        when (intent) {
            is WorkspaceIntent.ShowNotice -> {
                val request = intent.request
                noticeRequests.tryEmit(
                    WorkspaceNoticeUi(
                        id = nextNoticeId.incrementAndGet(),
                        message = request.message,
                        kind = request.kind,
                        actionLabel = request.actionLabel,
                        action = request.action,
                    ),
                )
            }
            WorkspaceIntent.DismissNotice -> {
                latestActiveNotice = null
                noticeRequests.tryEmit(null)
            }
            WorkspaceIntent.PerformNoticeAction -> {
                val target = latestActiveNotice
                latestActiveNotice = null
                noticeRequests.tryEmit(null)
                when (target?.action) {
                    WorkspaceNoticeAction.OPEN_ENGINE_SHEET -> {
                        effectChannel.trySend(WorkspaceEffect.OpenEngineSheet)
                    }
                    null -> Unit
                }
            }
            is WorkspaceIntent.OnOverlayPresenceChanged -> {
                if (intent.isOverlayOpen) {
                    latestActiveNotice = null
                    noticeRequests.tryEmit(null)
                }
            }
        }
    }

    private suspend fun awaitReadinessShift() {
        val downloadsFlow = downloadManager?.downloadStates ?: flowOf(emptyMap())
        val readinessFlow = combine(
            edgeEngine.state,
            preferencesRepository.preferencesFlow,
            cloudSessionMonitor.state,
            downloadsFlow,
        ) { engineState, prefs, cloudState, _ ->
            Triple(
                prefs.inferenceMode,
                isLocalUsable(engineState, prefs.selectedModelId),
                cloudState is CloudSessionState.Ready,
            )
        }.distinctUntilChanged()

        var baseline: Triple<InferenceMode, Boolean, Boolean>? = null
        readinessFlow.first { current ->
            val prev = baseline
            if (prev == null) {
                baseline = current
                false
            } else {
                current.first != prev.first ||
                    (!prev.second && current.second) ||
                    (!prev.third && current.third)
            }
        }
    }

    private fun isLocalUsable(
        engineState: EngineState,
        selectedModelId: String,
    ): Boolean {
        if (engineState is EngineState.Ready ||
            engineState is EngineState.Generating ||
            engineState is EngineState.Loading
        ) {
            return true
        }
        return edgeEngine.isBackendAvailable &&
            downloadManager?.resolveReadyModelDirPath(selectedModelId) != null
    }

    companion object {
        internal fun resolveTopBarStatus(
            engineState: EngineState,
            inferenceMode: InferenceMode,
            cloudSessionState: CloudSessionState,
        ): TopBarStatusUi {
            val tone = when {
                engineState is EngineState.Loading || engineState is EngineState.Releasing -> TopBarDotTone.LOADING
                engineState is EngineState.Generating -> TopBarDotTone.GENERATING
                engineState is EngineState.Error -> TopBarDotTone.ERROR
                inferenceMode == InferenceMode.CLOUD_COLLAB -> when (cloudSessionState) {
                    is CloudSessionState.Ready -> TopBarDotTone.READY
                    CloudSessionState.Verifying -> TopBarDotTone.LOADING
                    CloudSessionState.IdleUnconfigured,
                    CloudSessionState.InvalidToken,
                    CloudSessionState.QuotaExhausted,
                    CloudSessionState.Unreachable,
                    -> TopBarDotTone.IDLE
                }
                engineState is EngineState.Ready -> TopBarDotTone.READY
                else -> TopBarDotTone.IDLE
            }
            val label = when {
                engineState is EngineState.Loading -> "Loading model\u2026"
                engineState is EngineState.Generating -> "Running on-device\u2026"
                engineState is EngineState.Releasing -> "Freeing memory\u2026"
                inferenceMode == InferenceMode.CLOUD_COLLAB -> when (cloudSessionState) {
                    is CloudSessionState.Ready -> "Cloud ready"
                    CloudSessionState.Verifying -> "Verifying\u2026"
                    CloudSessionState.IdleUnconfigured,
                    CloudSessionState.InvalidToken,
                    CloudSessionState.QuotaExhausted,
                    CloudSessionState.Unreachable,
                    -> "Cloud offline"
                }
                engineState is EngineState.Ready -> "Qwen 0.5B \u00B7 Ready"
                else -> "On-device"
            }
            return TopBarStatusUi(label = label, tone = tone)
        }
    }
}
