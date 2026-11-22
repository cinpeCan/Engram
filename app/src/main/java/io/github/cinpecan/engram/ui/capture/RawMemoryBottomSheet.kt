package io.github.cinpecan.engram.ui.capture

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cinpecan.engram.ui.components.ExpressivePillSurface
import io.github.cinpecan.engram.ui.theme.transparentize
import io.github.cinpecan.engram.ui.viewmodel.RawMemoryIntent
import io.github.cinpecan.engram.ui.viewmodel.RawMemoryUiState
import io.github.cinpecan.engram.ui.viewmodel.RawMemoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun RawMemoryRoute(
    memoryId: String,
    modifier: Modifier = Modifier,
    viewModel: RawMemoryViewModel = koinViewModel(key = memoryId) { parametersOf(memoryId) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isCopied by viewModel.isCopied.collectAsStateWithLifecycle()
    RawMemorySheetContent(
        uiState = uiState,
        isCopied = isCopied,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

@Composable
fun RawMemorySheetContent(
    uiState: RawMemoryUiState,
    isCopied: Boolean = false,
    onIntent: (RawMemoryIntent) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    when (uiState) {
        RawMemoryUiState.Loading -> {
            Spacer(
                modifier = modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(120.dp),
            )
        }
        RawMemoryUiState.NotFound -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Source note not found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                Spacer(modifier = Modifier.navigationBarsPadding().height(12.dp))
            }
        }
        is RawMemoryUiState.Ready -> {
            val memory = uiState.memory
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Source note",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${formatFullTimestamp(memory.createdAtMillis)} · ${memory.content.length} chars",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    ExpressivePillSurface(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("engram_raw", memory.content))
                            onIntent(RawMemoryIntent.OnSourceCopied)
                        },
                        modifier = Modifier.size(36.dp),
                        restCorner = 18.dp,
                        pressedCorner = 10.dp,
                        containerColor = if (isCopied) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.transparentize()
                        },
                        pressedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isCopied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        contentDescription = if (isCopied) "Source copied" else "Copy source",
                    ) {
                        Icon(
                            imageVector = if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SelectionContainer {
                        Text(
                            text = memory.content,
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Spacer(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .height(12.dp),
                )
            }
        }
    }
}

private fun formatFullTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
