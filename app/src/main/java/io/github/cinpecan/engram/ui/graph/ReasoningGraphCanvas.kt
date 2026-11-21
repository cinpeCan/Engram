package io.github.cinpecan.engram.ui.graph

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinpe.deponder.compose.DeponderDefaultRubber
import com.cinpe.deponder.compose.DeponderRubberStyle
import com.cinpe.deponder.compose.deponderDrag
import com.cinpe.deponder.compose.deponderPlanet
import com.cinpe.deponder.compose.deponderRoot
import com.cinpe.deponder.compose.rememberDeponderState
import com.cinpe.deponder.core.DeponderArrowMode
import com.cinpe.deponder.core.DeponderRubberInsets
import io.github.cinpecan.engram.domain.rag.TraceEdge
import io.github.cinpecan.engram.ui.viewmodel.GraphNodeUi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Frameless Deponder canvas rendering the cross-memory reasoning subgraph walked by GraphRAG.
 * Seed entities (named in the question) are highlighted; canvas drags never dismiss the sheet.
 */
@Composable
fun ReasoningGraphCanvas(
    edges: List<TraceEdge>,
    seedEntities: Set<String>,
    modifier: Modifier = Modifier,
    height: Dp = 260.dp,
    onEntityClick: (String) -> Unit = {},
) {
    val nodes = remember(edges) {
        val degree = LinkedHashMap<String, Int>()
        edges.forEach { edge ->
            degree[edge.source] = (degree[edge.source] ?: 0) + 1
            degree[edge.target] = (degree[edge.target] ?: 0) + 1
        }
        degree.map { (id, deg) -> GraphNodeUi(id = id, label = id, degree = deg) }
    }
    if (nodes.isEmpty()) return

    val deponderState = rememberDeponderState(
        physics = buildGraphRootPhysics(widthDp = 360f, heightDp = height.value),
    )
    val lineColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelBgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
    val seedKeys = remember(seedEntities) { seedEntities.mapTo(HashSet()) { it.lowercase() } }

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
        val physics = remember(canvasWidthDp) {
            buildGraphRootPhysics(widthDp = canvasWidthDp, heightDp = height.value)
        }
        val scale = remember(canvasWidthDp, nodes.size) {
            computeAutoGraphScale(widthDp = canvasWidthDp, heightDp = height.value, nodeCount = nodes.size)
        }
        LaunchedEffect(physics, scale, edges) {
            deponderState.updatePhysics(physics)
            deponderState.submitScale(scale)
            deponderState.reheat()
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
            edges.forEach { edge ->
                val edgeKey = listOf(edge.source.lowercase(), edge.target.lowercase()).sorted().joinToString("__")
                key(edgeKey) {
                    DeponderDefaultRubber(
                        startId = edge.source,
                        endId = edge.target,
                        label = edge.relation,
                        arrowMode = if (edge.bidirectional) DeponderArrowMode.BOTH else DeponderArrowMode.END,
                        physics = GraphRubberPhysics,
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

            val seedRadiusPx = with(density) { (minOf(canvasWidthDp, height.value) * 0.24f).dp.toPx() }
            nodes.forEachIndexed { index, node ->
                val angle = (2.0 * PI * index) / nodes.size
                val seedOffset = IntOffset(
                    x = (cos(angle) * seedRadiusPx).roundToInt(),
                    y = (sin(angle) * seedRadiusPx).roundToInt(),
                )
                key(node.id) {
                    EntityCapsuleNode(
                        node = node,
                        isSelected = node.id.lowercase() in seedKeys,
                        onClick = { onEntityClick(node.id) },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset { seedOffset }
                            .deponderPlanet(id = node.id, physics = GraphPlanetPhysics)
                            .deponderDrag(),
                    )
                }
            }
        }
    }
}
