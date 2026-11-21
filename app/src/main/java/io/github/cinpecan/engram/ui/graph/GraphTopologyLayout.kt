package io.github.cinpecan.engram.ui.graph

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cinpe.deponder.core.DeponderDefaults
import com.cinpe.deponder.core.PlanetPhysics
import com.cinpe.deponder.core.RootPhysics
import com.cinpe.deponder.core.RubberPhysics
import io.github.cinpecan.engram.ui.components.ExpressivePillSurface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Immutable
internal data class GraphTopologyMetrics(
    val nodeCount: Int,
    val edgeCount: Int,
    val diameterHops: Int,
    val maxDegree: Int,
    val orderedNodeIds: List<String>,
) {
    val isLinearChain: Boolean
        get() = nodeCount >= 4 && diameterHops >= nodeCount - 2 && maxDegree <= 2
}

/**
 * Computes hop diameter, max degree, and a peripheral trail ordering for undirected graph nodes
 * so long linear chains fold cleanly into a serpentine ribbon without chord jumps.
 */
internal fun analyzeGraphTopology(
    nodeIds: List<String>,
    edges: List<Pair<String, String>>,
): GraphTopologyMetrics {
    val canonicalIds = nodeIds.distinct()
    val n = canonicalIds.size
    if (n == 0) {
        return GraphTopologyMetrics(
            nodeCount = 0,
            edgeCount = 0,
            diameterHops = 0,
            maxDegree = 0,
            orderedNodeIds = emptyList(),
        )
    }

    val indexOfId = HashMap<String, Int>(n * 2)
    canonicalIds.forEachIndexed { idx, id -> indexOfId[id] = idx }

    val adj = Array(n) { LinkedHashSet<Int>() }
    edges.forEach { (rawU, rawV) ->
        val u = indexOfId[rawU] ?: return@forEach
        val v = indexOfId[rawV] ?: return@forEach
        if (u != v) {
            adj[u].add(v)
            adj[v].add(u)
        }
    }

    var halfEdges = 0
    var maxDegree = 0
    for (i in 0 until n) {
        val deg = adj[i].size
        halfEdges += deg
        if (deg > maxDegree) maxDegree = deg
    }
    val edgeCount = halfEdges / 2

    val visitedComp = BooleanArray(n)
    val bfsQueue = IntArray(n)
    val multiComponents = ArrayList<List<Int>>()
    val isolatedNodes = ArrayList<Int>()

    for (root in 0 until n) {
        if (visitedComp[root]) continue
        if (adj[root].isEmpty()) {
            visitedComp[root] = true
            isolatedNodes.add(root)
            continue
        }
        var qHead = 0
        var qTail = 0
        visitedComp[root] = true
        bfsQueue[qTail++] = root
        while (qHead < qTail) {
            val u = bfsQueue[qHead++]
            for (v in adj[u]) {
                if (!visitedComp[v]) {
                    visitedComp[v] = true
                    bfsQueue[qTail++] = v
                }
            }
        }
        val comp = ArrayList<Int>(qTail)
        for (i in 0 until qTail) {
            comp.add(bfsQueue[i])
        }
        multiComponents.add(comp)
    }

    multiComponents.sortWith(
        compareByDescending<List<Int>> { it.size }
            .thenBy { it.first() },
    )

    var maxDiameterHops = 0
    val orderedIndices = ArrayList<Int>(n)
    val dist = IntArray(n) { -1 }
    val trailVisited = BooleanArray(n)

    for (comp in multiComponents) {
        val m = comp.size
        val eccentricity = HashMap<Int, Int>(m * 2)
        var compDiameter = 0

        for (src in comp) {
            for (member in comp) {
                dist[member] = -1
            }
            var qHead = 0
            var qTail = 0
            dist[src] = 0
            bfsQueue[qTail++] = src
            var maxDist = 0
            while (qHead < qTail) {
                val u = bfsQueue[qHead++]
                val du = dist[u]
                if (du > maxDist) maxDist = du
                for (v in adj[u]) {
                    if (dist[v] == -1) {
                        dist[v] = du + 1
                        bfsQueue[qTail++] = v
                    }
                }
            }
            eccentricity[src] = maxDist
            if (maxDist > compDiameter) compDiameter = maxDist
        }

        if (compDiameter > maxDiameterHops) maxDiameterHops = compDiameter

        val start = comp.minWith(
            compareBy<Int> { if (adj[it].size == 1) 0 else adj[it].size }
                .thenByDescending { eccentricity[it] ?: 0 }
                .thenBy { it },
        )

        val stack = ArrayDeque<Int>(m)
        trailVisited[start] = true
        orderedIndices.add(start)
        stack.addLast(start)

        while (stack.isNotEmpty()) {
            val curr = stack.last()
            var nextChoice = -1
            var bestUnvisitedDeg = Int.MAX_VALUE
            var bestEcc = Int.MIN_VALUE

            for (nb in adj[curr]) {
                if (trailVisited[nb]) continue
                var unvisitedDeg = 0
                for (nb2 in adj[nb]) {
                    if (!trailVisited[nb2]) unvisitedDeg++
                }
                val ecc = eccentricity[nb] ?: 0
                val isBetter = nextChoice == -1 ||
                    unvisitedDeg < bestUnvisitedDeg ||
                    (unvisitedDeg == bestUnvisitedDeg && ecc > bestEcc) ||
                    (unvisitedDeg == bestUnvisitedDeg && ecc == bestEcc && nb < nextChoice)
                if (isBetter) {
                    nextChoice = nb
                    bestUnvisitedDeg = unvisitedDeg
                    bestEcc = ecc
                }
            }

            if (nextChoice != -1) {
                trailVisited[nextChoice] = true
                orderedIndices.add(nextChoice)
                stack.addLast(nextChoice)
            } else {
                stack.removeLast()
            }
        }
    }

    orderedIndices.addAll(isolatedNodes)

    return GraphTopologyMetrics(
        nodeCount = n,
        edgeCount = edgeCount,
        diameterHops = maxDiameterHops,
        maxDegree = maxDegree,
        orderedNodeIds = orderedIndices.map { canonicalIds[it] },
    )
}

