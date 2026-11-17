package io.github.cinpecan.engram.ui.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

class EngramNavigatorTest {

    @Test
    fun singleScreenWorkspaceOpensAndReplacesOverlaysWhileKeepingCaptureRoot() {
        val navigator = EngramNavigator(
            backStack = mutableListOf<NavKey>(EngramNavKey.Capture),
        )

        navigator.openGraphForMemory("m1")
        assertEquals(
            listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.GraphSheet("m1")),
            navigator.backStack,
        )

        navigator.openOverlay(EngramNavKey.EngineSheet)
        assertEquals(
            listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.EngineSheet),
            navigator.backStack,
        )

        navigator.openGraphForMemory("m2")
        assertEquals(
            listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.GraphSheet("m2")),
            navigator.backStack,
        )

        navigator.goBack()
        assertEquals(listOf<NavKey>(EngramNavKey.Capture), navigator.backStack)

        navigator.goBack()
        assertEquals(listOf<NavKey>(EngramNavKey.Capture), navigator.backStack)
    }
}
