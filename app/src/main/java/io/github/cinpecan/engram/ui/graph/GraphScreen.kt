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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.cinpecan.engram.ui.viewmodel.GraphNodeUi
import io.github.cinpecan.engram.ui.viewmodel.GraphViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun GraphScreen(
    memoryId: String?,
    viewModel: GraphViewModel,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(memoryId) {
        viewModel.bindMemory(memoryId)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deponderState = rememberDeponderState(
        physics = RootPhysics.fromPreset(DeponderDefaults.Presets.Crisp).copy(
            airDamping = 2.85f,
            wallRepulsionDp = 36f,
            minScale = 0.55f,
            maxScale = 1.25f,
        ),
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
                        text = state.memoryTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = viewModel::replayStreamingGrowth,
                        enabled = state.totalEdgeCount > 1 && !state.isReplaying,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (state.isReplaying) "生长中…" else "生长重演",
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

                if (state.availableMemories.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.availableMemories, key = { it.id }) { option ->
                            FilterChip(
                                selected = state.activeMemoryId == option.id,
                                onClick = { viewModel.bindMemory(option.id) },
                                label = {
                                    Text(
                                        text = "${option.title} (${option.edgeCount})",
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
            if (state.nodes.isEmpty()) {
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
                    val autoScale = computeAutoGraphScale(
                        widthDp = maxWidth.value,
                        heightDp = maxHeight.value,
                        nodeCount = state.nodes.size,
                    )
                    LaunchedEffect(autoScale, state.activeMemoryId) {
                        deponderState.submitScale(autoScale)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .deponderRoot(deponderState),
                    ) {
                        state.visibleEdges.forEach { edge ->
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
                                        elasticity = 2.65f,
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

                        val nodeCount = state.nodes.size.coerceAtLeast(1)
                        val seedRadiusPx = with(LocalDensity.current) { 105.dp.toPx() }
                        state.nodes.forEachIndexed { index, node ->
                            val angle = (2.0 * PI * index) / nodeCount
                            val seedOffset = IntOffset(
                                x = (cos(angle) * seedRadiusPx).roundToInt(),
                                y = (sin(angle) * seedRadiusPx).roundToInt(),
                            )
                            key(node.id) {
                                EntityCapsuleNode(
                                    node = node,
                                    isSelected = state.selectedEntity == node.id,
                                    onClick = { viewModel.selectEntity(node.id) },
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .offset { seedOffset }
                                        .deponderPlanet(
                                            id = node.id,
                                            physics = PlanetPhysics(
                                                repulsionRadiusDp = 190f,
                                                elasticity = 2.25f,
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
        val selectedEntity = state.selectedEntity
        if (selectedEntity != null) {
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
                            text = "实体溯源 · $selectedEntity (${state.relatedMemories.size} 条关联)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(
                            onClick = viewModel::clearSelectedEntity,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭")
                        }
                    }
                    state.relatedMemories.take(2).forEach { item ->
                        Text(
                            text = "• ${item.content}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun computeAutoGraphScale(
    widthDp: Float,
    heightDp: Float,
    nodeCount: Int,
): Float {
    val baseByCount = when {
        nodeCount <= 3 -> 0.95f
        nodeCount <= 5 -> 0.86f
        nodeCount <= 8 -> 0.78f
        else -> 0.70f
    }
    val viewportFactor = (minOf(widthDp, heightDp) / 380f).coerceIn(0.85f, 1.12f)
    return (baseByCount * viewportFactor).coerceIn(0.62f, 1.05f)
}

private fun computeAutoNaturalLengthDp(
    source: String,
    target: String,
    relation: String,
): Float {
    val srcHalfDp = 14f + source.length * 6.5f
    val tgtHalfDp = 14f + target.length * 6.5f
    val labelWidthDp = 14f + relation.length * 11.5f
    val arrowAndBreathingDp = 52f
    return (srcHalfDp + tgtHalfDp + labelWidthDp + arrowAndBreathingDp).coerceIn(155f, 255f)
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