/**
 * Seeds node offsets strictly within central safe viewport bounds (`<= 28%` half-width, `<= 27%`
 * half-height). Large or high-diameter topologies are folded into a boustrophedon (serpentine)
 * S-ribbon aligned with the longer viewport axis so adjacent chain nodes start close without
 * horizontal/vertical overflow.
 */
internal fun computeSeedOffsetsPx(
    orderedNodeIds: List<String>,
    diameterHops: Int,
    widthPx: Float,
    heightPx: Float,
): Map<String, IntOffset> {
    val n = orderedNodeIds.size
    if (n == 0) return emptyMap()
    if (n == 1) return mapOf(orderedNodeIds[0] to IntOffset.Zero)

    val safeW = widthPx.coerceAtLeast(160f)
    val safeH = heightPx.coerceAtLeast(160f)
    val maxHalfX = (safeW * 0.28f).coerceAtLeast(32f)
    val maxHalfY = (safeH * 0.27f).coerceAtLeast(28f)
    val result = LinkedHashMap<String, IntOffset>(n * 2)

    if (n == 2) {
        if (safeH > safeW * 1.2f) {
            val ry = min(maxHalfY, safeH * 0.20f)
            result[orderedNodeIds[0]] = IntOffset(0, (-ry).roundToInt())
            result[orderedNodeIds[1]] = IntOffset(0, ry.roundToInt())
        } else {
            val rx = min(maxHalfX, safeW * 0.20f)
            result[orderedNodeIds[0]] = IntOffset((-rx).roundToInt(), 0)
            result[orderedNodeIds[1]] = IntOffset(rx.roundToInt(), 0)
        }
        return result
    }

    if (n <= 5 && diameterHops <= 2) {
        val rx = min(maxHalfX, safeW * 0.22f)
        val ry = min(maxHalfY, safeH * 0.22f)
        orderedNodeIds.forEachIndexed { index, id ->
            val angle = -PI * 0.5 + (2.0 * PI * index) / n
            result[id] = IntOffset(
                x = (cos(angle) * rx).roundToInt(),
                y = (sin(angle) * ry).roundToInt(),
            )
        }
        return result
    }

    val portraitMajor = safeH > safeW * 1.15f
    val laneCap = if (n <= 6) 2 else 3
    val passes = ((n + laneCap - 1) / laneCap).coerceAtLeast(2)

    orderedNodeIds.forEachIndexed { index, id ->
        val p = index / laneCap
        val sRaw = index % laneCap
        val countInPass = minOf(laneCap, n - p * laneCap)
        val s = if (p % 2 == 0) sRaw else (countInPass - 1 - sRaw)

        val uProg = if (passes <= 1) 0f else (p.toFloat() / (passes - 1).toFloat()) * 2f - 1f
        val uCross = if (countInPass <= 1) 0f else (s.toFloat() / (countInPass - 1).toFloat()) * 2f - 1f
        val stagger = if (index % 2 == 0) 0.08f else -0.08f

        val rawX: Float
        val rawY: Float
        if (portraitMajor) {
            rawX = uCross * maxHalfX + stagger * maxHalfX * 0.35f
            rawY = uProg * maxHalfY + stagger * maxHalfY * 0.20f
        } else {
            rawX = uProg * maxHalfX + stagger * maxHalfX * 0.20f
            rawY = uCross * maxHalfY + stagger * maxHalfY * 0.35f
        }
        result[id] = IntOffset(
            x = rawX.coerceIn(-maxHalfX, maxHalfX).roundToInt(),
            y = rawY.coerceIn(-maxHalfY, maxHalfY).roundToInt(),
        )
    }
    return result
}

