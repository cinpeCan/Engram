package io.github.cinpecan.engram.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import io.github.cinpecan.engram.ui.components.ExpressivePillSurface
import io.github.cinpecan.engram.ui.theme.transparentize
import io.github.cinpecan.engram.ui.viewmodel.TopBarDotTone
import io.github.cinpecan.engram.ui.viewmodel.TopBarStatusUi
import io.github.cinpecan.engram.ui.viewmodel.WorkspaceEffect
import io.github.cinpecan.engram.ui.viewmodel.WorkspaceIntent
import io.github.cinpecan.engram.ui.viewmodel.WorkspaceNoticeUi
import io.github.cinpecan.engram.ui.viewmodel.WorkspaceViewModel
import org.koin.compose.navigation3.koinEntryProvider
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI

val LocalWorkspaceContentPadding = compositionLocalOf { PaddingValues(0.dp) }

@OptIn(
    ExperimentalMaterial3Api::class,
    KoinExperimentalAPI::class,
)
@Composable
fun EngramWorkspace(
    modifier: Modifier = Modifier,
    workspaceViewModel: WorkspaceViewModel = koinViewModel(),
) {
    val navigator = rememberEngramNavigator()
    val topBarStatus by workspaceViewModel.topBarStatus.collectAsStateWithLifecycle()
    val activeNotice by workspaceViewModel.activeNotice.collectAsStateWithLifecycle()

    LaunchedEffect(navigator, workspaceViewModel) {
        navigator.noticeRequests.collect { request ->
            if (request == null) {
                workspaceViewModel.onIntent(WorkspaceIntent.DismissNotice)
            } else {
                workspaceViewModel.onIntent(WorkspaceIntent.ShowNotice(request))
            }
        }
    }

    LaunchedEffect(navigator, workspaceViewModel) {
        workspaceViewModel.effects.collect { effect ->
            when (effect) {
                WorkspaceEffect.OpenEngineSheet -> navigator.openOverlay(EngramNavKey.EngineSheet)
            }
        }
    }

    val isOverlayOpen by remember(navigator) {
        derivedStateOf { navigator.backStack.lastOrNull() is EngramNavKey.Overlay }
    }
    LaunchedEffect(isOverlayOpen, workspaceViewModel) {
        workspaceViewModel.onIntent(WorkspaceIntent.OnOverlayPresenceChanged(isOverlayOpen))
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
                WorkspaceNoticeHost(
                    notice = activeNotice,
                    bottomClearanceDp = navigator.bottomConsoleClearanceDp,
                    onAction = { workspaceViewModel.onIntent(WorkspaceIntent.PerformNoticeAction) },
                    onDismiss = { workspaceViewModel.onIntent(WorkspaceIntent.DismissNotice) },
                )
            },
            topBar = {
                val topBarBaseColor = MaterialTheme.colorScheme.background
                TopAppBar(
                    title = { Text("Engram") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = topBarBaseColor.transparentize(),
                        scrolledContainerColor = topBarBaseColor.copy(alpha = 0.88f),
                    ),
                    scrollBehavior = topBarScrollBehavior,
                    actions = {
                        EngineStatusPill(
                            status = topBarStatus,
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
private fun WorkspaceNoticeHost(
    notice: WorkspaceNoticeUi?,
    bottomClearanceDp: Dp,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedContent(
        targetState = notice,
        transitionSpec = {
            fadeIn(spring(stiffness = 700f)) togetherWith fadeOut(spring(stiffness = 900f))
        },
        contentKey = { it?.id },
        label = "workspaceNotice",
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(
                start = 12.dp,
                end = 12.dp,
                bottom = bottomClearanceDp + 8.dp,
            ),
    ) { currentNotice ->
        if (currentNotice != null) {
            key(currentNotice.id) {
                val dismissState = rememberSwipeToDismissBoxState()
                LaunchedEffect(dismissState.currentValue) {
                    if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                        onDismiss()
                    }
                }
                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {},
                ) {
                    Snackbar(
                        action = currentNotice.actionLabel?.let { label ->
                            {
                                TextButton(onClick = onAction) {
                                    Text(
                                        text = label,
                                        color = SnackbarDefaults.actionColor,
                                    )
                                }
                            }
                        },
                    ) {
                        Text(text = currentNotice.message)
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineStatusPill(
    status: TopBarStatusUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetDotColor = when (status.tone) {
        TopBarDotTone.LOADING -> Color(0xFFFFA000)
        TopBarDotTone.GENERATING -> Color(0xFF1E88E5)
        TopBarDotTone.ERROR -> MaterialTheme.colorScheme.error
        TopBarDotTone.READY -> Color(0xFF2E7D32)
        TopBarDotTone.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }

    val dotColor by animateColorAsState(
        targetValue = targetDotColor,
        animationSpec = spring(dampingRatio = 1.0f, stiffness = 1600f),
        label = "engineDotColor",
    )

    ExpressivePillSurface(
        onClick = onClick,
        modifier = modifier,
        restCorner = 20.dp,
        pressedCorner = 10.dp,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        contentDescription = "Inference & Sync: ${status.label}",
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
                text = status.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
