package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.preferences.EnginePreferences
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.ModelDescriptorDto
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.data.remote.ModelDownloadState
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
    data object SyncCatalog : EngineIntent
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
) : ViewModel() {

    private val serverUrlInputFlow = MutableStateFlow(EnginePreferences.DEFAULT_SERVER_BASE_URL)
    val serverUrlInput: StateFlow<String> = serverUrlInputFlow.asStateFlow()

    private val isRefreshingFlow = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = isRefreshingFlow.asStateFlow()

    private var hasUserEditedServerUrl = false

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
            val savedUrl = preferencesRepository.preferencesFlow.first().serverBaseUrl
            if (!hasUserEditedServerUrl) {
                serverUrlInputFlow.value = savedUrl
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
            }
            EngineIntent.SyncCatalog -> syncCatalog()
            is EngineIntent.ToggleDownload -> toggleDownload(intent.model)
            is EngineIntent.LoadModel -> loadModel(intent.model)
            EngineIntent.ReleaseMemoryNow -> releaseMemoryNow()
        }
    }

    private fun syncCatalog() {
        val normalizedUrl = serverUrlInputFlow.value.trim().trimEnd('/')
            .ifEmpty { EnginePreferences.DEFAULT_SERVER_BASE_URL }
        viewModelScope.launch {
            isRefreshingFlow.value = true
            try {
                preferencesRepository.setServerBaseUrl(normalizedUrl)
                serverUrlInputFlow.value = normalizedUrl
                val res = downloadManager.fetchCatalog(normalizedUrl)
                val msg = res.fold(
                    onSuccess = { list -> "已保存网关地址并同步清单（${list.size} 个模型）" },
                    onFailure = { "已保存网关地址；网关未连接，当前使用内置模型清单" },
                )
                effectChannel.send(EngineEffect.ShowMessage(msg))
            } finally {
                isRefreshingFlow.value = false
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
            effectChannel.trySend(EngineEffect.ShowMessage("请先完整下载模型权重后再加载"))
            return
        }
        if (!edgeEngine.isBackendAvailable) {
            effectChannel.trySend(EngineEffect.ShowMessage("当前安装包未内置 MNN 原生库，无法加载权重"))
            return
        }
        viewModelScope.launch {
            val result = edgeEngine.loadModel(model.id, completed.localDirPath)
            val msg = if (result.isSuccess) {
                preferencesRepository.setSelectedModelId(model.id)
                "已加载本地量化权重：${model.name}"
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
                    String.format(Locale.US, "已卸载端侧会话，回收内存 %.1f MB", mb),
                ),
            )
        }
    }
}
