package io.github.cinpecan.engram.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable

@Immutable
data class EngramSnackbarEvent(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

@Serializable
sealed interface EngramNavKey : NavKey {
    @Serializable
    sealed interface Overlay : EngramNavKey

    @Serializable
    data object Capture : EngramNavKey

    @Serializable
    data class GraphSheet(val memoryId: String) : Overlay

    @Serializable
    data class RawMemorySheet(val memoryId: String) : Overlay

    @Serializable
    data object EngineSheet : Overlay
}

@Stable
class EngramNavigator(
    val backStack: MutableList<NavKey>,
) {
    private val snackbarChannel = Channel<EngramSnackbarEvent>(Channel.BUFFERED)
    val snackbarEvents: Flow<EngramSnackbarEvent> = snackbarChannel.receiveAsFlow()

    var bottomConsoleClearanceDp by mutableStateOf(0.dp)

    fun showMessage(message: String) {
        snackbarChannel.trySend(EngramSnackbarEvent(message = message))
    }

    fun showActionMessage(
        message: String,
        actionLabel: String,
        onAction: () -> Unit,
    ) {
        snackbarChannel.trySend(
            EngramSnackbarEvent(
                message = message,
                actionLabel = actionLabel,
                onAction = onAction,
            ),
        )
    }

    fun openGraphForMemory(memoryId: String) {
        openOverlay(EngramNavKey.GraphSheet(memoryId = memoryId))
    }

    fun openRawMemory(memoryId: String) {
        openOverlay(EngramNavKey.RawMemorySheet(memoryId = memoryId))
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
}

val LocalEngramNavigator = staticCompositionLocalOf<EngramNavigator> {
    error("EngramNavigator not provided")
}

@Composable
fun rememberEngramNavigator(): EngramNavigator {
    val backStack = rememberNavBackStack(EngramNavKey.Capture)
    return remember(backStack) {
        EngramNavigator(backStack = backStack)
    }
}
