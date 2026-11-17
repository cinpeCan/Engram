package io.github.cinpecan.engram.ui.graph

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinpe.deponder.compose.DeponderDefaultRubber
import com.cinpe.deponder.compose.DeponderRubberStyle
import com.cinpe.deponder.compose.deponderDrag
import com.cinpe.deponder.compose.deponderPlanet
import com.cinpe.deponder.compose.deponderRoot
import com.cinpe.deponder.compose.rememberDeponderState
import com.cinpe.deponder.core.DeponderArrowMode
import com.cinpe.deponder.core.DeponderDefaults
import com.cinpe.deponder.core.DeponderRubberInsets
import com.cinpe.deponder.core.PlanetPhysics
import com.cinpe.deponder.core.RootPhysics
import com.cinpe.deponder.core.RubberPhysics
import io.github.cinpecan.engram.ui.navigation.EngramNavigator
import io.github.cinpecan.engram.ui.navigation.LocalEngramNavigator
import io.github.cinpecan.engram.ui.navigation.LocalModalBottomSheetState
import io.github.cinpecan.engram.ui.viewmodel.EntityCitationState
import io.github.cinpecan.engram.ui.viewmodel.GraphCanvasState
import io.github.cinpecan.engram.ui.viewmodel.GraphIntent
import io.github.cinpecan.engram.ui.viewmodel.GraphMemoryOption
import io.github.cinpecan.engram.ui.viewmodel.GraphNodeUi
import io.github.cinpecan.engram.ui.viewmodel.GraphViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphRoute(
    initialMemoryId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: GraphViewModel = koinViewModel(),
    navigator: EngramNavigator = LocalEngramNavigator.current,
) {
    LaunchedEffect(initialMemoryId) {
        viewModel.onIntent(GraphIntent.BindMemory(initialMemoryId))
    }

    val availableMemories by viewModel.availableMemories.collectAsStateWithLifecycle()
    val canvasState by viewModel.canvasState.collectAsStateWithLifecycle()
    val entityCitation by viewModel.entityCitation.collectAsStateWithLifecycle()

    val sheetState = LocalModalBottomSheetState.current
    val scope = rememberCoroutineScope()
    val isSheetExpanded = sheetState?.currentValue == SheetValue.Expanded

    GraphScreen(
        availableMemories = availableMemories,
        canvasState = canvasState,
        entityCitation = entityCitation,
        isSheetExpanded = isSheetExpanded,
        onIntent = viewModel::onIntent,
        onToggleSheetHeight = {
            scope.launch {
                val target = sheetState ?: return@launch
                if (target.currentValue == SheetValue.Expanded) {
                    target.partialExpand()
                } else {
                    target.expand()
                }
            }
        },
        onDismissSheet = {
            scope.launch {
                sheetState?.hide()
                navigator.goBack()
            }
        },
        modifier = modifier,
    )
}

