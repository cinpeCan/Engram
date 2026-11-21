package io.github.cinpecan.engram.ui.graph

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Polyline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinpe.deponder.compose.DeponderDefaultRubber
import com.cinpe.deponder.compose.DeponderRubberStyle
import com.cinpe.deponder.compose.DeponderState
import com.cinpe.deponder.compose.deponderDrag
import com.cinpe.deponder.compose.deponderPlanet
import com.cinpe.deponder.compose.deponderRoot
import com.cinpe.deponder.core.DeponderArrowMode
import com.cinpe.deponder.core.DeponderRubberInsets
import io.github.cinpecan.engram.domain.rag.TraceEdge
import io.github.cinpecan.engram.ui.viewmodel.GraphNodeUi
import java.util.Locale

/**
 * Frameless Deponder canvas rendering the cross-memory reasoning subgraph walked by GraphRAG.
 * Seed entities (named in the question) and the tapped node are highlighted; canvas drags never
 * dismiss the sheet.
 */
@Composable
fun ReasoningGraphCanvas(
    edges: List<TraceEdge>,
    seedEntities: Set<String>,
    modifier: Modifier = Modifier,
    height: Dp = 260.dp,
    selectedEntity: String? = null,
    onEntityClick: (String) -> Unit = {},
) {
    val sanitizedEdges = remember(edges) { canonicalizeTraceEdges(edges) }
    val nodes = remember(sanitizedEdges) {
        val degree = LinkedHashMap<String, Int>()
        sanitizedEdges.forEach { edge ->
            degree[edge.source] = (degree[edge.source] ?: 0) + 1
            degree[edge.target] = (degree[edge.target] ?: 0) + 1
        }
        degree.map { (id, deg) -> GraphNodeUi(id = id, label = id, degree = deg) }
    }
    if (nodes.isEmpty()) return

    val topology = remember(nodes, sanitizedEdges) {
        analyzeGraphTopology(
            nodeIds = nodes.map { it.id },
            edges = sanitizedEdges.map { it.source to it.target },
        )
    }

    var isUnfoldEnabled by remember(sanitizedEdges) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val lineColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelBgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
    val seedKeys = remember(seedEntities) {
        seedEntities.mapTo(HashSet()) { it.trim().lowercase(Locale.US) }
    }

    val nestedScrollBlocker = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                available

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .nestedScroll(nestedScrollBlocker),
    ) {
        val density = LocalDensity.current
        val canvasWidthDp = maxWidth.value
        val physics = remember(canvasWidthDp, height, isUnfoldEnabled) {
            buildGraphRootPhysics(
                widthDp = canvasWidthDp,
                heightDp = height.value,
                unfoldEnabled = isUnfoldEnabled,
            )
        }
        val rubberPhysics = remember(topology.diameterHops, isUnfoldEnabled) {
            buildGraphRubberPhysics(
                diameterHops = topology.diameterHops,
                unfoldEnabled = isUnfoldEnabled,
            )
        }
        val scale = remember(canvasWidthDp, height, topology.nodeCount, topology.diameterHops, isUnfoldEnabled) {
            computeAutoGraphScale(
                widthDp = canvasWidthDp,
                heightDp = height.value,
                nodeCount = topology.nodeCount,
                diameterHops = topology.diameterHops,
                unfoldEnabled = isUnfoldEnabled,
            )
        }

        val deponderState = remember(sanitizedEdges) {
            DeponderState(physics = physics)
        }
        SideEffect {
            deponderState.updatePhysics(physics)
            deponderState.submitScale(scale)
        }

        val seedOffsets = remember(topology, maxWidth, height, density) {
            computeSeedOffsetsPx(
                orderedNodeIds = topology.orderedNodeIds,
                diameterHops = topology.diameterHops,
                widthPx = with(density) { maxWidth.toPx() },
                heightPx = with(density) { height.toPx() },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume() } },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .deponderRoot(deponderState),
        ) {
            sanitizedEdges.forEach { edge ->
                val edgeKey = listOf(
                    edge.source.lowercase(Locale.US),
                    edge.target.lowercase(Locale.US),
                ).sorted().joinToString("__")
                key(edgeKey) {
                    DeponderDefaultRubber(
                        startId = edge.source,
                        endId = edge.target,
                        label = edge.relation,
                        arrowMode = if (edge.bidirectional) DeponderArrowMode.BOTH else DeponderArrowMode.END,
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

            nodes.forEach { node ->
                val seedOffset = seedOffsets[node.id] ?: IntOffset.Zero
                val isSelected = node.id.lowercase(Locale.US) in seedKeys ||
                    (!selectedEntity.isNullOrBlank() && node.id.equals(selectedEntity, ignoreCase = true))
                key(node.id) {
                    EntityCapsuleNode(
                        node = node,
                        isSelected = isSelected,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onEntityClick(node.id)
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset { seedOffset }
                            .deponderPlanet(id = node.id, physics = GraphPlanetPhysics)
                            .deponderDrag(),
                    )
                }
            }
        }

        if (nodes.size >= 3) {
            AdaptiveGraphActionPill(
                icon = Icons.Rounded.Polyline,
                label = "Unfold",
                showLabel = false,
                active = isUnfoldEnabled,
                compactSize = 32.dp,
                contentDescription = if (isUnfoldEnabled) {
                    "Disable topological unfold"
                } else {
                    "Unfold reasoning graph"
                },
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    isUnfoldEnabled = !isUnfoldEnabled
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 10.dp),
            )
        }
    }
}

internal fun canonicalizeTraceEdges(edges: List<TraceEdge>): List<TraceEdge> {
    val canonicalNames = HashMap<String, String>()
    val deduped = LinkedHashMap<Pair<String, String>, TraceEdge>()
    edges.forEach { raw ->
        val trimmedSource = raw.source.trim()
        val trimmedTarget = raw.target.trim()
        if (trimmedSource.isEmpty() || trimmedTarget.isEmpty()) return@forEach
        val sKey = trimmedSource.lowercase(Locale.US)
        val tKey = trimmedTarget.lowercase(Locale.US)
        if (sKey == tKey) return@forEach

        val canonicalSource = canonicalNames.getOrPut(sKey) { trimmedSource }
        val canonicalTarget = canonicalNames.getOrPut(tKey) { trimmedTarget }
        val pairKey = if (sKey <= tKey) sKey to tKey else tKey to sKey
        val existing = deduped[pairKey]
        deduped[pairKey] = when {
            existing == null -> raw.copy(source = canonicalSource, target = canonicalTarget)
            existing.source.equals(canonicalSource, ignoreCase = true) -> existing
            else -> existing.copy(bidirectional = true)
        }
    }
    return deduped.values.toList()
}
