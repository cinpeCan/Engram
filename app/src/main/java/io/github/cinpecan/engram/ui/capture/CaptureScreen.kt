package io.github.cinpecan.engram.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cinpecan.engram.data.repository.UnifiedInferenceRouter
import io.github.cinpecan.engram.domain.agent.CalendarToolExecutor
import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.CalendarProposal
import io.github.cinpecan.engram.domain.model.MemoryAnalysisStatus
import io.github.cinpecan.engram.domain.model.MemoryItem
import io.github.cinpecan.engram.domain.model.StreamingPreviewState
import io.github.cinpecan.engram.ui.components.CollapsibleBottomConsoleLayout
import io.github.cinpecan.engram.ui.components.ExpressiveCardSurface
import io.github.cinpecan.engram.ui.components.ExpressivePillSurface
import io.github.cinpecan.engram.ui.navigation.EngramNavigator
import io.github.cinpecan.engram.ui.navigation.LocalEngramNavigator
import io.github.cinpecan.engram.ui.navigation.LocalWorkspaceContentPadding
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
    var pendingInsert by remember { mutableStateOf<CaptureEffect.OpenCalendarInsert?>(null) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onIntent(CaptureIntent.OnCalendarPermissionResult(granted))
        val pending = pendingInsert
        pendingInsert = null
        if (pending != null) {
            val launched = CalendarToolExecutor.launchCalendarInsert(
                context = context,
                memoryId = pending.memoryId,
                proposal = pending.proposal,
            )
            viewModel.onIntent(
                CaptureIntent.OnCalendarActionLaunched(
                    title = pending.proposal.title,
                    launched = launched,
                    openedExisting = false,
                    hasReadPermission = granted,
                ),
            )
        }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CaptureEffect.OpenCalendarInsert -> {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_CALENDAR,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPermission) {
                        val launched = CalendarToolExecutor.launchCalendarInsert(
                            context = context,
                            memoryId = effect.memoryId,
                            proposal = effect.proposal,
                        )
                        viewModel.onIntent(
                            CaptureIntent.OnCalendarActionLaunched(
                                title = effect.proposal.title,
                                launched = launched,
                                openedExisting = false,
                                hasReadPermission = true,
                            ),
                        )
                    } else {
                        pendingInsert = effect
                        calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                    }
                }
                is CaptureEffect.OpenCalendarEvent -> {
                    val launched = CalendarToolExecutor.launchCalendarView(
                        context = context,
                        eventId = effect.eventId,
                        proposal = effect.proposal,
                    )
                    viewModel.onIntent(
                        CaptureIntent.OnCalendarActionLaunched(
                            title = effect.proposal.title,
                            launched = launched,
                            openedExisting = true,
                            hasReadPermission = true,
                        ),
                    )
                }
                is CaptureEffect.ShowMessage -> navigator.showMessage(effect.message)
                is CaptureEffect.ShowUndoDelete -> navigator.showActionMessage(
                    message = effect.message,
                    actionLabel = "Undo",
                    onAction = { viewModel.onIntent(CaptureIntent.UndoDeleteMemory) },
                )
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
        onOpenRawMemory = navigator::openRawMemory,
        onConsoleClearanceChanged = { navigator.bottomConsoleClearanceDp = it },
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptureScreen(
    memories: List<MemoryItem>,
    streamingPreviews: Map<String, StreamingPreviewState>,
    inputText: String,
    isSubmitting: Boolean,
    onIntent: (CaptureIntent) -> Unit,
    onOpenGraph: (String) -> Unit,
    onOpenRawMemory: (String) -> Unit,
    modifier: Modifier = Modifier,
    onConsoleClearanceChanged: (Dp) -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var isInputFocused by remember { mutableStateOf(false) }
    var activeDeleteBubbleMemoryId by remember { mutableStateOf<String?>(null) }
    val isImeVisible = WindowInsets.isImeVisible

    val dismissKeyboard: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    val isInputEmpty = inputText.isEmpty()
    val lockConsoleExpanded = inputText.isNotBlank() || isSubmitting || isInputFocused
    val workspacePadding = LocalWorkspaceContentPadding.current

    CollapsibleBottomConsoleLayout(
        lockExpanded = lockConsoleExpanded,
        onUserScroll = {
            activeDeleteBubbleMemoryId = null
            if (isInputFocused || isImeVisible) {
                dismissKeyboard()
            }
        },
        extraBottomListPadding = 8.dp,
        onClearanceChanged = onConsoleClearanceChanged,
        modifier = modifier,
        listContent = { bottomContentPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isInputFocused, isImeVisible) {
                        if (isInputFocused || isImeVisible) {
                            detectTapGestures {
                                activeDeleteBubbleMemoryId = null
                                dismissKeyboard()
                            }
                        }
                    },
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = workspacePadding.calculateTopPadding() + 8.dp,
                    end = 16.dp,
                    bottom = bottomContentPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(memories, key = { it.id }) { memory ->
                    MemoryTimelineCard(
                        memory = memory,
                        streamingPreview = streamingPreviews[memory.id],
                        isKeyboardActive = isInputFocused || isImeVisible,
                        isDeleteBubbleVisible = activeDeleteBubbleMemoryId == memory.id,
                        onDismissKeyboard = dismissKeyboard,
                        onLongPress = {
                            if (isInputFocused || isImeVisible) {
                                dismissKeyboard()
                            }
                            activeDeleteBubbleMemoryId = memory.id
                        },
                        onDismissDeleteBubble = {
                            if (activeDeleteBubbleMemoryId == memory.id) {
                                activeDeleteBubbleMemoryId = null
                            }
                        },
                        onDeleteMemory = {
                            activeDeleteBubbleMemoryId = null
                            onIntent(CaptureIntent.DeleteMemory(memory))
                        },
                        onOpenGraph = { onOpenGraph(memory.id) },
                        onOpenRawMemory = { onOpenRawMemory(memory.id) },
                        onAddCalendar = { proposal ->
                            onIntent(
                                CaptureIntent.RequestCalendarAction(
                                    memoryId = memory.id,
                                    proposal = proposal,
                                    existingEventId = memory.calendarEventId,
                                ),
                            )
                        },
                    )
                }
            }
        },
        consoleContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(visible = isInputEmpty) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            ExpressivePillSurface(
                                onClick = { onIntent(CaptureIntent.FillSample(SAMPLE_RELATION_NOTE)) },
                                restCorner = 16.dp,
                                pressedCorner = 10.dp,
                                containerColor = MaterialTheme.colorScheme.surface,
                                pressedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                border = BorderStroke(
                                    width = 0.75.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Hub,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        text = "Try a relationship note",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        item {
                            ExpressivePillSurface(
                                onClick = { onIntent(CaptureIntent.FillSample(SAMPLE_CALENDAR_NOTE)) },
                                restCorner = 16.dp,
                                pressedCorner = 10.dp,
                                containerColor = MaterialTheme.colorScheme.surface,
                                pressedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                border = BorderStroke(
                                    width = 0.75.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CalendarMonth,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        text = "Try a scheduling note",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                UnifiedCaptureComposer(
                    inputText = inputText,
                    isInputFocused = isInputFocused,
                    isSubmitting = isSubmitting,
                    onFocusChanged = { isInputFocused = it },
                    onValueChange = { onIntent(CaptureIntent.UpdateInput(it)) },
                    onSubmit = {
                        onIntent(CaptureIntent.SubmitCapture)
                        dismissKeyboard()
                    },
                )
            }
        },
    )
}

@Composable
private fun UnifiedCaptureComposer(
    inputText: String,
    isInputFocused: Boolean,
    isSubmitting: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )

    val borderColor by animateColorAsState(
        targetValue = if (isInputFocused) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
        },
        animationSpec = spring(dampingRatio = 1.0f, stiffness = 2400f),
        label = "composerBorderColor",
    )

    val canSubmit = inputText.isNotBlank() && !isSubmitting
    val hasInput = inputText.isNotEmpty()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val reservedSingleLineHorizontalPx = with(density) {
            (if (hasInput) 98.dp else 66.dp).roundToPx()
        }
        val singleLineAvailPx = (constraints.maxWidth - reservedSingleLineHorizontalPx).coerceAtLeast(1)
        val measured = remember(inputText, singleLineAvailPx, textStyle) {
            if (inputText.isEmpty()) {
                null
            } else {
                textMeasurer.measure(
                    text = inputText,
                    style = textStyle,
                    constraints = Constraints(maxWidth = singleLineAvailPx),
                )
            }
        }
        val isMultiline = inputText.contains('\n') || (measured?.lineCount ?: 0) > 1

        val containerCorner by animateDpAsState(
            targetValue = if (isMultiline) 20.dp else 26.dp,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 500f),
            label = "composerCorner",
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(containerCorner),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            border = BorderStroke(
                width = if (isInputFocused) 1.5.dp else 1.dp,
                color = borderColor,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(if (isMultiline) Alignment.TopStart else Alignment.CenterStart)
                        .onFocusChanged { onFocusChanged(it.isFocused) }
                        .padding(
                            start = 16.dp,
                            top = 12.dp,
                            end = when {
                                isMultiline -> 16.dp
                                hasInput -> 82.dp
                                else -> 50.dp
                            },
                            bottom = if (isMultiline) 44.dp else 12.dp,
                        ),
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    maxLines = 5,
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (inputText.isEmpty()) {
                                Text(
                                    text = "Capture a thought…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    },
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (hasInput) {
                        IconButton(
                            onClick = { onValueChange("") },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear input",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    ExpressivePillSurface(
                        onClick = onSubmit,
                        enabled = canSubmit,
                        modifier = Modifier.size(36.dp),
                        restCorner = 18.dp,
                        pressedCorner = 11.dp,
                        containerColor = if (canSubmit) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                        },
                        pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        contentColor = if (canSubmit) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        },
                        contentDescription = "Save memory",
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryTimelineCard(
    memory: MemoryItem,
    streamingPreview: StreamingPreviewState?,
    isKeyboardActive: Boolean,
    isDeleteBubbleVisible: Boolean,
    onDismissKeyboard: () -> Unit,
    onLongPress: () -> Unit,
    onDismissDeleteBubble: () -> Unit,
    onDeleteMemory: () -> Unit,
    onOpenGraph: () -> Unit,
    onOpenRawMemory: () -> Unit,
    onAddCalendar: (CalendarProposal) -> Unit,
) {
    var expanded by rememberSaveable(memory.id) { mutableStateOf(false) }
    var wasPending by remember(memory.id) { mutableStateOf(memory.analysisStatus == MemoryAnalysisStatus.PENDING) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    LaunchedEffect(memory.analysisStatus) {
        if (wasPending && memory.analysisStatus == MemoryAnalysisStatus.READY) {
            wasPending = false
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        ExpressiveCardSurface(
            onClick = {
                if (isDeleteBubbleVisible) {
                    onDismissDeleteBubble()
                } else if (isKeyboardActive) {
                    onDismissKeyboard()
                } else {
                    expanded = !expanded
                }
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongPress()
            },
            modifier = Modifier.fillMaxWidth(),
            restCorner = 20.dp,
            pressedCorner = 15.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.5.dp,
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
                        if (memory.analysisSource == AnalysisSource.SEED) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                            ) {
                                Text(
                                    text = "Sample",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
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
                                    text = streamingPreview?.stageLabel ?: "Distilling…",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    } else if (!expanded) {
                        if (memory.calendarProposal != null) {
                            Icon(
                                imageVector = if (memory.calendarSaved) {
                                    Icons.Rounded.EventAvailable
                                } else {
                                    Icons.Outlined.CalendarMonth
                                },
                                contentDescription = if (memory.calendarSaved) "Added to calendar" else "Has schedule",
                                tint = if (memory.calendarSaved) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                },
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        if (memory.relations.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Outlined.Hub,
                                contentDescription = "Has graph",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }

                    Icon(
                        imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            AnimatedContent(
                targetState = memory.analysisStatus,
                transitionSpec = {
                    (
                        fadeIn(animationSpec = spring(dampingRatio = 1.0f, stiffness = 900f)) togetherWith
                            fadeOut(animationSpec = spring(dampingRatio = 1.0f, stiffness = 2400f))
                        ).using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.9f, stiffness = 420f) })
                },
                contentAlignment = Alignment.TopStart,
                label = "cardAnalysisTransition",
            ) { status ->
                if (status == MemoryAnalysisStatus.PENDING) {
                    val partial = streamingPreview?.partialText.orEmpty()
                    if (partial.isNotBlank()) {
                        // Typewriter preview: the summary decoded so far, rendered exactly like a
                        // READY card so the final text settles in place without a layout jump.
                        SummaryBulletList(
                            summary = partial,
                            expanded = expanded,
                        )
                    } else {
                        Text(
                            text = memory.content.replace(WHITESPACE_RUN_REGEX, " "),
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SummaryBulletList(
                            summary = memory.summary.ifBlank { memory.content },
                            expanded = expanded,
                        )

                        val showTelemetryBadge = shouldShowTelemetryBadge(memory)
                        if (showTelemetryBadge) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            ) {
                                Text(
                                    text = memory.telemetryBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (memory.relations.isNotEmpty()) {
                        ExpressivePillSurface(
                            onClick = onOpenGraph,
                            restCorner = 16.dp,
                            pressedCorner = 9.dp,
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Hub,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "Graph (${memory.relations.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    ExpressivePillSurface(
                        onClick = onOpenRawMemory,
                        restCorner = 8.dp,
                        pressedCorner = 6.dp,
                        containerColor = Color.Transparent,
                        pressedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Subject,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Source",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
        }

        if (isDeleteBubbleVisible) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, -with(density) { 8.dp.roundToPx() }),
                onDismissRequest = onDismissDeleteBubble,
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 6.dp,
                    border = BorderStroke(
                        width = 0.75.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    ),
                ) {
                    ExpressivePillSurface(
                        onClick = onDeleteMemory,
                        restCorner = 16.dp,
                        pressedCorner = 10.dp,
                        containerColor = Color.Transparent,
                        pressedContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        contentColor = MaterialTheme.colorScheme.error,
                        contentDescription = "Delete card",
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Delete",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryBulletList(
    summary: String,
    expanded: Boolean,
) {
    val lines = remember(summary) {
        summary.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
    if (lines.size <= 1 && !summary.trimStart().startsWith("•")) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        val visibleLines = if (expanded) lines else lines.take(3)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            visibleLines.forEach { rawLine ->
                val textBody = rawLine.removePrefix("•").removePrefix("·").trim()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                    )
                    Text(
                        text = textBody,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
        append(" · ${proposal.durationMinutes} min")
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
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
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
            ExpressivePillSurface(
                onClick = onAddCalendar,
                restCorner = 12.dp,
                pressedCorner = 7.dp,
                containerColor = if (calendarSaved) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    Color.Transparent
                },
                pressedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (calendarSaved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            ) {
                Text(
                    text = if (calendarSaved) "Added" else "Add to calendar",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

private val WHITESPACE_RUN_REGEX = Regex("\\s+")

private val FALLBACK_BADGE_PREFIXES = listOf(
    UnifiedInferenceRouter.BADGE_FALLBACK_UNAUTHORIZED,
    UnifiedInferenceRouter.BADGE_FALLBACK_QUOTA,
    UnifiedInferenceRouter.BADGE_FALLBACK_OFFLINE,
)

/**
 * Telemetry badges are shown for model-driven runs (MNN / Cloud SSE) and for honest
 * cloud-degraded local extraction. Seed cards and plain offline rule runs stay badge-free.
 */
internal fun shouldShowTelemetryBadge(memory: MemoryItem): Boolean {
    val badge = memory.telemetryBadge
    if (badge.isBlank()) return false
    return when (memory.analysisSource) {
        AnalysisSource.SEED -> false
        AnalysisSource.MNN_EDGE, AnalysisSource.CLOUD_SSE -> true
        AnalysisSource.LOCAL_RULE_FALLBACK -> FALLBACK_BADGE_PREFIXES.any { badge.startsWith(it) }
    }
}

private const val SAMPLE_RELATION_NOTE =
    "Project sync: Liam Chen sent the latest Micro-OLED reliability report to Priya Nair for review. " +
        "Marcus Cole says the revised quote is attached to the supply agreement, so Sarah Jenkins and " +
        "Elena Vance need to align the delivery penalty clause."

private const val SAMPLE_CALENDAR_NOTE =
    "The day after tomorrow at 10:30 AM in Building A, Room 501: sign off the Micro-OLED " +
        "mass-production firmware test with Liam Chen and Marcus Cole."
