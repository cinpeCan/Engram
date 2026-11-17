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
import io.github.cinpecan.engram.ui.components.ExpressivePillSurface
import io.github.cinpecan.engram.ui.navigation.LocalModalBottomSheetState
import io.github.cinpecan.engram.ui.viewmodel.EntityCitationState
import io.github.cinpecan.engram.ui.viewmodel.GraphCanvasState
import io.github.cinpecan.engram.ui.viewmodel.GraphIntent
import io.github.cinpecan.engram.ui.viewmodel.GraphNodeUi
import io.github.cinpecan.engram.ui.viewmodel.GraphViewModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
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

            // Anchor-driven physics & scale (recomputed only on state targets, never per drag frame)
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
                .coerceIn(52f, 92f)
                .dp

            LaunchedEffect(rootPhysics, autoScale, canvasState.memoryId, isSheetExpanded) {
                deponderState.updatePhysics(rootPhysics)
                deponderState.submitScale(autoScale)
                deponderState.reheat()
            }

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
                                text = "该印迹暂未提取到多方实体关系",
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
                            val seedRadiusPx = with(density) { seedRadiusDp.toPx() }
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

                        if (canvasState.nodes.size >= 2) {
                            ExpressivePillSurface(
                                onClick = {
                                    if (canvasState.totalEdgeCount > 1) {
                                        onIntent(GraphIntent.ReplayGrowth)
                                    }
                                    deponderState.reheat()
                                },
                                enabled = !canvasState.isReplaying,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 16.dp, bottom = 12.dp)
                                    .size(36.dp),
                                restCorner = 18.dp,
                                pressedCorner = 10.dp,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                                pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                contentDescription = "重播脉络推演",
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Replay,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
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
                                    text = "实体关联 · ${citation.entityName} (${citation.relatedMemories.size})",
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
                                    contentDescription = "关闭实体关联",
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
