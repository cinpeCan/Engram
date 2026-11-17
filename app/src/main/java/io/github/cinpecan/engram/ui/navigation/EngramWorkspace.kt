package io.github.cinpecan.engram.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import java.util.Locale
import org.koin.compose.koinInject
import org.koin.compose.navigation3.koinEntryProvider
import org.koin.core.annotation.KoinExperimentalAPI

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3AdaptiveApi::class,
    KoinExperimentalAPI::class,
)
@Composable
fun EngramWorkspace(
    modifier: Modifier = Modifier,
    edgeEngine: EdgeAiEngine = koinInject(),
) {
    val navigator = rememberEngramNavigator()
    val activeTabKey = navigator.activeTabKey
    val engineState by edgeEngine.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(navigator) {
        navigator.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val topBarState = rememberTopAppBarState()
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        state = topBarState,
        canScroll = { activeTabKey !is EngramNavKey.Graph },
    )

    LaunchedEffect(activeTabKey) {
        topBarState.heightOffset = 0f
    }

    val bottomSheetStrategy = remember { BottomSheetSceneStrategy<NavKey>() }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()
    val singlePaneStrategy = remember { SinglePaneSceneStrategy<NavKey>() }
    val sceneStrategies = remember(bottomSheetStrategy, listDetailStrategy, singlePaneStrategy) {
        listOf(
            bottomSheetStrategy,
            listDetailStrategy,
            singlePaneStrategy,
        )
    }

    CompositionLocalProvider(LocalEngramNavigator provides navigator) {
        Scaffold(
            modifier = modifier.nestedScroll(topBarScrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("Engram · 印迹") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    scrollBehavior = topBarScrollBehavior,
                    actions = {
                        EngineStatusPill(
                            engineState = engineState,
                            onClick = { navigator.openOverlay(EngramNavKey.EngineSheet) },
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    },
                )
            },
            bottomBar = {
                EngramSlimBottomBar(
                    activeTabKey = activeTabKey,
                    onSelectCapture = { navigator.navigateTopTab(EngramNavKey.Capture) },
                    onSelectGraph = { navigator.navigateTopTab(EngramNavKey.Graph) },
                    onSelectAsk = { navigator.navigateTopTab(EngramNavKey.Ask) },
                )
            },
        ) { innerPadding ->
            NavDisplay(
                backStack = navigator.backStack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
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

@Composable
private fun EngramSlimBottomBar(
    activeTabKey: EngramNavKey,
    onSelectCapture: () -> Unit,
    onSelectGraph: () -> Unit,
    onSelectAsk: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                    .height(50.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SlimNavItem(
                    selected = activeTabKey is EngramNavKey.Capture,
                    selectedIcon = Icons.Rounded.EditNote,
                    unselectedIcon = Icons.Outlined.EditNote,
                    contentDescription = "印迹",
                    onClick = onSelectCapture,
                    modifier = Modifier.weight(1f),
                )
                SlimNavItem(
                    selected = activeTabKey is EngramNavKey.Graph,
                    selectedIcon = Icons.Rounded.Hub,
                    unselectedIcon = Icons.Outlined.Hub,
                    contentDescription = "脉络",
                    onClick = onSelectGraph,
                    modifier = Modifier.weight(1f),
                )
                SlimNavItem(
                    selected = activeTabKey is EngramNavKey.Ask,
                    selectedIcon = Icons.Rounded.AutoAwesome,
                    unselectedIcon = Icons.Outlined.AutoAwesome,
                    contentDescription = "寻迹",
                    onClick = onSelectAsk,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SlimNavItem(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "navPillColor",
    )
    val pillWidth by animateDpAsState(
        targetValue = if (selected) 46.dp else 26.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "navPillWidth",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "navIconTint",
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(pillWidth)
                .height(30.dp)
                .clip(CircleShape)
                .background(pillColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun EngineStatusPill(
    engineState: EngineState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dotColor = when (engineState) {
        EngineState.Unloaded -> Color(0xFF9E9E9E)
        is EngineState.Loading, is EngineState.Releasing -> Color(0xFFFFA000)
        is EngineState.Ready -> Color(0xFF2E7D32)
        is EngineState.Generating -> Color(0xFF1E88E5)
        is EngineState.Error -> MaterialTheme.colorScheme.error
    }
    val text = when (engineState) {
        EngineState.Unloaded -> "引擎未加载"
        is EngineState.Loading -> "加载中…"
        is EngineState.Ready -> String.format(
            Locale.US,
            "MNN 就绪 · %.0fMB",
            engineState.nativeHeapBytes / (1024.0 * 1024.0),
        )
        is EngineState.Generating -> "端侧推理中…"
        is EngineState.Releasing -> "释放内存…"
        is EngineState.Error -> "引擎异常"
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
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
