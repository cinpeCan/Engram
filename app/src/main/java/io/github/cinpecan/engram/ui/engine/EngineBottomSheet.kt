package io.github.cinpecan.engram.ui.engine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cinpecan.engram.data.remote.DefaultCloudEndpointProvider
import io.github.cinpecan.engram.data.remote.ModelDownloadState
import io.github.cinpecan.engram.data.repository.CloudSessionState
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
    val cloudSessionState by viewModel.cloudSessionState.collectAsStateWithLifecycle()
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
        cloudSessionState = cloudSessionState,
        catalogItems = catalogItems,
        latestNotice = latestNotice,
        hasBundledDefaultEndpoint = DefaultCloudEndpointProvider.hasBundledDefaultEndpoint,
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
    cloudSessionState: CloudSessionState = CloudSessionState.IdleUnconfigured,
    hasBundledDefaultEndpoint: Boolean = DefaultCloudEndpointProvider.hasBundledDefaultEndpoint,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
            }
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .imePadding()
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
                text = "Inference & Sync",
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
                Text("On-device")
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
                Text("Cloud")
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
                text = "Multi-device sync",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GatewayEndpointField(
                value = serverUrlInput,
                hasBundledDefaultEndpoint = hasBundledDefaultEndpoint,
                onValueChange = { onIntent(EngineIntent.UpdateServerUrlInput(it)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Sync code / access token",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MaskedSyncKeyField(
                    value = syncKeyInput,
                    onValueChange = { onIntent(EngineIntent.UpdateSyncKeyInput(it)) },
                    modifier = Modifier.weight(1f),
                )

                val isSpinning = isSyncing || cloudSessionState is CloudSessionState.Verifying
                val canSync = cloudSessionState is CloudSessionState.Ready && !isSpinning

                ExpressivePillSurface(
                    onClick = { onIntent(EngineIntent.SyncMemories) },
                    enabled = canSync,
                    modifier = Modifier.size(42.dp),
                    restCorner = 21.dp,
                    pressedCorner = 12.dp,
                    containerColor = if (canSync) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    },
                    pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                    contentColor = if (canSync) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                    contentDescription = "Sync memories",
                ) {
                    if (isSpinning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = "Sync",
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }

            val statusBanner = resolveStatusBanner(
                syncKeyInput = syncKeyInput,
                cloudSessionState = cloudSessionState,
                latestNotice = latestNotice,
                engineError = (runtimeStatus.engineState as? EngineState.Error)?.message,
            )
            if (statusBanner != null) {
                val canRetry = cloudSessionState is CloudSessionState.Unreachable
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = statusBanner.containerColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (canRetry) {
                                Modifier.clickable { onIntent(EngineIntent.RetryCloudVerification) }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(
                        text = statusBanner.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusBanner.textColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

private data class StatusBanner(
    val text: String,
    val containerColor: Color,
    val textColor: Color,
)

@Composable
private fun resolveStatusBanner(
    syncKeyInput: String,
    cloudSessionState: CloudSessionState,
    latestNotice: String?,
    engineError: String?,
): StatusBanner? {
    val neutralBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val neutralFg = MaterialTheme.colorScheme.onSurfaceVariant
    val readyBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    val readyFg = MaterialTheme.colorScheme.onPrimaryContainer
    val errorBg = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    val errorFg = MaterialTheme.colorScheme.onErrorContainer

    return when (cloudSessionState) {
        CloudSessionState.Verifying -> StatusBanner("Verifying access token…", neutralBg, neutralFg)
        CloudSessionState.InvalidToken -> StatusBanner("Invalid or expired access token", errorBg, errorFg)
        CloudSessionState.QuotaExhausted -> StatusBanner("Cloud quota reached", errorBg, errorFg)
        CloudSessionState.Unreachable -> StatusBanner("Can't reach the cloud gateway · Tap to retry", errorBg, errorFg)
        is CloudSessionState.Ready -> when {
            !latestNotice.isNullOrBlank() -> StatusBanner(latestNotice, readyBg, readyFg)
            cloudSessionState.remainingQuota != null -> StatusBanner(
                "Cloud ready · ${cloudSessionState.remainingQuota} distillations left",
                readyBg,
                readyFg,
            )
            else -> StatusBanner("Cloud ready", readyBg, readyFg)
        }
        CloudSessionState.IdleUnconfigured -> when {
            !latestNotice.isNullOrBlank() -> StatusBanner(latestNotice, neutralBg, neutralFg)
            syncKeyInput.trim().isNotEmpty() -> StatusBanner("Keep typing the full access token", neutralBg, neutralFg)
            !engineError.isNullOrBlank() -> StatusBanner(engineError, errorBg, errorFg)
            else -> null
        }
    }
}

@Composable
private fun GatewayEndpointField(
    value: String,
    hasBundledDefaultEndpoint: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val showMaskedPreset = hasBundledDefaultEndpoint && value.isEmpty() && !isFocused
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = if (value.isNotEmpty()) 4.dp else 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .onFocusChanged { isFocused = it.isFocused },
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (showMaskedPreset) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = "https://••••••••••••",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                                ) {
                                    Text(
                                        text = "Default",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        } else if (value.isEmpty()) {
                            Text(
                                text = if (hasBundledDefaultEndpoint) {
                                    "Custom endpoint (optional)"
                                } else {
                                    "Server URL (e.g. http://127.0.0.1:8787)"
                                },
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

            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear server URL",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MaskedSyncKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var revealSecret by remember { mutableStateOf(false) }
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = if (value.isNotEmpty()) 4.dp else 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (revealSecret) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = "Sync code / access token",
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

            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { revealSecret = !revealSecret },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = if (revealSecret) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Visibility
                        },
                        contentDescription = if (revealSecret) "Hide access token" else "Show access token",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear access token",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
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
        ModelDownloadState.Idle -> "Offline model · ~${approxMb} MB (direct CDN)"
        is ModelDownloadState.Downloading -> {
            val pct = (downloadState.progress * 100).toInt()
            "Downloading $pct% · ${downloadState.currentFileName}"
        }
        is ModelDownloadState.Paused -> {
            val pct = if (downloadState.totalBytes > 0L) {
                (downloadState.bytesRead * 100 / downloadState.totalBytes).toInt().coerceIn(0, 99)
            } else {
                0
            }
            "Paused · $pct%"
        }
        is ModelDownloadState.Completed -> when {
            isLoading -> "Loading into memory…"
            isGenerating -> "Running on-device…"
            isReady -> {
                if (engineState.modelFootprintBytes > 0L) {
                    String.format(
                        Locale.US,
                        "Ready · +%.0f MB",
                        engineState.modelFootprintBytes / (1024.0 * 1024.0),
                    )
                } else {
                    "Ready"
                }
            }
            runtimeStatus.isBackendAvailable -> "Downloaded · Idle"
            else -> "Native runtime unavailable on this build"
        }
        is ModelDownloadState.Failed -> "Download failed: ${downloadState.message}"
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
                            Text("Free memory", style = MaterialTheme.typography.labelLarge)
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
                            text = if (isLoading) "Loading" else "Load model",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                else -> {
                    val (icon, label, containerColor, contentColor) = when (downloadState) {
                        is ModelDownloadState.Downloading -> Quadruple(
                            Icons.Rounded.Pause,
                            "Pause",
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        is ModelDownloadState.Paused -> Quadruple(
                            Icons.Rounded.PlayArrow,
                            "Resume",
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.onPrimary,
                        )
                        else -> Quadruple(
                            Icons.Rounded.CloudDownload,
                            "Download",
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

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
