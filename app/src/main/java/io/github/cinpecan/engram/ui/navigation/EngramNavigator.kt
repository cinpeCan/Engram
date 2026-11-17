package io.github.cinpecan.engram.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable

@Serializable
sealed interface EngramNavKey : NavKey {
    @Serializable
    sealed interface Overlay : EngramNavKey

    @Serializable
    data object Capture : EngramNavKey

    @Serializable
    data object Graph : EngramNavKey

    @Serializable
    data object Ask : EngramNavKey

    @Serializable
    data class RawMemory(val memoryId: String) : Overlay

    @Serializable
    data object EngineSheet : Overlay
}

@Stable
class EngramNavigator(
    val backStack: MutableList<NavKey>,
    initialGraphMemoryId: String? = null,
) {

    val activeTabKey: EngramNavKey
        get() = backStack.lastOrNull { it is EngramNavKey && it !is EngramNavKey.Overlay } as? EngramNavKey
            ?: EngramNavKey.Capture

    private val _selectedGraphMemoryId = MutableStateFlow(initialGraphMemoryId)
    val selectedGraphMemoryId: StateFlow<String?> = _selectedGraphMemoryId.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    fun showMessage(message: String) {
        messageChannel.trySend(message)
    }

    fun selectGraphMemory(memoryId: String?) {
        _selectedGraphMemoryId.value = memoryId
    }

    fun navigateTopTab(target: EngramNavKey) {
        if (activeTabKey::class == target::class && backStack.none { it is EngramNavKey.Overlay }) {
            return
        }
        while (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
        if (backStack.firstOrNull() != EngramNavKey.Capture) {
            backStack.clear()
            backStack.add(EngramNavKey.Capture)
        }
        if (target != EngramNavKey.Capture) {
            backStack.add(target)
        }
    }

    fun openGraphForMemory(memoryId: String) {
        popTrailingOverlays()
        _selectedGraphMemoryId.value = memoryId
        if (backStack.lastOrNull() != EngramNavKey.Graph) {
            backStack.add(EngramNavKey.Graph)
        }
    }

    fun openOverlay(overlay: EngramNavKey.Overlay) {
        if (backStack.lastOrNull() is EngramNavKey.Overlay) {
            backStack[backStack.lastIndex] = overlay
        } else {
            backStack.add(overlay)
        }
    }

    fun goBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    private fun popTrailingOverlays() {
        while (backStack.lastOrNull() is EngramNavKey.Overlay) {
            backStack.removeAt(backStack.lastIndex)
        }
    }
}

val LocalEngramNavigator = staticCompositionLocalOf<EngramNavigator> {
    error("EngramNavigator not provided")
}

@Composable
fun rememberEngramNavigator(): EngramNavigator {
    val backStack = rememberNavBackStack(EngramNavKey.Capture)
    var savedGraphMemoryId by rememberSaveable { mutableStateOf<String?>(null) }
    val navigator = remember(backStack) {
        EngramNavigator(
            backStack = backStack,
            initialGraphMemoryId = savedGraphMemoryId,
        )
    }
    LaunchedEffect(navigator) {
        navigator.selectedGraphMemoryId.collect { id ->
            savedGraphMemoryId = id
        }
    }
    return navigator
}
