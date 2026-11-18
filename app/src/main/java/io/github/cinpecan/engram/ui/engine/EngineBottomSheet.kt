package io.github.cinpecan.engram.ui.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cinpecan.engram.data.remote.ModelDownloadState
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.ui.components.ExpressivePillSurface
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
    val syncKeyInput by viewModel.syncKeyInput.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
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
        syncKeyInput = syncKeyInput,
        isSyncing = isSyncing,
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
    syncKeyInput: String,
    isSyncing: Boolean,
    catalogItems: List<ModelCatalogItemUi>,
    latestNotice: String?,
    onIntent: (EngineIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "推理与多端同步",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

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
                Text("端侧离线")
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
                Text("云端协同")
            }
        }

        catalogItems.forEach { item ->
            FramelessModelRow(
                item = item,
                runtimeStatus = runtimeStatus,
                onToggleDownload = { onIntent(EngineIntent.ToggleDownload(item.model)) },
                onLoad = { onIntent(EngineIntent.LoadModel(item.model)) },
                onRelease = { onIntent(EngineIntent.ReleaseMemoryNow) },
            )
        }

        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "多设备记忆同步",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SheetTextField(
                value = serverUrlInput,
                onValueChange = { onIntent(EngineIntent.UpdateServerUrlInput(it)) },
                placeholder = "服务器地址（如 http://192.168.1.10:8787）",
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetTextField(
                    value = syncKeyInput,
                    onValueChange = { onIntent(EngineIntent.UpdateSyncKeyInput(it)) },
                    placeholder = "同步码（如 engram-demo）",
                    modifier = Modifier.weight(1f),
                )

                ExpressivePillSurface(
                    onClick = { onIntent(EngineIntent.SyncMemories) },
                    enabled = !isSyncing,
                    restCorner = 18.dp,
                    pressedCorner = 10.dp,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            text = if (isSyncing) "同步中" else "同步",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            val displayNotice = latestNotice ?: (runtimeStatus.engineState as? EngineState.Error)?.message
            if (!displayNotice.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = displayNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun FramelessModelRow(
    item: ModelCatalogItemUi,
    runtimeStatus: EngineRuntimeStatus,
    onToggleDownload: () -> Unit,
    onLoad: () -> Unit,
    onRelease: () -> Unit,
) {
    val engineState = runtimeStatus.engineState
    val downloadState = item.downloadState
    val isReady = engineState is EngineState.Ready && engineState.modelId == item.model.id
    val isGenerating = engineState is EngineState.Generating && engineState.modelId == item.model.id
    val isLoading = engineState is EngineState.Loading && engineState.modelId == item.model.id
    val approxMb = (item.model.sizeBytes / (1024 * 1024)).coerceAtLeast(1)

    val subtitle = when (downloadState) {
        ModelDownloadState.Idle -> "离线模型 · 约 ${approxMb} MB（魔搭直连）"
        is ModelDownloadState.Downloading -> {
            val pct = (downloadState.progress * 100).toInt()
            "下载中 $pct% · ${downloadState.currentFileName}"
        }
        is ModelDownloadState.Paused -> {
            val pct = if (downloadState.totalBytes > 0L) {
                (downloadState.bytesRead * 100 / downloadState.totalBytes).toInt().coerceIn(0, 99)
            } else {
                0
            }
            "已暂停 · $pct%"
        }
        is ModelDownloadState.Completed -> when {
            isLoading -> "正在加载至内存…"
            isGenerating -> "端侧推理中…"
            isReady -> {
                if (engineState.modelFootprintBytes > 0L) {
                    String.format(
                        Locale.US,
                        "已就绪 · 占用 +%.0f MB",
                        engineState.modelFootprintBytes / (1024.0 * 1024.0),
                    )
                } else {
                    "已就绪"
                }
            }
            runtimeStatus.isBackendAvailable -> "已下载 · 待命"
            else -> "当前环境暂不支持原生库"
        }
        is ModelDownloadState.Failed -> "下载失败：${downloadState.message}"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${item.model.name} · ${item.model.quantization}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (downloadState is ModelDownloadState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            when {
                isReady || isGenerating -> {
                    ExpressivePillSurface(
                        onClick = onRelease,
                        enabled = !isGenerating,
                        restCorner = 18.dp,
                        pressedCorner = 10.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        pressedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CleaningServices,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Text("释放内存", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                downloadState is ModelDownloadState.Completed -> {
                    val canLoad = runtimeStatus.isBackendAvailable && !isLoading
                    ExpressivePillSurface(
                        onClick = onLoad,
                        enabled = canLoad,
                        restCorner = 18.dp,
                        pressedCorner = 10.dp,
                        containerColor = if (canLoad) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        contentColor = if (canLoad) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        },
                    ) {
                        Text(
                            text = if (isLoading) "加载中" else "加载模型",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                else -> {
                    val (icon, label, containerColor, contentColor) = when (downloadState) {
                        is ModelDownloadState.Downloading -> Quadruple(
                            Icons.Rounded.Pause,
                            "暂停",
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        is ModelDownloadState.Paused -> Quadruple(
                            Icons.Rounded.PlayArrow,
                            "继续",
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.onPrimary,
                        )
                        else -> Quadruple(
                            Icons.Rounded.CloudDownload,
                            "下载",
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    ExpressivePillSurface(
                        onClick = onToggleDownload,
                        restCorner = 18.dp,
                        pressedCorner = 10.dp,
                        containerColor = containerColor,
                        pressedContainerColor = containerColor.copy(alpha = 0.85f),
                        contentColor = contentColor,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        if (downloadState is ModelDownloadState.Downloading) {
            LinearProgressIndicator(
                progress = { downloadState.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        } else if (downloadState is ModelDownloadState.Paused && downloadState.totalBytes > 0L) {
            LinearProgressIndicator(
                progress = { (downloadState.bytesRead.toFloat() / downloadState.totalBytes.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
