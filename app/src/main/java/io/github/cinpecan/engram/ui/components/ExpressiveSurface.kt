package io.github.cinpecan.engram.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.cinpecan.engram.ui.theme.transparentize

/**
 * Material 3 Expressive tactile card surface using spring shape morphing, gentle spatial scale,
 * and clean state-layer tonal shift instead of legacy harsh ink ripples.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExpressiveCardSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    restCorner: Dp = 20.dp,
    pressedCorner: Dp = 15.dp,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    pressedContainerColor: Color = if (containerColor.alpha == 0f) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
    } else {
        lerp(containerColor, MaterialTheme.colorScheme.surfaceVariant, 0.36f)
    },
    shadowElevation: Dp = 0.5.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val corner by animateDpAsState(
        targetValue = if (pressed) pressedCorner else restCorner,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 700f),
        label = "expressiveCardCorner",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.986f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 700f),
        label = "expressiveCardScale",
    )
    val restColor = if (containerColor.alpha == 0f && pressedContainerColor.alpha > 0f) {
        pressedContainerColor.transparentize()
    } else {
        containerColor
    }
    val bgColor by animateColorAsState(
        targetValue = if (pressed) pressedContainerColor else restColor,
        animationSpec = spring(dampingRatio = 1.0f, stiffness = 3000f),
        label = "expressiveCardColor",
    )

    val shape = RoundedCornerShape(corner)
    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = shape,
        color = bgColor,
        shadowElevation = shadowElevation,
    ) {
        Box(content = content)
    }
}

/**
 * Material 3 Expressive pill/icon button surface morphing smoothly from pill/circle toward a
 * squircle under finger press using spring physics and tonal shift rather than legacy ripples.
 */
@Composable
fun ExpressivePillSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    restCorner: Dp = 24.dp,
    pressedCorner: Dp = 10.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    pressedContainerColor: Color = if (containerColor.alpha == 0f) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
    } else {
        lerp(containerColor, MaterialTheme.colorScheme.primaryContainer, 0.42f)
    },
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    role: Role = Role.Button,
    contentDescription: String? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val effectivePressed = enabled && pressed

    val corner by animateDpAsState(
        targetValue = if (effectivePressed) pressedCorner else restCorner,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 800f),
        label = "expressivePillCorner",
    )
    val scale by animateFloatAsState(
        targetValue = if (effectivePressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 800f),
        label = "expressivePillScale",
    )
    val restColor = if (containerColor.alpha == 0f && pressedContainerColor.alpha > 0f) {
        pressedContainerColor.transparentize()
    } else {
        containerColor
    }
    val bgColor by animateColorAsState(
        targetValue = if (effectivePressed) pressedContainerColor else restColor,
        animationSpec = spring(dampingRatio = 1.0f, stiffness = 3800f),
        label = "expressivePillColor",
    )

    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .then(semanticsModifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = role,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(corner),
        color = bgColor,
        contentColor = contentColor,
        border = border,
        shadowElevation = shadowElevation,
    ) {
        Box(
            contentAlignment = contentAlignment,
            content = content,
        )
    }
}
