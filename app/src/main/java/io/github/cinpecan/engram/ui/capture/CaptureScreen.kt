package io.github.cinpecan.engram.ui.capture

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cinpecan.engram.domain.agent.CalendarToolExecutor
import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.model.StreamingPreviewState
import io.github.cinpecan.engram.ui.components.CollapsibleBottomConsoleLayout
import io.github.cinpecan.engram.ui.navigation.EngramNavigator
import io.github.cinpecan.engram.ui.navigation.LocalEngramNavigator
import io.github.cinpecan.engram.ui.viewmodel.CaptureEffect
import io.github.cinpecan.engram.ui.viewmodel.CaptureIntent
import io.github.cinpecan.engram.ui.viewmodel.CaptureViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CaptureRoute(
    modifier: Modifier = Modifier,
    viewModel: CaptureViewModel = koinViewModel(),
    navigator: EngramNavigator = LocalEngramNavigator.current,
) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val streamingPreviews by viewModel.streamingPreviews.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CaptureEffect.LaunchCalendarInsert -> {
                    val launched = CalendarToolExecutor.launchCalendarInsert(context, effect.proposal)
                    viewModel.onIntent(
                        CaptureIntent.ConfirmCalendarLaunchResult(
                            memoryId = effect.memoryId,
                            title = effect.proposal.title,
                            launched = launched,
                        ),
                    )
                }
                is CaptureEffect.ShowMessage -> navigator.showMessage(effect.message)
            }
        }
    }

    CaptureScreen(
        memories = memories,
        streamingPreviews = streamingPreviews,
        inputText = inputText,
        isSubmitting = isSubmitting,
        onIntent = viewModel::onIntent,
        onOpenGraph = navigator::openGraphForMemory,
        onCopyRaw = { rawText ->
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("engram_raw", rawText))
            navigator.showMessage("已复制原文到剪贴板")
        },
        modifier = modifier,
    )
}

