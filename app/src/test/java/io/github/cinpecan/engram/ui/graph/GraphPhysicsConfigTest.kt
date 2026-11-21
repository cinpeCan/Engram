package io.github.cinpecan.engram.ui.graph

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphPhysicsConfigTest {

    @Test
    fun computeRecenterDeltaYPx_shiftsCentroidByHalfHeightDeltaAndSkipsInitialLayout() {
        assertEquals(0f, computeRecenterDeltaYPx(previousHeightPx = 0f, newHeightPx = 800f), 0.001f)
        assertEquals(0f, computeRecenterDeltaYPx(previousHeightPx = 800f, newHeightPx = 800.5f), 0.001f)
        assertEquals(200f, computeRecenterDeltaYPx(previousHeightPx = 600f, newHeightPx = 1000f), 0.001f)
        assertEquals(-200f, computeRecenterDeltaYPx(previousHeightPx = 1000f, newHeightPx = 600f), 0.001f)
    }

    @Test
    fun buildGraphRootPhysics_defaultsUnfoldOffAndEnforcesStrongFourWallContainment() {
        val defaultPhysics = buildGraphRootPhysics(widthDp = 350f, heightDp = 520f)
        assertFalse(defaultPhysics.unfoldEnabled)
        assertTrue(defaultPhysics.minScale <= 0.40f)
        assertTrue(defaultPhysics.wallRepulsionDp >= 98f)
        assertTrue(defaultPhysics.wallElasticityStart >= 5.0f)
        assertTrue(defaultPhysics.wallElasticityEnd >= 5.0f)
        assertTrue(defaultPhysics.wallElasticityTop >= 5.0f)
        assertTrue(defaultPhysics.wallElasticityBottom >= 5.0f)

        val unfoldedPhysics = buildGraphRootPhysics(widthDp = 350f, heightDp = 520f, unfoldEnabled = true)
        assertTrue(unfoldedPhysics.unfoldEnabled)
    }

    @Test
    fun graphRubberPhysics_adaptsLengthToLabelWithinReadableBounds() {
        assertTrue(GraphRubberPhysics.isAutoLength)
        assertTrue(GraphRubberPhysics.elasticity > 5.6f)
        val shortLen = GraphRubberPhysics.resolveNaturalLengthDp(contentWidthDp = 20f)
        val longLen = GraphRubberPhysics.resolveNaturalLengthDp(contentWidthDp = 80f)
        val hugeLen = GraphRubberPhysics.resolveNaturalLengthDp(contentWidthDp = 400f)
        assertEquals(132f, shortLen, 0.001f)
        assertTrue(longLen > shortLen && longLen <= 188f)
        assertEquals(188f, hugeLen, 0.001f)
        assertTrue(GraphPlanetPhysics.elasticity > 4.2f)

        val compactChainRubber = buildGraphRubberPhysics(diameterHops = 8, unfoldEnabled = true)
        assertTrue(compactChainRubber.resolveNaturalLengthDp(contentWidthDp = 20f) < shortLen)
    }

    @Test
    fun computeAutoGraphScale_decreasesAsNodeCountAndDiameterGrow() {
        val s2 = computeAutoGraphScale(widthDp = 350f, heightDp = 520f, nodeCount = 2)
        val s5 = computeAutoGraphScale(widthDp = 350f, heightDp = 520f, nodeCount = 5)
        val s7 = computeAutoGraphScale(widthDp = 350f, heightDp = 520f, nodeCount = 7)
        val s10 = computeAutoGraphScale(widthDp = 350f, heightDp = 520f, nodeCount = 10)
        assertTrue(s2 in 0.50f..1.02f)
        assertTrue(s10 in 0.50f..1.02f)
        assertTrue(s2 > s5 && s5 > s7 && s7 > s10)

        val star10 = computeAutoGraphScale(
            widthDp = 360f,
            heightDp = 260f,
            nodeCount = 10,
            diameterHops = 2,
            unfoldEnabled = false,
        )
        val chain10Folded = computeAutoGraphScale(
            widthDp = 360f,
            heightDp = 260f,
            nodeCount = 10,
            diameterHops = 9,
            unfoldEnabled = false,
        )
        val chain10Unfolded = computeAutoGraphScale(
            widthDp = 360f,
            heightDp = 260f,
            nodeCount = 10,
            diameterHops = 9,
            unfoldEnabled = true,
        )
        assertTrue(chain10Folded <= star10)
        assertTrue(chain10Unfolded < star10)
        assertTrue(chain10Unfolded in 0.38f..1.02f)
        assertTrue(chain10Unfolded * 9 * 98f <= 360f * 1.05f)
    }

    @Test
    fun analyzeGraphTopology_reconstructsScrambledLinearChainInEndpointOrder() {
        val canonical = (0..9).map { "N$it" }
        val scrambledNodes = listOf("N4", "N0", "N9", "N2", "N7", "N1", "N6", "N3", "N8", "N5")
        val scrambledEdges = listOf(
            "N5" to "N4",
            "N1" to "N2",
            "N8" to "N7",
            "N3" to "N2",
            "N0" to "N1",
            "N6" to "N5",
            "N7" to "N6",
            "N4" to "N3",
            "N9" to "N8",
        )

        val metrics = analyzeGraphTopology(scrambledNodes, scrambledEdges)
        assertEquals(10, metrics.nodeCount)
        assertEquals(9, metrics.edgeCount)
        assertEquals(9, metrics.diameterHops)
        assertEquals(2, metrics.maxDegree)
        assertTrue(metrics.isLinearChain)
        assertTrue(
            metrics.orderedNodeIds == canonical || metrics.orderedNodeIds == canonical.reversed(),
        )
    }

    @Test
    fun computeSeedOffsetsPx_keepsLongLinearChainInsideViewportWithoutChordJumps() {
        val chain = (0 until 12).map { "N$it" }
        val viewports = listOf(360f to 240f, 360f to 640f)

        viewports.forEach { (w, h) ->
            val offsets = computeSeedOffsetsPx(
                orderedNodeIds = chain,
                diameterHops = chain.size - 1,
                widthPx = w,
                heightPx = h,
            )
            assertEquals(chain.size, offsets.size)

            offsets.values.forEach { offset ->
                assertTrue(abs(offset.x) <= w * 0.30f + 1f)
                assertTrue(abs(offset.y) <= h * 0.30f + 1f)
            }

            chain.zipWithNext { a, b ->
                val p1 = offsets.getValue(a)
                val p2 = offsets.getValue(b)
                val dist = hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble())
                assertTrue(dist <= maxOf(w, h) * 0.45)
            }
        }
    }
}
