package io.github.cinpecan.engram

import android.content.res.Configuration
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.cinpecan.engram.ui.navigation.EngramWorkspace
import io.github.cinpecan.engram.ui.theme.EngramTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        applyTransparentSystemBars(darkTheme = isDarkTheme)
        setContent {
            EngramTheme {
                EngramWorkspace()
            }
        }
    }
}

fun ComponentActivity.applyTransparentSystemBars(darkTheme: Boolean) {
    val barStyle = if (darkTheme) {
        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        )
    }
    enableEdgeToEdge(
        statusBarStyle = barStyle,
        navigationBarStyle = barStyle,
    )
    window.disableContrastEnforcement()
}

@Suppress("DEPRECATION")
internal fun Window.disableContrastEnforcement() {
    isStatusBarContrastEnforced = false
    isNavigationBarContrastEnforced = false
}
