package io.github.cinpecan.engram.ui.ask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.ui.components.CollapsibleBottomConsoleLayout
import io.github.cinpecan.engram.ui.viewmodel.AskConversationTurn
import io.github.cinpecan.engram.ui.viewmodel.AskViewModel

@Composable
fun AskScreen(
    viewModel: AskViewModel,
    onOpenRawMemory: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var isInputFocused by remember { mutableStateOf(false) }

    val isQueryEmpty = state.queryInput.isEmpty()
    val lockConsoleExpanded = state.queryInput.isNotBlank() || state.isAsking

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        // Offline Edge vs Cloud Collaboration Switch
        Surface(
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.mode == InferenceMode.OFFLINE_EDGE,
                        onClick = { viewModel.setMode(InferenceMode.OFFLINE_EDGE) },
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
                        selected = state.mode == InferenceMode.CLOUD_COLLAB,
                        onClick = { viewModel.setMode(InferenceMode.CLOUD_COLLAB) },
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

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(SUGGESTED_QUERIES) { suggestion ->
                        AssistChip(
                            onClick = { viewModel.submitQuery(suggestion) },
                            label = {
                                Text(
                                    text = suggestion,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }

        CollapsibleBottomConsoleLayout(
            lockExpanded = lockConsoleExpanded,
            onUserScroll = {
                if (isInputFocused && state.queryInput.isBlank()) {
                    focusManager.clearFocus()
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            listContent = { bottomContentPadding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = bottomContentPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (state.turns.isEmpty()) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                ),
                            ) {
                                Text(
                                    text = "在上方点击示例问题或输入任何追问。系统先执行本地「CJK 双字全文 + 向量 RRF」混合召回并明文展示引用来源，再在本地 MNN 或同仓 SSE 网关流式作答。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }

                    items(state.turns, key = { it.id }) { turn ->
                        AskTurnView(
                            turn = turn,
                            onOpenRawMemory = onOpenRawMemory,
                        )
                    }
                }
            },
            consoleContent = {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = if (isQueryEmpty) Alignment.CenterVertically else Alignment.Bottom,
                    ) {
                        OutlinedTextField(
                            value = state.queryInput,
                            onValueChange = viewModel::updateQuery,
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { isInputFocused = it.isFocused },
                            placeholder = {
                                Text(
                                    text = "向本地记忆库提问…",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            minLines = 1,
                            maxLines = if (isQueryEmpty) 1 else 4,
                            shape = RoundedCornerShape(24.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = { viewModel.submitQuery() },
                            enabled = state.queryInput.isNotBlank() && !state.isAsking,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "提问")
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun AskTurnView(
    turn: AskConversationTurn,
    onOpenRawMemory: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // User Query Bubble
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    text = turn.query,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        // White-Box RAG Citations
        if (turn.citations.isNotEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "白盒召回溯源（RRF 混合检索命中 ${turn.citations.size} 条）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    turn.citations.forEachIndexed { idx, hit ->
                        val rankBadge = buildString {
                            append("RRF ")
                            append(String.format("%.4f", hit.rrfScore))
                            hit.lexicalRank?.let { append(" · FTS #$it") }
                            hit.vectorRank?.let {
                                append(" · Vec #$it (${String.format("%.2f", hit.vectorScore)})")
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenRawMemory(hit.memory.id) }
                                .padding(vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "[${idx + 1}] $rankBadge",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                                Text(
                                    text = "原文",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                )
                            }
                            Text(
                                text = hit.memory.summary.ifBlank { hit.memory.content },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // Streamed Answer + Telemetry Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = turn.answerText.ifEmpty { if (turn.isStreaming) "正在推理…" else "" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                turn.telemetry?.let { t ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = String.format(
                                "%s · TTFT %dms · %.1f tok/s · Native %.1f MB",
                                t.engineLabel,
                                t.ttftMs,
                                t.tokensPerSec,
                                t.nativeHeapMb,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

private val SUGGESTED_QUERIES = listOf(
    "屏幕采购为什么还没签？",
    "测试后台账号和密码是多少？",
    "明天会议安排在哪里？",
)
