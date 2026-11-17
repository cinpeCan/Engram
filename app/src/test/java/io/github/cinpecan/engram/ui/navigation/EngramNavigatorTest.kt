package io.github.cinpecan.engram.ui.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

class EngramNavigatorTest {

    @Test
    fun graphSelectionSurvivesTabSwitchesAndOverlaysReplaceInPlace() {
        val navigator = EngramNavigator(
            backStack = mutableListOf<NavKey>(EngramNavKey.Capture),
        )

        navigator.openGraphForMemory("m1")
        assertEquals(listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.Graph), navigator.backStack)
        assertEquals("m1", navigator.selectedGraphMemoryId.value)
        assertEquals(EngramNavKey.Graph, navigator.activeTabKey)

        navigator.selectGraphMemory("m2")
        navigator.navigateTopTab(EngramNavKey.Ask)
        assertEquals(listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.Ask), navigator.backStack)
        assertEquals(EngramNavKey.Ask, navigator.activeTabKey)
        assertEquals("m2", navigator.selectedGraphMemoryId.value)

        navigator.navigateTopTab(EngramNavKey.Graph)
        assertEquals(listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.Graph), navigator.backStack)
        assertEquals("m2", navigator.selectedGraphMemoryId.value)

        navigator.openOverlay(EngramNavKey.EngineSheet)
        assertEquals(
            listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.Graph, EngramNavKey.EngineSheet),
            navigator.backStack,
        )
        assertEquals(EngramNavKey.Graph, navigator.activeTabKey)

        navigator.openOverlay(EngramNavKey.RawMemory("m2"))
        assertEquals(
            listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.Graph, EngramNavKey.RawMemory("m2")),
            navigator.backStack,
        )

        navigator.openGraphForMemory("m3")
        assertEquals(
            listOf<NavKey>(EngramNavKey.Capture, EngramNavKey.Graph),
            navigator.backStack,
        )
        assertEquals("m3", navigator.selectedGraphMemoryId.value)

        navigator.goBack()
        assertEquals(listOf<NavKey>(EngramNavKey.Capture), navigator.backStack)
        navigator.goBack()
        assertEquals(listOf<NavKey>(EngramNavKey.Capture), navigator.backStack)
    }
}
