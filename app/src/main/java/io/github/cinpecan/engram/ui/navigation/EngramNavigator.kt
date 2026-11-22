package io.github.cinpecan.engram.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import io.github.cinpecan.engram.ui.viewmodel.WorkspaceNoticeAction
import io.github.cinpecan.engram.ui.viewmodel.WorkspaceNoticeKind
import io.github.cinpecan.engram.ui.viewmodel.WorkspaceNoticeRequest
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

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

    @Serializable
    data class AnswerSheet(val question: String) : Overlay
}

@Stable
class EngramNavigator(
    val backStack: MutableList<NavKey>,
) {
    private val _noticeRequests = MutableSharedFlow<WorkspaceNoticeRequest?>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val noticeRequests: Flow<WorkspaceNoticeRequest?> = _noticeRequests.asSharedFlow()

    var bottomConsoleClearanceDp by mutableStateOf(0.dp)

    fun showMessage(message: String) {
        _noticeRequests.tryEmit(
            WorkspaceNoticeRequest(
                message = message,
                kind = WorkspaceNoticeKind.INFO,
            ),
        )
    }

    fun showEngineSetupNotice(
        message: String,
        actionLabel: String,
    ) {
        _noticeRequests.tryEmit(
            WorkspaceNoticeRequest(
                message = message,
                kind = WorkspaceNoticeKind.ENGINE_SETUP,
                actionLabel = actionLabel,
                action = WorkspaceNoticeAction.OPEN_ENGINE_SHEET,
            ),
        )
    }

    fun dismissNotice() {
        _noticeRequests.tryEmit(null)
    }

    fun openGraphForMemory(memoryId: String) {
        openOverlay(EngramNavKey.GraphSheet(memoryId = memoryId))
    }

    fun openRawMemory(memoryId: String) {
        openOverlay(EngramNavKey.RawMemorySheet(memoryId = memoryId))
    }

    fun openAnswer(question: String) {
        openOverlay(EngramNavKey.AnswerSheet(question = question))
    }

    fun openOverlay(overlay: EngramNavKey.Overlay) {
        val currentTop = backStack.lastOrNull()
        val shouldStackOnAnswer = currentTop is EngramNavKey.AnswerSheet &&
            overlay is EngramNavKey.RawMemorySheet
        if (currentTop is EngramNavKey.Overlay && !shouldStackOnAnswer) {
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