@Composable
fun GraphScreen(
    availableMemories: List<GraphMemoryOption>,
    canvasState: GraphCanvasState,
    entityCitation: EntityCitationState?,
    isSheetExpanded: Boolean,
    onIntent: (GraphIntent) -> Unit,
    onToggleSheetHeight: () -> Unit,
    onDismissSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deponderState = rememberDeponderState(
        physics = buildGraphRootPhysics(widthDp = 360f, heightDp = 300f),
    )
    val haptic = LocalHapticFeedback.current
    var hasRenderedAnchor by remember { mutableStateOf(false) }

    LaunchedEffect(isSheetExpanded) {
        if (hasRenderedAnchor) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else {
            hasRenderedAnchor = true
        }
    }

    val lineColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelBgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)

    // Consumes residual nested scroll from inside the Deponder canvas so ModalBottomSheet stays stable.
    val canvasNestedScrollBlocker = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = available

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity = available
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Sheet Header (remains draggable to stretch/collapse sheet)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = canvasState.memorySummary,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    FilledTonalButton(
                        onClick = { onIntent(GraphIntent.ReplayGrowth) },
                        enabled = canvasState.totalEdgeCount > 1 && !canvasState.isReplaying,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (canvasState.isReplaying) "生长中…" else "生长重演",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    IconButton(
                        onClick = { deponderState.reheat() },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "重新整理排版",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = onToggleSheetHeight,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = if (isSheetExpanded) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
                            contentDescription = if (isSheetExpanded) "收起至半屏" else "展开至全屏",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    AnimatedVisibility(
                        visible = isSheetExpanded,
                        enter = fadeIn(animationSpec = spring(dampingRatio = 1.0f, stiffness = 1600f)),
                        exit = fadeOut(animationSpec = spring(dampingRatio = 1.0f, stiffness = 1600f)),
                    ) {
                        IconButton(
                            onClick = onDismissSheet,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭脉络弹窗",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                if (availableMemories.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(availableMemories, key = { it.id }) { option ->
                            FilterChip(
                                selected = canvasState.activeMemoryId == option.id,
                                onClick = { onIntent(GraphIntent.BindMemory(option.id)) },
                                label = {
                                    Text(
                                        text = "${option.summarySnippet} (${option.edgeCount})",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        // Two-stage responsive canvas area: fits cleanly in 50% preview fold and expands smoothly to 92%
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val halfCanvasHeight = (maxHeight * 0.44f).coerceIn(200.dp, 270.dp)
            val targetCanvasHeight = if (isSheetExpanded) {
                if (entityCitation != null) (maxHeight - 148.dp).coerceAtLeast(240.dp) else maxHeight
            } else {
                if (entityCitation != null) (halfCanvasHeight - 68.dp).coerceAtLeast(156.dp) else halfCanvasHeight
            }

            val animatedCanvasHeight by animateDpAsState(
                targetValue = targetCanvasHeight,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
                label = "graphCanvasHeight",
            )

            // Anchor-driven physics & scale (never recalculated per drag frame)
            val rootPhysics = remember(maxWidth, targetCanvasHeight) {
                buildGraphRootPhysics(
                    widthDp = maxWidth.value,
                    heightDp = targetCanvasHeight.value,
                )
            }
            val autoScale = remember(maxWidth, targetCanvasHeight, canvasState.nodes.size) {
                computeAutoGraphScale(
                    widthDp = maxWidth.value,
                    heightDp = targetCanvasHeight.value,
                    nodeCount = canvasState.nodes.size,
                )
            }
            val seedRadiusDp = (minOf(maxWidth.value, targetCanvasHeight.value) * 0.21f)
                .coerceIn(52f, 88f)
                .dp

            LaunchedEffect(rootPhysics, autoScale, canvasState.activeMemoryId, isSheetExpanded) {
                deponderState.updatePhysics(rootPhysics)
                deponderState.submitScale(autoScale)
                deponderState.reheat()
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Optical inner radius: outer sheet 28dp - 12dp horizontal padding = 16dp
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(animatedCanvasHeight)
                        .nestedScroll(canvasNestedScrollBlocker),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    if (canvasState.nodes.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "当前记忆暂无多方实体关系，可先在主界面录入包含多人物或项目环节的笔记。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            // Sibling background gesture absorber: isolates missed drags on empty canvas
                            // without interfering with node-level `.deponderDrag()`.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, _ -> change.consume() }
                                    },
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clipToBounds()
                                    .deponderRoot(deponderState),
                            ) {
                                canvasState.visibleEdges.forEach { edge ->
                                    val edgeKey = if (edge.source <= edge.target) {
                                        "${edge.source}__${edge.target}"
                                    } else {
                                        "${edge.target}__${edge.source}"
                                    }
                                    val naturalLengthDp = computeAutoNaturalLengthDp(
                                        source = edge.source,
                                        target = edge.target,
                                        relation = edge.relation,
                                    )
                                    key(edgeKey) {
                                        DeponderDefaultRubber(
                                            startId = edge.source,
                                            endId = edge.target,
                                            label = edge.relation,
                                            arrowMode = if (edge.bidirectional) {
                                                DeponderArrowMode.BOTH
                                            } else {
                                                DeponderArrowMode.END
                                            },
                                            physics = RubberPhysics(
                                                elasticity = 5.6f,
                                                naturalLengthDp = naturalLengthDp,
                                            ),
                                            insets = DeponderRubberInsets.Default,
                                            style = DeponderRubberStyle.of(
                                                lineColor = lineColor,
                                                lineThickness = 2.dp,
                                                arrowSize = 8.dp,
                                                labelColor = labelColor,
                                                labelTextSize = 11.sp,
                                                labelBackgroundColor = labelBgColor,
                                            ),
                                        )
                                    }
                                }

                                val nodeCount = canvasState.nodes.size.coerceAtLeast(1)
                                val seedRadiusPx = with(LocalDensity.current) { seedRadiusDp.toPx() }
                                canvasState.nodes.forEachIndexed { index, node ->
                                    val angle = (2.0 * PI * index) / nodeCount
                                    val seedOffset = IntOffset(
                                        x = (cos(angle) * seedRadiusPx).roundToInt(),
                                        y = (sin(angle) * seedRadiusPx).roundToInt(),
                                    )
                                    key(node.id) {
                                        EntityCapsuleNode(
                                            node = node,
                                            isSelected = entityCitation?.entityName == node.id,
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onIntent(GraphIntent.SelectEntity(node.id))
                                            },
                                            modifier = Modifier
                                                .align(Alignment.Center)
                                                .offset { seedOffset }
                                                .deponderPlanet(
                                                    id = node.id,
                                                    physics = PlanetPhysics(
                                                        mass = 1.65f,
                                                        repulsionRadiusDp = 148f,
                                                        elasticity = 4.2f,
                                                        pinned = false,
                                                    ),
                                                )
                                                .deponderDrag(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Inline entity citation drawer inside the sheet
                entityCitation?.let { citation ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "实体溯源 · ${citation.entityName} (${citation.relatedMemories.size} 条关联)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                IconButton(
                                    onClick = { onIntent(GraphIntent.ClearSelectedEntity) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "关闭实体溯源")
                                }
                            }
                            citation.relatedMemories.take(if (isSheetExpanded) 3 else 1).forEach { item ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "• ${item.summary.ifBlank { item.content }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (isSheetExpanded && item.summary.isNotBlank() && item.content != item.summary) {
                                        Text(
                                            text = item.content,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun buildGraphRootPhysics(
    widthDp: Float,
    heightDp: Float,
): RootPhysics {
    val wallRepulsionDp = (minOf(widthDp, heightDp) * 0.34f).coerceIn(98f, 136f)
    return RootPhysics.fromPreset(DeponderDefaults.Presets.Crisp).copy(
        airDamping = 2.95f,
        wallRepulsionDp = wallRepulsionDp,
        wallElasticityStart = 5.8f,
        wallElasticityTop = 5.2f,
        wallElasticityEnd = 5.8f,
        wallElasticityBottom = 5.2f,
        minScale = 0.55f,
        maxScale = 1.25f,
    )
}

internal fun computeAutoGraphScale(
    widthDp: Float,
    heightDp: Float,
    nodeCount: Int,
): Float {
    val baseByCount = when {
        nodeCount <= 3 -> 0.94f
        nodeCount <= 5 -> 0.84f
        nodeCount <= 8 -> 0.74f
        else -> 0.66f
    }
    val viewportFactor = (minOf(widthDp, heightDp) / 360f).coerceIn(0.82f, 1.08f)
    return (baseByCount * viewportFactor).coerceIn(0.60f, 1.02f)
}

internal fun computeAutoNaturalLengthDp(
    source: String,
    target: String,
    relation: String,
): Float {
    val srcHalfDp = 12f + source.length * 5.2f
    val tgtHalfDp = 12f + target.length * 5.2f
    val labelWidthDp = 12f + relation.length * 10.5f
    val arrowAndBreathingDp = 36f
    return (srcHalfDp + tgtHalfDp + labelWidthDp + arrowAndBreathingDp).coerceIn(132f, 188f)
}

@Composable
private fun EntityCapsuleNode(
    node: GraphNodeUi,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            },
        ),
        shadowElevation = if (isSelected) 6.dp else 2.dp,
    ) {
        Text(
            text = node.label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}
