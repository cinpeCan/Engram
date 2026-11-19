package io.github.cinpecan.engram.ui.graph

import org.junit.Assert.assertEquals
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
    fun buildGraphRootPhysics_enforcesStrongFourWallContainment() {
        val physics = buildGraphRootPhysics(widthDp = 350f, heightDp = 520f)
        assertTrue(physics.wallRepulsionDp >= 98f)
        assertTrue(physics.wallElasticityStart >= 5.0f)
        assertTrue(physics.wallElasticityEnd >= 5.0f)
        assertTrue(physics.wallElasticityTop >= 5.0f)
        assertTrue(physics.wallElasticityBottom >= 5.0f)
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
    }

    @Test
    fun computeAutoGraphScale_decreasesAsNodeCountGrows() {
        val s2 = computeAutoGraphScale(widthDp = 350f, heightDp = 520f, nodeCount = 2)
        val s5 = computeAutoGraphScale(widthDp = 350f, heightDp = 520f, nodeCount = 5)
        val s7 = computeAutoGraphScale(widthDp = 350f, heightDp = 520f, nodeCount = 7)
        val s10 = computeAutoGraphScale(widthDp = 350f, heightDp = 520f, nodeCount = 10)
        assertTrue(s2 in 0.60f..1.02f)
        assertTrue(s10 in 0.60f..1.02f)
        assertTrue(s2 > s5 && s5 > s7 && s7 > s10)
    }
}
