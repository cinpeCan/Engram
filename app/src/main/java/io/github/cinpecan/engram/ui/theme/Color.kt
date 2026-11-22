package io.github.cinpecan.engram.ui.theme

import androidx.compose.ui.graphics.Color

val IndigoPrimary = Color(0xFF3949AB)
val IndigoLight = Color(0xFF8C9EFF)
val TealSecondary = Color(0xFF00796B)
val TealLight = Color(0xFF80CBC4)
val AmberTertiary = Color(0xFFB26A00)
val AmberLight = Color(0xFFFFCA28)

val LightBackground = Color(0xFFF6F7FB)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE9ECF6)

val DarkBackground = Color(0xFF0F1117)
val DarkSurface = Color(0xFF171A23)
val DarkSurfaceVariant = Color(0xFF232836)

/**
 * Returns a zero-alpha copy preserving this color's RGB channels so Oklab/sRGB
 * interpolations (e.g. TopAppBar scroll transitions and gradients) fade alpha cleanly
 * without dipping through Color.Transparent's black (0, 0, 0) chromaticity.
 */
fun Color.transparentize(): Color = copy(alpha = 0f)
