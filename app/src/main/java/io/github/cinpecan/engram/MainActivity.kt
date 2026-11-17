package io.github.cinpecan.engram

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.cinpecan.engram.ui.navigation.EngramWorkspace
import io.github.cinpecan.engram.ui.theme.EngramTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as EngramApplication).container
        setContent {
            EngramTheme {
                EngramWorkspace(container = appContainer)
            }
        }
    }
}
