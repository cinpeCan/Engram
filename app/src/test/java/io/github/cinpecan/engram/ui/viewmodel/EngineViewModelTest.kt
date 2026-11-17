package io.github.cinpecan.engram.ui.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.edgemnn.InferenceTelemetry
import io.github.cinpecan.engram.edgemnn.StreamChunk
import io.github.cinpecan.engram.edgemnn.UnloadReason
import io.github.cinpecan.engram.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class EngineViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class StubEdgeEngine : EdgeAiEngine {
        private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
        override val state: StateFlow<EngineState> = _state
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)

        val unloadReasons = mutableListOf<UnloadReason>()
        private var heapBytes = 0L

        override suspend fun loadModel(modelId: String, modelDirPath: String): Result<Unit> {
            heapBytes = 128L * 1024 * 1024
            _state.value = EngineState.Ready(modelId = modelId, backendName = "MNN", nativeHeapBytes = heapBytes)
            return Result.success(Unit)
        }

        override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {}
        override suspend fun embedText(text: String, dims: Int) = FloatArray(dims)

        override suspend fun unload(reason: UnloadReason): Long {
            unloadReasons.add(reason)
            val reclaimed = heapBytes
            heapBytes = 0L
            _state.value = EngineState.Unloaded
            return reclaimed
        }

        override fun cancelActiveGeneration() = Unit
        override fun onTrimMemory(level: Int) = Unit
        override fun currentNativeHeapBytes(): Long = heapBytes
    }

    @Test
    fun syncCatalogLoadModelAndReleaseMemoryEmitOrderedStateAndSingleEffects() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("engine_prefs.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        val downloadManager = ModelDownloadManager(
            primaryModelsDir = tempFolder.newFolder("models"),
            externalModelsDir = null,
            okHttpClient = OkHttpClient(),
        )
        val engine = StubEdgeEngine()
        val vm = EngineViewModel(engine, downloadManager, prefsRepo)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.runtimeStatus.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.catalogItems.collect {}
        }

        vm.onIntent(EngineIntent.UpdateServerUrlInput("http://127.0.0.1:9/"))
        assertEquals("http://127.0.0.1:9/", vm.serverUrlInput.value)

        vm.onIntent(EngineIntent.SyncCatalog)

        val syncEffect = vm.effects.first() as EngineEffect.ShowMessage
        assertTrue(syncEffect.message.contains("已保存网关地址"))
        assertEquals("http://127.0.0.1:9", prefsRepo.preferencesFlow.first().serverBaseUrl)
        assertEquals("http://127.0.0.1:9", vm.serverUrlInput.value)
        assertFalse(vm.isRefreshing.first { !it })

        val targetModel = ModelDownloadManager.builtInCatalog().first { it.id == "qwen2.5-1.5b-instruct-mnn" }
        vm.onIntent(EngineIntent.LoadModel(targetModel))

        val loadEffect = vm.effects.first() as EngineEffect.ShowMessage
        assertTrue(loadEffect.message.contains("端侧 JNI 运行时会话"))
        assertEquals("qwen2.5-1.5b-instruct-mnn", prefsRepo.preferencesFlow.first().selectedModelId)
        val selectedItems = vm.catalogItems.first { list ->
            list.any { it.model.id == "qwen2.5-1.5b-instruct-mnn" && it.isSelected }
        }
        assertTrue(selectedItems.first { it.model.id == "qwen2.5-1.5b-instruct-mnn" }.isSelected)
        assertTrue(vm.runtimeStatus.value.engineState is EngineState.Ready)

        vm.onIntent(EngineIntent.ReleaseMemoryNow)
        val releaseEffect = vm.effects.first() as EngineEffect.ShowMessage
        assertTrue(releaseEffect.message.contains("128.0 MB"))
        assertEquals(listOf(UnloadReason.USER_REQUESTED), engine.unloadReasons)
        assertEquals(EngineState.Unloaded, vm.runtimeStatus.value.engineState)
    }
}
