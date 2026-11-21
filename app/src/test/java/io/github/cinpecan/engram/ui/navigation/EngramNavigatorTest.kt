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

        navigator.openRawMemory("m1")
        assertEquals(
            listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.RawMemorySheet("m1")),
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

        navigator.openRawMemory("m2")
        assertEquals(
            listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.RawMemorySheet("m2")),
            navigator.backStack,
        )

        // Opening a cited source from AnswerSheet stacks RawMemorySheet so Back preserves AnswerSheet.
        navigator.openAnswer("Who signed off?")
        assertEquals(
            listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.AnswerSheet("Who signed off?")),
            navigator.backStack,
        )

        navigator.openRawMemory("seed-2")
        assertEquals(
            listOf<NavKey>(
                EngramNavKey.Capture,
                EngramNavKey.AnswerSheet("Who signed off?"),
                EngramNavKey.RawMemorySheet("seed-2"),
            ),
            navigator.backStack,
        )

        navigator.goBack()
        assertEquals(
            listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.AnswerSheet("Who signed off?")),
            navigator.backStack,
        )

        navigator.goBack()
        assertEquals(listOf<NavKey>(EngramNavKey.Capture), navigator.backStack)

        navigator.goBack()
        assertEquals(listOf<NavKey>(EngramNavKey.Capture), navigator.backStack)
    }
}
