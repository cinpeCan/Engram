package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.preferences.EnginePreferences
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.CloudGatewayHttpException
import io.github.cinpecan.engram.data.remote.ModelDescriptorDto
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.data.remote.ModelDownloadState
import io.github.cinpecan.engram.data.repository.CloudSessionMonitor
import io.github.cinpecan.engram.data.repository.CloudSessionState
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.edgemnn.UnloadReason
import java.util.Locale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface EngineIntent {
    data class SelectMode(val mode: InferenceMode) : EngineIntent
    data class UpdateServerUrlInput(val url: String) : EngineIntent
    data class UpdateSyncKeyInput(val syncKey: String) : EngineIntent
    data object RetryCloudVerification : EngineIntent
    data object SyncMemories : EngineIntent
    data class ToggleDownload(val model: ModelDescriptorDto) : EngineIntent
    data class LoadModel(val model: ModelDescriptorDto) : EngineIntent
    data object ReleaseMemoryNow : EngineIntent
}

sealed interface EngineEffect {
    data class ShowMessage(val message: String) : EngineEffect
}

@Immutable
data class EngineRuntimeStatus(
    val engineState: EngineState = EngineState.Unloaded,
    val residentMemoryMb: Double = 0.0,
    val isBackendAvailable: Boolean = false,
)

@Immutable
data class ModelCatalogItemUi(
    val model: ModelDescriptorDto,
    val downloadState: ModelDownloadState,
    val isSelected: Boolean,
)

