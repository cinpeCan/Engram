package io.github.cinpecan.engram.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cinpecan.engram.data.preferences.EnginePreferences
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.ModelDescriptorDto
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.data.remote.ModelDownloadState
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.edgemnn.UnloadReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class EngineSheetUiState(
    val engineState: EngineState = EngineState.Unloaded,
    val nativeHeapMb: Double = 0.0,
    val catalog: List<ModelDescriptorDto> = emptyList(),
    val downloadStates: Map<String, ModelDownloadState> = emptyMap(),
    val selectedModelId: String = EnginePreferences.DEFAULT_MODEL_ID,
    val serverBaseUrl: String = EnginePreferences.DEFAULT_SERVER_BASE_URL,
    val statusNotice: String? = null,
    val isRefreshingCatalog: Boolean = false,
)

class EngineViewModel(
    private val edgeEngine: EdgeAiEngine,
    private val downloadManager: ModelDownloadManager,
    private val preferencesRepository: EnginePreferencesRepository,
) : ViewModel() {

    private val statusNoticeFlow = MutableStateFlow<String?>(null)
    private val refreshingCatalogFlow = MutableStateFlow(false)

    val uiState: StateFlow<EngineSheetUiState> = combine(
        edgeEngine.state,
        downloadManager.catalog,
        downloadManager.downloadStates,
        preferencesRepository.preferencesFlow,
        statusNoticeFlow,
        refreshingCatalogFlow,
    ) { values ->
        val engineState = values[0] as EngineState
        @Suppress("UNCHECKED_CAST")
        val catalog = values[1] as List<ModelDescriptorDto>
        @Suppress("UNCHECKED_CAST")
        val downloads = values[2] as Map<String, ModelDownloadState>
        val prefs = values[3] as EnginePreferences
        val notice = values[4] as String?
        val refreshing = values[5] as Boolean

        EngineSheetUiState(
            engineState = engineState,
            nativeHeapMb = edgeEngine.currentNativeHeapBytes() / (1024.0 * 1024.0),
            catalog = catalog,
            downloadStates = downloads,
            selectedModelId = prefs.selectedModelId,
            serverBaseUrl = prefs.serverBaseUrl,
            statusNotice = notice,
            isRefreshingCatalog = refreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = EngineSheetUiState(),
    )

    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            preferencesRepository.setServerBaseUrl(url)
            statusNoticeFlow.value = "已保存网关地址"
        }
    }

    fun refreshCatalog(serverUrl: String) {
        viewModelScope.launch {
            refreshingCatalogFlow.value = true
            val res = downloadManager.fetchCatalog(serverUrl)
            statusNoticeFlow.value = res.fold(
                onSuccess = { list -> "已同步同仓服务端清单（${list.size} 个模型）" },
                onFailure = { "网关未连接，当前使用内置模型清单" },
            )
            refreshingCatalogFlow.value = false
        }
    }

    fun toggleDownload(model: ModelDescriptorDto) {
        val current = downloadManager.downloadStates.value[model.id]
        if (current is ModelDownloadState.Downloading) {
            downloadManager.pause(model.id)
        } else {
            downloadManager.startOrResume(model)
        }
    }

    fun loadModel(model: ModelDescriptorDto) {
        viewModelScope.launch {
            val completed = downloadManager.downloadStates.value[model.id] as? ModelDownloadState.Completed
            val dir = completed?.localDirPath ?: ""
            val result = edgeEngine.loadModel(model.id, dir)
            if (result.isSuccess) {
                preferencesRepository.setSelectedModelId(model.id)
                statusNoticeFlow.value = if (dir.isNotEmpty()) {
                    "已加载本地量化权重：${model.name}"
                } else {
                    "已激活端侧 JNI 运行时会话（轻量模式）"
                }
            } else {
                statusNoticeFlow.value = "加载失败：${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun releaseMemoryNow() {
        viewModelScope.launch {
            val reclaimedBytes = edgeEngine.unload(UnloadReason.USER_REQUESTED)
            val mb = reclaimedBytes / (1024.0 * 1024.0)
            statusNoticeFlow.value = String.format("已卸载端侧会话，回收 Native 内存 %.1f MB", mb)
        }
    }

    fun clearNotice() {
        statusNoticeFlow.value = null
    }

    class Factory(
        private val edgeEngine: EdgeAiEngine,
        private val downloadManager: ModelDownloadManager,
        private val preferencesRepository: EnginePreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EngineViewModel(edgeEngine, downloadManager, preferencesRepository) as T
    }
}