@Composable
fun CaptureScreen(
    memories: List<MemoryItem>,
    streamingPreviews: Map<String, StreamingPreviewState>,
    inputText: String,
    isSubmitting: Boolean,
    onIntent: (CaptureIntent) -> Unit,
    onOpenGraph: (String) -> Unit,
    onCopyRaw: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var isInputFocused by remember { mutableStateOf(false) }

    val isInputEmpty = inputText.isEmpty()
    val lockConsoleExpanded = inputText.isNotBlank() || isSubmitting
    val fieldCornerRadius by animateDpAsState(
        targetValue = if (isInputEmpty) 28.dp else 20.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "inputFieldCorner",
    )

    CollapsibleBottomConsoleLayout(
        lockExpanded = lockConsoleExpanded,
        onUserScroll = {
            if (isInputFocused && inputText.isBlank()) {
                focusManager.clearFocus()
            }
        },
        modifier = modifier,
        listContent = { bottomContentPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 12.dp,
                    end = 16.dp,
                    bottom = bottomContentPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(memories, key = { it.id }) { memory ->
                    MemoryTimelineCard(
                        memory = memory,
                        streamingPreview = streamingPreviews[memory.id],
                        onOpenGraph = { onOpenGraph(memory.id) },
                        onCopyRaw = { onCopyRaw(memory.content) },
                        onAddCalendar = { proposal ->
                            onIntent(
                                CaptureIntent.RequestAddCalendar(
                                    memoryId = memory.id,
                                    proposal = proposal,
                                ),
                            )
                        },
                    )
                }
            }
        },
        consoleContent = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AnimatedVisibility(visible = isInputEmpty) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item {
                                AssistChip(
                                    onClick = { onIntent(CaptureIntent.FillSample(SAMPLE_RELATION_NOTE)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Hub,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = "填入多方关系示例",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = { onIntent(CaptureIntent.FillSample(SAMPLE_CALENDAR_NOTE)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.CalendarMonth,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = "填入日程提取示例",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = if (isInputEmpty) Alignment.CenterVertically else Alignment.Bottom,
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { onIntent(CaptureIntent.UpdateInput(it)) },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { isInputFocused = it.isFocused },
                            placeholder = {
                                Text(
                                    text = "记下一段闪念…",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            minLines = 1,
                            maxLines = if (isInputEmpty) 1 else 4,
                            shape = RoundedCornerShape(fieldCornerRadius),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = { onIntent(CaptureIntent.SubmitCapture) },
                            enabled = inputText.isNotBlank() && !isSubmitting,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = "保存印迹",
                            )
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryTimelineCard(
    memory: MemoryItem,
    streamingPreview: StreamingPreviewState?,
    onOpenGraph: () -> Unit,
    onCopyRaw: () -> Unit,
    onAddCalendar: (CalendarProposal) -> Unit,
) {
    var expanded by rememberSaveable(memory.id) { mutableStateOf(false) }
    var wasPending by remember(memory.id) { mutableStateOf(memory.analysisStatus == MemoryAnalysisStatus.PENDING) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(memory.analysisStatus) {
        if (wasPending && memory.analysisStatus == MemoryAnalysisStatus.READY) {
            wasPending = false
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.8f,
                        stiffness = 380f,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Compact metadata header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = formatTimestamp(memory.createdAtMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                    if (!expanded && memory.tags.isNotEmpty()) {
                        Text(
                            text = memory.tags.take(2).joinToString("  ") { "#$it" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (memory.analysisStatus == MemoryAnalysisStatus.PENDING) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                )
                                Text(
                                    text = streamingPreview?.stageLabel ?: "提炼中…",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    } else if (!expanded) {
                        if (memory.calendarProposal != null) {
                            Icon(
                                imageVector = Icons.Outlined.CalendarMonth,
                                contentDescription = "包含日程",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        if (memory.relations.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Outlined.Hub,
                                contentDescription = "包含脉络",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }

                    Icon(
                        imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            AnimatedContent(
                targetState = memory.analysisStatus,
                transitionSpec = {
                    fadeIn(animationSpec = spring(dampingRatio = 1.0f, stiffness = 1600f)) togetherWith
                        fadeOut(animationSpec = spring(dampingRatio = 1.0f, stiffness = 1600f))
                },
                label = "cardAnalysisTransition",
            ) { status ->
                if (status == MemoryAnalysisStatus.PENDING) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = memory.summary.ifBlank { memory.content },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val partial = streamingPreview?.partialText.orEmpty()
                        if (partial.isNotBlank()) {
                            // Optical inner radius: outer 20dp - 12dp padding = 8dp
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = partial,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = memory.summary.ifBlank { memory.content },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        if (memory.telemetryBadge.isNotBlank()) {
                            val isModelDriven = memory.analysisSource == AnalysisSource.MNN_EDGE ||
                                memory.analysisSource == AnalysisSource.CLOUD_SSE
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isModelDriven) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                },
                            ) {
                                Text(
                                    text = memory.telemetryBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isModelDriven) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (expanded) {
                if (memory.tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        memory.tags.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            ) {
                                Text(
                                    text = "#$tag",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }

                memory.calendarProposal?.let { proposal ->
                    ScheduleInlineStrip(
                        proposal = proposal,
                        calendarSaved = memory.calendarSaved,
                        onAddCalendar = { onAddCalendar(proposal) },
                    )
                }

                // In-place raw text expansion (replaces RawMemoryBottomSheet)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "原始记录 · ${memory.content.length} 字",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(
                                onClick = onCopyRaw,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "复制原文",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                        SelectionContainer {
                            Text(
                                text = memory.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                if (memory.relations.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onOpenGraph),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Hub,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "脉络图谱 (${memory.relations.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleInlineStrip(
    proposal: CalendarProposal,
    calendarSaved: Boolean,
    onAddCalendar: () -> Unit,
) {
    val scheduleSubtitle = buildString {
        append(proposal.startTimeIso.replace('T', ' ').take(16).let {
            if (it.length > 5) it.drop(5) else it
        })
        append(" · ${proposal.durationMinutes}分钟")
        proposal.location?.takeIf { it.isNotBlank() }?.let { loc ->
            append(" · $loc")
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = proposal.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = scheduleSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onAddCalendar,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text(
                    text = if (calendarSaved) "已添至日历" else "添至日历",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (calendarSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

private const val SAMPLE_RELATION_NOTE =
    "项目同步：陈工刚提交了柔性屏模组最新可靠性报告给财务刘敏核验，老李通知样品报价单已发采购合同，需要法务张姐与王总尽快对齐交期违约条款。"

private const val SAMPLE_CALENDAR_NOTE =
    "后天上午10:30在软件园二期5号楼会议室，和陈工、老李对齐柔性屏量产固件测试验收。"