internal fun buildGraphRootPhysics(
    widthDp: Float,
    heightDp: Float,
    unfoldEnabled: Boolean = false,
): RootPhysics {
    val wallRepulsionDp = (minOf(widthDp, heightDp) * 0.34f).coerceIn(98f, 136f)
    return RootPhysics.fromPreset(DeponderDefaults.Presets.Crisp).copy(
        airDamping = 2.95f,
        wallRepulsionDp = wallRepulsionDp,
        wallElasticityStart = 6.2f,
        wallElasticityTop = 5.6f,
        wallElasticityEnd = 6.2f,
        wallElasticityBottom = 5.6f,
        minScale = 0.36f,
        maxScale = 1.25f,
    ).withUnfold(unfoldEnabled)
}

/**
 * Content-adaptive spring length (Deponder 0.6.0): rest length follows the measured relation label
 * width plus breathing room for both entity capsules and arrowheads, clamped to a readable range.
 */
internal val GraphRubberPhysics: RubberPhysics = RubberPhysics.auto(
    contentPaddingDp = 84f,
    minLengthDp = 132f,
    maxLengthDp = 188f,
    elasticity = 6.6f,
)

internal fun buildGraphRubberPhysics(
    diameterHops: Int = 2,
    unfoldEnabled: Boolean = false,
): RubberPhysics = if ((unfoldEnabled && diameterHops >= 4) || diameterHops >= 6) {
    RubberPhysics.auto(
        contentPaddingDp = 60f,
        minLengthDp = 98f,
        maxLengthDp = 148f,
        elasticity = 6.6f,
    )
} else {
    GraphRubberPhysics
}

internal val GraphPlanetPhysics: PlanetPhysics = PlanetPhysics(
    mass = 1.65f,
    repulsionRadiusDp = 148f,
    elasticity = 4.8f,
    pinned = false,
)

internal fun computeAutoGraphScale(
    widthDp: Float,
    heightDp: Float,
    nodeCount: Int,
    diameterHops: Int = (nodeCount - 1).coerceAtLeast(1).coerceAtMost(3),
    unfoldEnabled: Boolean = false,
): Float {
    val baseByCount = when {
        nodeCount <= 3 -> 0.94f
        nodeCount <= 5 -> 0.84f
        nodeCount <= 8 -> 0.74f
        nodeCount <= 11 -> 0.65f
        else -> 0.58f
    }
    val viewportFactor = (minOf(widthDp, heightDp) / 360f).coerceIn(0.82f, 1.08f)
    val countScale = (baseByCount * viewportFactor).coerceIn(0.50f, 1.02f)

    return when {
        unfoldEnabled && diameterHops >= 3 -> {
            val usableMajorSpanDp = (maxOf(widthDp, heightDp) * 0.78f).coerceAtLeast(160f)
            val perHopDp = if (diameterHops >= 4) 108f else 136f
            val unfoldFitScale = (usableMajorSpanDp / (diameterHops * perHopDp)).coerceIn(0.38f, 1.02f)
            minOf(countScale, unfoldFitScale)
        }
        diameterHops >= 5 -> {
            val foldedCap = (minOf(widthDp, heightDp) / (112f * sqrt(diameterHops.toFloat())))
                .coerceIn(0.48f, 1.02f)
            minOf(countScale, foldedCap)
        }
        else -> countScale
    }
}

/**
 * Space-adaptive floating action pill: renders a crisp icon-only circle when compact, and expands
 * smoothly to reveal its text label when ample expanded viewport space is available.
 */
@Composable
internal fun AdaptiveGraphActionPill(
    icon: ImageVector,
    label: String,
    showLabel: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    compactSize: Dp = 36.dp,
) {
    val containerColor = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.80f)
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val border = if (active) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
    } else {
        null
    }

    ExpressivePillSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(compactSize)
            .widthIn(min = compactSize),
        restCorner = compactSize / 2,
        pressedCorner = 10.dp,
        containerColor = containerColor,
        pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = contentColor,
        border = border,
        contentDescription = contentDescription,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (showLabel) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            AnimatedVisibility(
                visible = showLabel,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