class EngineViewModel(
    private val edgeEngine: EdgeAiEngine,
    private val downloadManager: ModelDownloadManager,
    private val preferencesRepository: EnginePreferencesRepository,
    private val memoryRepository: MemoryRepository,
    private val cloudSessionMonitor: CloudSessionMonitor? = null,
) : ViewModel() {

    private val serverUrlInputFlow = MutableStateFlow(EnginePreferences.DEFAULT_SERVER_BASE_URL)
    val serverUrlInput: StateFlow<String> = serverUrlInputFlow.asStateFlow()

    private val syncKeyInputFlow = MutableStateFlow(EnginePreferences.DEFAULT_SYNC_KEY)
    val syncKeyInput: StateFlow<String> = syncKeyInputFlow.asStateFlow()

    private val isSyncingFlow = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = isSyncingFlow.asStateFlow()

    val cloudSessionState: StateFlow<CloudSessionState> =
        cloudSessionMonitor?.state ?: MutableStateFlow<CloudSessionState>(CloudSessionState.IdleUnconfigured).asStateFlow()

    private var hasUserEditedServerUrl = false
    private var hasUserEditedSyncKey = false

    private val effectChannel = Channel<EngineEffect>(Channel.BUFFERED)
    val effects: Flow<EngineEffect> = effectChannel.receiveAsFlow()

    val inferenceMode: StateFlow<InferenceMode> = preferencesRepository.preferencesFlow
        .map { it.inferenceMode }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = InferenceMode.OFFLINE_EDGE,
        )

    val runtimeStatus: StateFlow<EngineRuntimeStatus> = edgeEngine.state
        .map { state ->
            EngineRuntimeStatus(
                engineState = state,
                residentMemoryMb = edgeEngine.currentMemoryBytes() / (1024.0 * 1024.0),
                isBackendAvailable = edgeEngine.isBackendAvailable,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = EngineRuntimeStatus(isBackendAvailable = edgeEngine.isBackendAvailable),
        )

    val catalogItems: StateFlow<List<ModelCatalogItemUi>> = combine(
        downloadManager.catalog,
        downloadManager.downloadStates,
        preferencesRepository.preferencesFlow.map { it.selectedModelId }.distinctUntilChanged(),
    ) { catalog, downloads, selectedId ->
        catalog.map { model ->
            ModelCatalogItemUi(
                model = model,
                downloadState = downloads[model.id] ?: ModelDownloadState.Idle,
                isSelected = selectedId == model.id,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList(),
    )

    init {
        viewModelScope.launch {
            val prefs = preferencesRepository.preferencesFlow.first()
            if (!hasUserEditedServerUrl) {
                serverUrlInputFlow.value = prefs.serverBaseUrl
            }
            if (!hasUserEditedSyncKey) {
                syncKeyInputFlow.value = prefs.syncKey
            }
        }
        viewModelScope.launch {
            downloadManager.downloadStates.collect { states ->
                states.forEach { (modelId, dlState) ->
                    if (dlState is ModelDownloadState.Completed) {
                        edgeEngine.configureDefaultModel(modelId, dlState.localDirPath)
                    }
                }
            }
        }
    }

    fun onIntent(intent: EngineIntent) {
        when (intent) {
            is EngineIntent.SelectMode -> {
                viewModelScope.launch {
                    preferencesRepository.setInferenceMode(intent.mode)
                }
            }
            is EngineIntent.UpdateServerUrlInput -> {
                hasUserEditedServerUrl = true
                serverUrlInputFlow.value = intent.url
                cloudSessionMonitor?.onConfigEdited(intent.url, syncKeyInputFlow.value)
                viewModelScope.launch {
                    preferencesRepository.setServerBaseUrl(intent.url)
                }
            }
            is EngineIntent.UpdateSyncKeyInput -> {
                hasUserEditedSyncKey = true
                syncKeyInputFlow.value = intent.syncKey
                cloudSessionMonitor?.onConfigEdited(serverUrlInputFlow.value, intent.syncKey)
                viewModelScope.launch {
                    preferencesRepository.setSyncKey(intent.syncKey)
                }
            }
            EngineIntent.RetryCloudVerification -> cloudSessionMonitor?.retryVerification()
            EngineIntent.SyncMemories -> syncMemories()
            is EngineIntent.ToggleDownload -> toggleDownload(intent.model)
            is EngineIntent.LoadModel -> loadModel(intent.model)
            EngineIntent.ReleaseMemoryNow -> releaseMemoryNow()
        }
    }

    private fun syncMemories() {
        val normalizedUrl = serverUrlInputFlow.value.trim().trimEnd('/')
        val normalizedKey = syncKeyInputFlow.value.trim()

        viewModelScope.launch {
            isSyncingFlow.value = true
            try {
                preferencesRepository.updateSyncConfig(normalizedUrl, normalizedKey)
                serverUrlInputFlow.value = normalizedUrl
                syncKeyInputFlow.value = normalizedKey
                cloudSessionMonitor?.onConfigEdited(normalizedUrl, normalizedKey)

                val result = memoryRepository.syncWithServer(normalizedUrl, normalizedKey)
                cloudSessionMonitor?.retryVerification()
                val msg = result.fold(
                    onSuccess = { summary ->
                        "同步完成 · 拉取 ${summary.pulledCount} 条更新，云端共 ${summary.totalRemoteCount} 条"
                    },
                    onFailure = { err ->
                        when (err) {
                            is CloudGatewayHttpException -> when (err.statusCode) {
                                401 -> "已保存配置 · 需填写有效体验码，或配置自定义服务器地址"
                                429 -> "已保存配置 · 云端体验额度已达上限"
                                else -> "已保存配置 · 同步服务返回异常（HTTP ${err.statusCode}）"
                            }
                            else -> "已保存配置，暂未连上同步服务，请检查网络或服务器地址"
                        }
                    },
                )
                effectChannel.send(EngineEffect.ShowMessage(msg))
            } finally {
                isSyncingFlow.value = false
            }
        }
    }

    private fun toggleDownload(model: ModelDescriptorDto) {
        val current = downloadManager.downloadStates.value[model.id]
        if (current is ModelDownloadState.Downloading) {
            downloadManager.pause(model.id)
        } else {
            downloadManager.startOrResume(model)
        }
    }

    private fun loadModel(model: ModelDescriptorDto) {
        val completed = downloadManager.downloadStates.value[model.id] as? ModelDownloadState.Completed
        if (completed == null) {
            effectChannel.trySend(EngineEffect.ShowMessage("请先下载模型文件"))
            return
        }
        if (!edgeEngine.isBackendAvailable) {
            effectChannel.trySend(EngineEffect.ShowMessage("当前运行环境未启用原生推理库"))
            return
        }
        viewModelScope.launch {
            val result = edgeEngine.loadModel(model.id, completed.localDirPath)
            val msg = if (result.isSuccess) {
                preferencesRepository.setSelectedModelId(model.id)
                "模型已加载就绪"
            } else {
                "加载失败：${result.exceptionOrNull()?.message}"
            }
            effectChannel.send(EngineEffect.ShowMessage(msg))
        }
    }

    private fun releaseMemoryNow() {
        val current = edgeEngine.state.value
        if (current !is EngineState.Ready && current !is EngineState.Generating) {
            return
        }
        viewModelScope.launch {
            val reclaimedBytes = edgeEngine.unload(UnloadReason.USER_REQUESTED)
            val mb = reclaimedBytes / (1024.0 * 1024.0)
            effectChannel.send(
                EngineEffect.ShowMessage(
                    String.format(Locale.US, "已释放模型内存 %.1f MB", mb),
                ),
            )
        }
    }
}
