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
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.ui.viewmodel.EngineViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineBottomSheet(
    viewModel: EngineViewModel,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        EngineBottomSheetContent(viewModel = viewModel)
    }
}

@Composable
fun EngineBottomSheetContent(
    viewModel: EngineViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var urlDraft by remember(state.serverBaseUrl) { mutableStateOf(state.serverBaseUrl) }

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
                    text = "端侧引擎与模型仓",
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            // Native Memory Lifecycle Card
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
                            text = "状态：${describeEngineState(state.engineState)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = String.format("Native Heap: %.1f MB", state.nativeHeapMb),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    state.statusNotice?.let { notice ->
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
                        onClick = viewModel::releaseMemoryNow,
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
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    label = { Text("同仓 FastAPI 网关地址") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        viewModel.updateServerUrl(urlDraft)
                        viewModel.refreshCatalog(urlDraft)
                    },
                    enabled = !state.isRefreshingCatalog,
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "同步模型清单")
                }
            }

            // Model List
            state.catalog.forEach { model ->
                ModelCatalogRow(
                    model = model,
                    downloadState = state.downloadStates[model.id] ?: ModelDownloadState.Idle,
                    isSelected = state.selectedModelId == model.id,
                    onToggleDownload = { viewModel.toggleDownload(model) },
                    onLoad = { viewModel.loadModel(model) },
                )
            }

            Spacer(modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ModelCatalogRow(
    model: ModelDescriptorDto,
    downloadState: ModelDownloadState,
    isSelected: Boolean,
    onToggleDownload: () -> Unit,
    onLoad: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val mb = model.sizeBytes / (1024 * 1024)
                    Text(
                        text = "${model.role} · ${model.quantization} · ${mb}MB · 需 ${model.minRamMb}MB RAM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                        is ModelDownloadState.Completed -> "已就绪"
                        else -> "分片下载"
                    }
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(label)
                }

                if (model.role == "embedding") {
                    OutlinedButton(
                        onClick = onLoad,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("向量组件")
                    }
                } else {
                    Button(
                        onClick = onLoad,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (isSelected) "激活 / 重载" else "加载模型")
                    }
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
    is EngineState.Error -> "错误：${state.message}"
}
