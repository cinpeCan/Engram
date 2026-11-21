package io.github.cinpecan.engram.ui.graph

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Polyline
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinpe.deponder.compose.DeponderDefaultRubber
import com.cinpe.deponder.compose.DeponderRubberStyle
import com.cinpe.deponder.compose.DeponderState
import com.cinpe.deponder.compose.deponderDrag
import com.cinpe.deponder.compose.deponderPlanet
import com.cinpe.deponder.compose.deponderRoot
import com.cinpe.deponder.core.DeponderArrowMode
import com.cinpe.deponder.core.DeponderRubberInsets
import io.github.cinpecan.engram.ui.components.ExpressivePillSurface
import io.github.cinpecan.engram.ui.navigation.LocalModalBottomSheetState
import io.github.cinpecan.engram.ui.viewmodel.EntityCitationState
import io.github.cinpecan.engram.ui.viewmodel.GraphCanvasState
import io.github.cinpecan.engram.ui.viewmodel.GraphIntent
import io.github.cinpecan.engram.ui.viewmodel.GraphNodeUi
import io.github.cinpecan.engram.ui.viewmodel.GraphViewModel
import kotlin.math.abs
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphRoute(
    memoryId: String,
    modifier: Modifier = Modifier,
    viewModel: GraphViewModel = koinViewModel(key = memoryId) { parametersOf(memoryId) },
) {
    val canvasState by viewModel.canvasState.collectAsStateWithLifecycle()
    val entityCitation by viewModel.entityCitation.collectAsStateWithLifecycle()

    val sheetState = LocalModalBottomSheetState.current
    val isSheetExpanded = sheetState?.targetValue == SheetValue.Expanded ||
        sheetState?.currentValue == SheetValue.Expanded

    GraphScreen(
        canvasState = canvasState,
        entityCitation = entityCitation,
        isSheetExpanded = isSheetExpanded,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

@Composable
fun GraphScreen(
    canvasState: GraphCanvasState,
    entityCitation: EntityCitationState?,
    isSheetExpanded: Boolean,
    onIntent: (GraphIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var hasRenderedAnchor by remember { mutableStateOf(false) }

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
            .navigationBarsPadding(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val density = LocalDensity.current
            val windowHeightDp = with(density) {
                LocalWindowInfo.current.containerSize.height.takeIf { it > 0 }?.toDp()
            } ?: (maxHeight / 0.85f)
            val navBottomDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

            val baseVisibleHeight = if (isSheetExpanded) {
                maxHeight
            } else {
                (windowHeightDp * 0.50f - 52.dp - navBottomDp).coerceIn(220.dp, maxHeight * 0.62f)
            }
            val targetCanvasHeight = if (entityCitation != null) {
                (baseVisibleHeight - 136.dp).coerceAtLeast(180.dp)
            } else {
                baseVisibleHeight
            }

            val animatedCanvasHeight by animateDpAsState(
                targetValue = targetCanvasHeight,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                label = "graphCanvasHeight",
            )

            val topology = remember(canvasState.nodes, canvasState.visibleEdges) {
                analyzeGraphTopology(
                    nodeIds = canvasState.nodes.map { it.id },
                    edges = canvasState.visibleEdges.map { it.source to it.target },
                )
            }

            // Anchor-driven physics & scale (recomputed only on state targets, never per drag frame)
            val rootPhysics = remember(maxWidth, targetCanvasHeight, canvasState.isUnfoldEnabled) {
                buildGraphRootPhysics(
                    widthDp = maxWidth.value,
                    heightDp = targetCanvasHeight.value,
                    unfoldEnabled = canvasState.isUnfoldEnabled,
                )
            }
            val rubberPhysics = remember(topology.diameterHops, canvasState.isUnfoldEnabled) {
                buildGraphRubberPhysics(
                    diameterHops = topology.diameterHops,
                    unfoldEnabled = canvasState.isUnfoldEnabled,
                )
            }
            val autoScale = remember(
                maxWidth,
                targetCanvasHeight,
                topology.nodeCount,
                topology.diameterHops,
                canvasState.isUnfoldEnabled,
            ) {
                computeAutoGraphScale(
                    widthDp = maxWidth.value,
                    heightDp = targetCanvasHeight.value,
                    nodeCount = topology.nodeCount,
                    diameterHops = topology.diameterHops,
                    unfoldEnabled = canvasState.isUnfoldEnabled,
                )
            }

            val deponderState = remember(canvasState.memoryId) {
                DeponderState(physics = rootPhysics)
            }
            SideEffect {
                deponderState.updatePhysics(rootPhysics)
                deponderState.submitScale(autoScale)
            }

            LaunchedEffect(isSheetExpanded) {
                if (hasRenderedAnchor) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                } else {
                    hasRenderedAnchor = true
                }
                deponderState.reheat()
            }

            val seedOffsets = remember(topology, maxWidth, targetCanvasHeight, density) {
                computeSeedOffsetsPx(
                    orderedNodeIds = topology.orderedNodeIds,
                    diameterHops = topology.diameterHops,
                    widthPx = with(density) { maxWidth.toPx() },
                    heightPx = with(density) { targetCanvasHeight.toPx() },
                )
            }
            val showControlLabels = isSheetExpanded &&
                entityCitation == null &&
                maxWidth >= 320.dp &&
                targetCanvasHeight >= 360.dp

            val latestNodes = rememberUpdatedState(canvasState.nodes)
            var lastViewportHeightPx by remember(canvasState.memoryId) { mutableFloatStateOf(0f) }

            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(animatedCanvasHeight)
                        .nestedScroll(canvasNestedScrollBlocker),
                ) {
                    if (canvasState.nodes.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No entity relations extracted from this memory yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    } else {
                        // Sibling empty-canvas drag absorber: prevents accidental sheet dismissal
                        // while preserving node-level `.deponderDrag()`.
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
                                .onSizeChanged { size ->
                                    val newHeightPx = size.height.toFloat()
                                    val dy = computeRecenterDeltaYPx(lastViewportHeightPx, newHeightPx)
                                    if (dy != 0f) {
                                        latestNodes.value.forEach { node ->
                                            deponderState.dragBy(node.id, dxPx = 0f, dyPx = dy)
                                        }
                                    }
                                    if (newHeightPx > 0f) {
                                        lastViewportHeightPx = newHeightPx
                                    }
                                }
                                .deponderRoot(deponderState),
                        ) {
                            canvasState.visibleEdges.forEach { edge ->
                                val edgeKey = if (edge.source <= edge.target) {
                                    "${edge.source}__${edge.target}"
                                } else {
                                    "${edge.target}__${edge.source}"
                                }
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
                                        physics = rubberPhysics,
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

                            canvasState.nodes.forEach { node ->
                                val seedOffset = seedOffsets[node.id] ?: IntOffset.Zero
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
                                                physics = GraphPlanetPhysics,
                                            )
                                            .deponderDrag(),
                                    )
                                }
                            }
                        }

                        if (canvasState.nodes.size >= 2) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 16.dp, bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (canvasState.nodes.size >= 3) {
                                    AdaptiveGraphActionPill(
                                        icon = Icons.Rounded.Polyline,
                                        label = "Unfold",
                                        showLabel = showControlLabels,
                                        active = canvasState.isUnfoldEnabled,
                                        contentDescription = if (canvasState.isUnfoldEnabled) {
                                            "Disable topological unfold"
                                        } else {
                                            "Unfold graph topology"
                                        },
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onIntent(GraphIntent.ToggleUnfold)
                                        },
                                    )
                                }

                                AdaptiveGraphActionPill(
                                    icon = Icons.Rounded.Replay,
                                    label = "Replay",
                                    showLabel = showControlLabels,
                                    enabled = !canvasState.isReplaying,
                                    contentDescription = "Replay graph layout",
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (canvasState.totalEdgeCount > 1) {
                                            onIntent(GraphIntent.ReplayGrowth)
                                        }
                                        deponderState.reheat()
                                    },
                                )
                            }
                        }
                    }
                }

                entityCitation?.let { citation ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                    text = "Linked memories · ${citation.entityName} (${citation.relatedMemories.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                ExpressivePillSurface(
                                    onClick = { onIntent(GraphIntent.ClearSelectedEntity) },
                                    modifier = Modifier.size(28.dp),
                                    restCorner = 14.dp,
                                    pressedCorner = 8.dp,
                                    containerColor = Color.Transparent,
                                    pressedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    contentDescription = "Close linked memories",
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            citation.relatedMemories.take(if (isSheetExpanded) 3 else 1).forEach { item ->
                                val snippet = item.summary.ifBlank { item.content }
                                    .replace('\n', ' ')
                                    .trim()
                                Text(
                                    text = snippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
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

internal fun computeRecenterDeltaYPx(
    previousHeightPx: Float,
    newHeightPx: Float,
): Float {
    if (previousHeightPx <= 0f || newHeightPx <= 0f) return 0f
    val delta = newHeightPx - previousHeightPx
    return if (abs(delta) <= 1f) 0f else delta * 0.5f
}

@Composable
internal fun EntityCapsuleNode(
    node: GraphNodeUi,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpressivePillSurface(
        onClick = onClick,
        modifier = modifier,
        restCorner = 24.dp,
        pressedCorner = 12.dp,
        containerColor = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        contentColor = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
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
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}
