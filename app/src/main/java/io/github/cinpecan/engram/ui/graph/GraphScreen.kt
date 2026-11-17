package io.github.cinpecan.engram.ui.graph

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import io.github.cinpecan.engram.ui.navigation.EngramNavKey
import io.github.cinpecan.engram.ui.navigation.EngramNavigator
import io.github.cinpecan.engram.ui.navigation.LocalEngramNavigator
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
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun GraphRoute(
    modifier: Modifier = Modifier,
    viewModel: GraphViewModel = koinViewModel(),
    navigator: EngramNavigator = LocalEngramNavigator.current,
) {
    val selectedMemoryId by navigator.selectedGraphMemoryId.collectAsStateWithLifecycle()
    LaunchedEffect(selectedMemoryId) {
        viewModel.onIntent(GraphIntent.BindMemory(selectedMemoryId))
    }

    val availableMemories by viewModel.availableMemories.collectAsStateWithLifecycle()
    val canvasState by viewModel.canvasState.collectAsStateWithLifecycle()
    val entityCitation by viewModel.entityCitation.collectAsStateWithLifecycle()

    GraphScreen(
        availableMemories = availableMemories,
        canvasState = canvasState,
        entityCitation = entityCitation,
        onIntent = { intent ->
            if (intent is GraphIntent.BindMemory) {
                navigator.selectGraphMemory(intent.memoryId)
            }
            viewModel.onIntent(intent)
        },
        modifier = modifier,
        onOpenRawMemory = { id -> navigator.openOverlay(EngramNavKey.RawMemory(id)) },
    )
}

@Composable
fun GraphScreen(
    availableMemories: List<GraphMemoryOption>,
    canvasState: GraphCanvasState,
    entityCitation: EntityCitationState?,
    onIntent: (GraphIntent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenRawMemory: (String) -> Unit = {},
) {
    val deponderState = rememberDeponderState(
        physics = buildGraphRootPhysics(widthDp = 360f, heightDp = 520f),
    )

    val lineColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelBgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
                    Spacer(modifier = Modifier.width(8.dp))
                    canvasState.activeMemoryId?.let { activeId ->
                        IconButton(
                            onClick = { onOpenRawMemory(activeId) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Subject,
                                contentDescription = "查看原文",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
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
                        Spacer(modifier = Modifier.width(4.dp))
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

        // 2D Deponder Force-Directed Graph Canvas (auto-scaled and auto-spaced)
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (canvasState.nodes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "当前记忆暂无多方实体关系，可先在「印迹」页录入包含多人物或项目环节的笔记。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    val rootPhysics = remember(maxWidth, maxHeight) {
                        buildGraphRootPhysics(
                            widthDp = maxWidth.value,
                            heightDp = maxHeight.value,
                        )
                    }
                    val autoScale = computeAutoGraphScale(
                        widthDp = maxWidth.value,
                        heightDp = maxHeight.value,
                        nodeCount = canvasState.nodes.size,
                    )
                    val seedRadiusDp = (minOf(maxWidth.value, maxHeight.value) * 0.21f)
                        .coerceIn(60f, 88f)
                        .dp
                    LaunchedEffect(rootPhysics, autoScale, canvasState.activeMemoryId) {
                        deponderState.updatePhysics(rootPhysics)
                        deponderState.submitScale(autoScale)
                    }

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
                                    onClick = { onIntent(GraphIntent.SelectEntity(node.id)) },
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

        // Selected Node RAG Citation Sheet
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
                            Icon(Icons.Rounded.Close, contentDescription = "关闭")
                        }
                    }
                    citation.relatedMemories.take(2).forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenRawMemory(item.id) }
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "• ${item.summary.ifBlank { item.content }}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "原文",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            )
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
