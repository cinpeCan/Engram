package io.github.cinpecan.engram.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import io.github.cinpecan.engram.data.preferences.EnginePreferences
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.ui.components.ExpressivePillSurface
import org.koin.compose.koinInject
import org.koin.compose.navigation3.koinEntryProvider
import org.koin.core.annotation.KoinExperimentalAPI

val LocalWorkspaceContentPadding = compositionLocalOf { PaddingValues(0.dp) }

@OptIn(
    ExperimentalMaterial3Api::class,
    KoinExperimentalAPI::class,
)
@Composable
fun EngramWorkspace(
    modifier: Modifier = Modifier,
    edgeEngine: EdgeAiEngine = koinInject(),
    preferencesRepository: EnginePreferencesRepository = koinInject(),
) {
    val navigator = rememberEngramNavigator()
    val engineState by edgeEngine.state.collectAsStateWithLifecycle()
    val prefs by preferencesRepository.preferencesFlow.collectAsStateWithLifecycle(
        initialValue = EnginePreferences(),
    )

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(navigator) {
        navigator.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val topBarState = rememberTopAppBarState()
    val topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(state = topBarState)

    val bottomSheetStrategy = remember { BottomSheetSceneStrategy<NavKey>() }
    val singlePaneStrategy = remember { SinglePaneSceneStrategy<NavKey>() }
    val sceneStrategies = remember(bottomSheetStrategy, singlePaneStrategy) {
        listOf(
            bottomSheetStrategy,
            singlePaneStrategy,
        )
    }

    CompositionLocalProvider(LocalEngramNavigator provides navigator) {
        Scaffold(
            modifier = modifier.nestedScroll(topBarScrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.systemBars
                .union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Horizontal),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.ime.union(WindowInsets.navigationBars),
                    ),
                )
            },
            topBar = {
                TopAppBar(
                    title = { Text("Engram · 印迹") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
                    ),
                    scrollBehavior = topBarScrollBehavior,
                    actions = {
                        EngineStatusPill(
                            engineState = engineState,
                            inferenceMode = prefs.inferenceMode,
                            onClick = { navigator.openOverlay(EngramNavKey.EngineSheet) },
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    },
                )
            },
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current
            CompositionLocalProvider(LocalWorkspaceContentPadding provides innerPadding) {
                NavDisplay(
                    backStack = navigator.backStack,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = innerPadding.calculateStartPadding(layoutDirection),
                            end = innerPadding.calculateEndPadding(layoutDirection),
                        ),
                    onBack = navigator::goBack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                        rememberViewModelStoreNavEntryDecorator<NavKey>(),
                    ),
                    sceneStrategies = sceneStrategies,
                    entryProvider = koinEntryProvider<NavKey>(),
                )
            }
        }
    }
}

@Composable
private fun EngineStatusPill(
    engineState: EngineState,
    inferenceMode: InferenceMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetDotColor = when {
        engineState is EngineState.Loading || engineState is EngineState.Releasing -> Color(0xFFFFA000)
        engineState is EngineState.Generating -> Color(0xFF1E88E5)
        engineState is EngineState.Error -> MaterialTheme.colorScheme.error
        inferenceMode == InferenceMode.CLOUD_COLLAB -> MaterialTheme.colorScheme.primary
        engineState is EngineState.Ready -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }

    val dotColor by animateColorAsState(
        targetValue = targetDotColor,
        animationSpec = spring(dampingRatio = 1.0f, stiffness = 1600f),
        label = "engineDotColor",
    )

    val text = when {
        engineState is EngineState.Loading -> "加载模型中…"
        engineState is EngineState.Generating -> "端侧推理中…"
        engineState is EngineState.Releasing -> "释放内存中…"
        inferenceMode == InferenceMode.CLOUD_COLLAB -> {
            if (engineState is EngineState.Ready) "云端 · 端侧待命" else "云端协同"
        }
        engineState is EngineState.Ready -> "Qwen 0.5B · 就绪"
        else -> "端侧离线"
    }

    ExpressivePillSurface(
        onClick = onClick,
        modifier = modifier,
        restCorner = 20.dp,
        pressedCorner = 10.dp,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        contentDescription = "推理与同步：$text",
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
