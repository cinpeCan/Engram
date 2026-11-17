package io.github.cinpecan.engram.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
class CollapsibleConsoleState(
    private val scope: CoroutineScope,
    private val jitterThresholdPx: Float,
) {
    var consoleHeightPx by mutableFloatStateOf(0f)
        private set

    var offsetPx by mutableFloatStateOf(0f)
        private set

    var lockExpanded: Boolean = false
    var onUserScroll: (() -> Unit)? = null

    val collapseFraction: Float
        get() = if (consoleHeightPx <= 0f) 0f else (offsetPx / consoleHeightPx).coerceIn(0f, 1f)

    private var reverseAccumulatorPx = 0f
    private var lastScrollDirection = 0 // -1 collapsing (scroll down list), +1 expanding (scroll up list)
    private var snapJob: Job? = null

    fun updateHeight(newHeightPx: Float) {
        if (newHeightPx <= 0f) {
            consoleHeightPx = 0f
            offsetPx = 0f
            return
        }
        val wasCollapsed = consoleHeightPx > 0f && offsetPx >= consoleHeightPx - 1f
        consoleHeightPx = newHeightPx
        offsetPx = if (wasCollapsed && !lockExpanded) {
            newHeightPx
        } else {
            offsetPx.coerceIn(0f, newHeightPx)
        }
    }

    fun expandSmoothly() {
        snapJob?.cancel()
        if (offsetPx <= 0f) return
        snapJob = scope.launch {
            animate(
                initialValue = offsetPx,
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = 0.86f,
                    stiffness = Spring.StiffnessMedium,
                ),
            ) { value, _ ->
                offsetPx = value
            }
        }
    }

    private fun settle() {
        if (lockExpanded) {
            expandSmoothly()
            return
        }
        val height = consoleHeightPx
        if (height <= 0f || offsetPx <= 0f || offsetPx >= height) return

        val shouldCollapse = if (lastScrollDirection < 0) {
            collapseFraction >= 0.25f
        } else {
            collapseFraction > 0.75f
        }
        val target = if (shouldCollapse) height else 0f
        snapJob?.cancel()
        snapJob = scope.launch {
            animate(
                initialValue = offsetPx,
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = 0.86f,
                    stiffness = Spring.StiffnessMedium,
                ),
            ) { value, _ ->
                offsetPx = value
            }
        }
    }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val deltaY = if (consumed.y != 0f) {
                consumed.y
            } else if (source == NestedScrollSource.UserInput) {
                available.y
            } else {
                0f
            }
            if (deltaY == 0f) return Offset.Zero

            if (source == NestedScrollSource.UserInput && abs(deltaY) > jitterThresholdPx) {
                onUserScroll?.invoke()
            }

            if (lockExpanded || consoleHeightPx <= 0f) return Offset.Zero

            if (deltaY < 0f) {
                reverseAccumulatorPx = 0f
                lastScrollDirection = -1
                snapJob?.cancel()
                offsetPx = (offsetPx - deltaY).coerceIn(0f, consoleHeightPx)
            } else if (offsetPx > 0f) {
                reverseAccumulatorPx += deltaY
                if (reverseAccumulatorPx > jitterThresholdPx) {
                    lastScrollDirection = 1
                    snapJob?.cancel()
                    offsetPx = (offsetPx - deltaY).coerceIn(0f, consoleHeightPx)
                }
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(
            consumed: Velocity,
            available: Velocity,
        ): Velocity {
            reverseAccumulatorPx = 0f
            settle()
            return Velocity.Zero
        }
    }
}

@Composable
fun rememberCollapsibleConsoleState(): CollapsibleConsoleState {
    val scope = rememberCoroutineScope()
    val jitterThresholdPx = with(LocalDensity.current) { 4.dp.toPx() }
    return remember(scope, jitterThresholdPx) {
        CollapsibleConsoleState(scope = scope, jitterThresholdPx = jitterThresholdPx)
    }
}

@Composable
fun CollapsibleBottomConsoleLayout(
    lockExpanded: Boolean,
    modifier: Modifier = Modifier,
    onUserScroll: () -> Unit = {},
    extraBottomListPadding: Dp = 12.dp,
    listContent: @Composable (bottomContentPadding: Dp) -> Unit,
    consoleContent: @Composable () -> Unit,
) {
    val consoleState = rememberCollapsibleConsoleState()
    consoleState.lockExpanded = lockExpanded
    consoleState.onUserScroll = onUserScroll

    LaunchedEffect(lockExpanded) {
        if (lockExpanded) {
            consoleState.expandSmoothly()
        }
    }

    val consoleHeightDp = with(LocalDensity.current) { consoleState.consoleHeightPx.toDp() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .imePadding()
            .nestedScroll(consoleState.nestedScrollConnection),
    ) {
        listContent(consoleHeightDp + extraBottomListPadding)

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { consoleState.updateHeight(it.height.toFloat()) }
                .offset { IntOffset(x = 0, y = consoleState.offsetPx.roundToInt()) }
                .graphicsLayer {
                    alpha = (1f - consoleState.collapseFraction * 0.9f).coerceIn(0.1f, 1f)
                },
        ) {
            consoleContent()
        }
    }
}
