package io.github.cinpecan.engram.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import io.github.cinpecan.engram.di.EngramContainer
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.ui.ask.AskScreen
import io.github.cinpecan.engram.ui.capture.CaptureScreen
import io.github.cinpecan.engram.ui.engine.EngineBottomSheet
import io.github.cinpecan.engram.ui.graph.GraphScreen
import io.github.cinpecan.engram.ui.viewmodel.AskViewModel
import io.github.cinpecan.engram.ui.viewmodel.CaptureViewModel
import io.github.cinpecan.engram.ui.viewmodel.EngineViewModel
import io.github.cinpecan.engram.ui.viewmodel.GraphViewModel
import kotlinx.coroutines.launch

sealed interface EngramNavKey {
    data object Capture : EngramNavKey
    data class Graph(val memoryId: String? = null) : EngramNavKey
    data object Ask : EngramNavKey
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngramWorkspace(container: EngramContainer) {
    val captureVm: CaptureViewModel = viewModel(
        factory = CaptureViewModel.Factory(container.memoryRepository),
    )
    val graphVm: GraphViewModel = viewModel(
        factory = GraphViewModel.Factory(container.memoryRepository),
    )
    val askVm: AskViewModel = viewModel(
        factory = AskViewModel.Factory(container.inferenceRouter, container.preferencesRepository),
    )
    val engineVm: EngineViewModel = viewModel(
        factory = EngineViewModel.Factory(
            container.edgeEngine,
            container.modelDownloadManager,
            container.preferencesRepository,
        ),
    )

    val backStack = remember { mutableStateListOf<EngramNavKey>(EngramNavKey.Capture) }
    val currentKey = backStack.lastOrNull() ?: EngramNavKey.Capture

    val engineSheetState by engineVm.uiState.collectAsStateWithLifecycle()
    val captureState by captureVm.uiState.collectAsStateWithLifecycle()

    var showEngineSheet by remember { mutableStateOf(false) }
    var activeGraphMemoryId by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()

    fun navigateTopTab(target: EngramNavKey) {
        if (currentKey == target) return
        backStack.clear()
        backStack.add(EngramNavKey.Capture)
        if (target != EngramNavKey.Capture) {
            backStack.add(target)
        }
    }

    fun openGraphForMemory(memoryId: String) {
        activeGraphMemoryId = memoryId
        val target = EngramNavKey.Graph(memoryId)
        if (currentKey is EngramNavKey.Graph) {
            backStack[backStack.lastIndex] = target
        } else {
            backStack.add(target)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Engram · 印迹") },
                actions = {
                    EngineStatusPill(
                        engineState = engineSheetState.engineState,
                        nativeHeapMb = engineSheetState.nativeHeapMb,
                        onClick = { showEngineSheet = true },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentKey is EngramNavKey.Capture,
                    onClick = { navigateTopTab(EngramNavKey.Capture) },
                    icon = { Icon(Icons.Rounded.EditNote, contentDescription = "印迹") },
                    label = { Text("印迹") },
                )
                NavigationBarItem(
                    selected = currentKey is EngramNavKey.Graph,
                    onClick = {
                        val defaultGraphId = activeGraphMemoryId
                            ?: captureState.memories.firstOrNull { it.relations.isNotEmpty() }?.id
                        navigateTopTab(EngramNavKey.Graph(defaultGraphId))
                    },
                    icon = { Icon(Icons.Rounded.Hub, contentDescription = "脉络") },
                    label = { Text("脉络") },
                )
                NavigationBarItem(
                    selected = currentKey is EngramNavKey.Ask,
                    onClick = { navigateTopTab(EngramNavKey.Ask) },
                    icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = "寻迹") },
                    label = { Text("寻迹") },
                )
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val isWideScreen = maxWidth >= 720.dp
            if (isWideScreen && currentKey !is EngramNavKey.Ask) {
                Row(modifier = Modifier.fillMaxSize()) {
                    CaptureScreen(
                        viewModel = captureVm,
                        onOpenGraph = { id -> activeGraphMemoryId = id },
                        onShowMessage = { msg -> uiScope.launch { snackbarHostState.showSnackbar(msg) } },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    VerticalDivider()
                    val selectedId = activeGraphMemoryId
                        ?: captureState.memories.firstOrNull { it.relations.isNotEmpty() }?.id
                    GraphScreen(
                        memoryId = selectedId,
                        viewModel = graphVm,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                NavDisplay(
                    backStack = backStack,
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    entryProvider = { key ->
                        when (key) {
                            is EngramNavKey.Capture -> NavEntry(key) {
                                CaptureScreen(
                                    viewModel = captureVm,
                                    onOpenGraph = ::openGraphForMemory,
                                    onShowMessage = { msg ->
                                        uiScope.launch { snackbarHostState.showSnackbar(msg) }
                                    },
                                )
                            }
                            is EngramNavKey.Graph -> NavEntry(key) {
                                GraphScreen(
                                    memoryId = key.memoryId ?: activeGraphMemoryId,
                                    viewModel = graphVm,
                                )
                            }
                            is EngramNavKey.Ask -> NavEntry(key) {
                                AskScreen(viewModel = askVm)
                            }
                        }
                    },
                )
            }
        }
    }

    if (showEngineSheet) {
        EngineBottomSheet(
            viewModel = engineVm,
            onDismiss = { showEngineSheet = false },
        )
    }
}

@Composable
private fun EngineStatusPill(
    engineState: EngineState,
    nativeHeapMb: Double,
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
        is EngineState.Ready -> String.format("MNN 就绪 · %.0fMB", nativeHeapMb)
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
