package io.github.cinpecan.engram.ui.theme

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslucentColorInterpolationTest {

    @Test
    fun transparentizedTopBarColorPreservesRgbAndAvoidsLuminanceDipAcrossScrollTransition() {
        val fractions = listOf(0f, 0.15f, 0.35f, 0.5f, 0.75f, 1f)
        val epsilon = 0.002f

        for (bg in listOf(LightBackground, DarkBackground)) {
            val start = bg.transparentize()
            val end = bg.copy(alpha = 0.88f)
            for (fraction in fractions) {
                val mid = lerp(start, end, fraction)
                assertTrue(
                    "Red channel drifted for bg=$bg at fraction=$fraction: mid=$mid",
                    abs(mid.red - bg.red) <= epsilon,
                )
                assertTrue(
                    "Green channel drifted for bg=$bg at fraction=$fraction: mid=$mid",
                    abs(mid.green - bg.green) <= epsilon,
                )
                assertTrue(
                    "Blue channel drifted for bg=$bg at fraction=$fraction: mid=$mid",
                    abs(mid.blue - bg.blue) <= epsilon,
                )

                val composited = mid.compositeOver(bg)
                assertTrue(
                    "Composited RGB drifted for bg=$bg at fraction=$fraction: composited=$composited",
                    abs(composited.red - bg.red) <= epsilon &&
                        abs(composited.green - bg.green) <= epsilon &&
                        abs(composited.blue - bg.blue) <= epsilon,
                )
            }
        }
    }
}
