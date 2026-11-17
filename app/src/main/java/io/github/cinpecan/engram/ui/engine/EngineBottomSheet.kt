package io.github.cinpecan.engram.ui.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cinpecan.engram.data.remote.ModelDescriptorDto
import io.github.cinpecan.engram.data.remote.ModelDownloadState
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.ui.viewmodel.EngineEffect
import io.github.cinpecan.engram.ui.viewmodel.EngineIntent
import io.github.cinpecan.engram.ui.viewmodel.EngineRuntimeStatus
import io.github.cinpecan.engram.ui.viewmodel.EngineViewModel
import io.github.cinpecan.engram.ui.viewmodel.ModelCatalogItemUi
import java.util.Locale
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun EngineBottomSheetRoute(
    modifier: Modifier = Modifier,
    viewModel: EngineViewModel = koinViewModel(),
) {
    val inferenceMode by viewModel.inferenceMode.collectAsStateWithLifecycle()
    val runtimeStatus by viewModel.runtimeStatus.collectAsStateWithLifecycle()
    val serverUrlInput by viewModel.serverUrlInput.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val catalogItems by viewModel.catalogItems.collectAsStateWithLifecycle()
    var latestNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is EngineEffect.ShowMessage -> latestNotice = effect.message
            }
        }
    }

    EngineBottomSheetContent(
        inferenceMode = inferenceMode,
        runtimeStatus = runtimeStatus,
        serverUrlInput = serverUrlInput,
        isRefreshing = isRefreshing,
        catalogItems = catalogItems,
        latestNotice = latestNotice,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

@Composable
fun EngineBottomSheetContent(
    inferenceMode: InferenceMode,
    runtimeStatus: EngineRuntimeStatus,
    serverUrlInput: String,
    isRefreshing: Boolean,
    catalogItems: List<ModelCatalogItemUi>,
    latestNotice: String?,
    onIntent: (EngineIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canRelease = runtimeStatus.engineState is EngineState.Ready ||
        runtimeStatus.engineState is EngineState.Generating

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = "端云推理与模型仓",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        // Edge MNN vs Cloud SSE Mode Switch
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = inferenceMode == InferenceMode.OFFLINE_EDGE,
                onClick = { onIntent(EngineIntent.SelectMode(InferenceMode.OFFLINE_EDGE)) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.AirplanemodeActive,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            ) {
                Text("纯离线 MNN")
            }
            SegmentedButton(
                selected = inferenceMode == InferenceMode.CLOUD_COLLAB,
                onClick = { onIntent(EngineIntent.SelectMode(InferenceMode.CLOUD_COLLAB)) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.CloudQueue,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            ) {
                Text("云端协同 SSE")
            }
        }

        // Honest Native Memory & Backend Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "会话：${describeEngineState(runtimeStatus.engineState)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = String.format(Locale.US, "RSS %.1f MB", runtimeStatus.residentMemoryMb),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Text(
                    text = if (runtimeStatus.isBackendAvailable) {
                        "MNN 原生库：已链接 (arm64-v8a)"
                    } else {
                        "MNN 原生库：未内置（未加载前将诚实走本地规则兜底或云端 SSE）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val readyState = runtimeStatus.engineState as? EngineState.Ready
                if (readyState != null && readyState.modelFootprintBytes > 0L) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "当前模型常驻增量：+%.1f MB",
                            readyState.modelFootprintBytes / (1024.0 * 1024.0),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }

                latestNotice?.let { notice ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }

                FilledTonalButton(
                    onClick = { onIntent(EngineIntent.ReleaseMemoryNow) },
                    enabled = canRelease,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CleaningServices,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("立即释放引擎内存 (Unload Session)")
                }
            }
        }

        // Same-repo Gateway Manifest Sync
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = serverUrlInput,
                onValueChange = { onIntent(EngineIntent.UpdateServerUrlInput(it)) },
                label = { Text("同仓 FastAPI 网关地址") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onIntent(EngineIntent.SyncCatalog) },
                enabled = !isRefreshing,
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "同步模型清单")
            }
        }

        // Single Qwen2.5-0.5B Model Entry
        catalogItems.forEach { item ->
            ModelCatalogRow(
                model = item.model,
                downloadState = item.downloadState,
                isBackendAvailable = runtimeStatus.isBackendAvailable,
                isLoaded = (runtimeStatus.engineState as? EngineState.Ready)?.modelId == item.model.id,
                onToggleDownload = { onIntent(EngineIntent.ToggleDownload(item.model)) },
                onLoad = { onIntent(EngineIntent.LoadModel(item.model)) },
            )
        }

        Spacer(modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ModelCatalogRow(
    model: ModelDescriptorDto,
    downloadState: ModelDownloadState,
    isBackendAvailable: Boolean,
    isLoaded: Boolean,
    onToggleDownload: () -> Unit,
    onLoad: () -> Unit,
) {
    val canLoad = downloadState is ModelDownloadState.Completed && isBackendAvailable

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val mb = model.sizeBytes / (1024 * 1024)
                Text(
                    text = "${model.quantization} · ${mb}MB · 需 ${model.minRamMb}MB RAM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            model.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (downloadState is ModelDownloadState.Downloading) {
                LinearProgressIndicator(
                    progress = { downloadState.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "正在分片下载 ${downloadState.currentFileName} (${(downloadState.progress * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (downloadState is ModelDownloadState.Failed) {
                Text(
                    text = "下载异常：${downloadState.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onToggleDownload,
                    modifier = Modifier.weight(1f),
                ) {
                    val icon = when (downloadState) {
                        is ModelDownloadState.Downloading -> Icons.Rounded.Pause
                        is ModelDownloadState.Paused -> Icons.Rounded.PlayArrow
                        else -> Icons.Rounded.CloudDownload
                    }
                    val label = when (downloadState) {
                        is ModelDownloadState.Downloading -> "暂停下载"
                        is ModelDownloadState.Paused -> "断点续传"
                        is ModelDownloadState.Completed -> "权重已就绪"
                        else -> "分片下载"
                    }
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(label)
                }

                Button(
                    onClick = onLoad,
                    enabled = canLoad,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            isLoaded -> "重载权重"
                            !isBackendAvailable -> "需内置 .so"
                            downloadState !is ModelDownloadState.Completed -> "待下载"
                            else -> "加载模型"
                        },
                    )
                }
            }
        }
    }
}

private fun describeEngineState(state: EngineState): String = when (state) {
    EngineState.Unloaded -> "未加载（已释放）"
    is EngineState.Loading -> "加载 ${state.modelId}…"
    is EngineState.Ready -> "${state.backendName} 就绪"
    is EngineState.Generating -> "流式生成中 (${state.backendName})"
    is EngineState.Releasing -> "释放中 (${state.reason.name})"
    is EngineState.Error -> "不可用：${state.message}"
}
