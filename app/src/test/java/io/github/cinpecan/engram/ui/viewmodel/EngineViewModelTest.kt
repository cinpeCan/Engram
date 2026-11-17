package io.github.cinpecan.engram.ui.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.EngineState
import io.github.cinpecan.engram.edgemnn.InferenceTelemetry
import io.github.cinpecan.engram.edgemnn.StreamChunk
import io.github.cinpecan.engram.edgemnn.UnloadReason
import io.github.cinpecan.engram.util.MainDispatcherRule
import java.io.File
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

    private class StubEdgeEngine(
        override val isBackendAvailable: Boolean = true,
    ) : EdgeAiEngine {
        private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
        override val state: StateFlow<EngineState> = _state
        override val lastTelemetry: StateFlow<InferenceTelemetry?> = MutableStateFlow(null)

        val unloadReasons = mutableListOf<UnloadReason>()
        var loadCalls = 0
        private var rssBytes = 0L

        override suspend fun loadModel(modelId: String, modelDirPath: String): Result<Unit> {
            loadCalls++
            rssBytes = 128L * 1024 * 1024
            _state.value = EngineState.Ready(
                modelId = modelId,
                backendName = "MNN-LLM (ARM64)",
                residentMemoryBytes = rssBytes,
                modelFootprintBytes = rssBytes,
            )
            return Result.success(Unit)
        }

        override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = flow {}

        override suspend fun unload(reason: UnloadReason): Long {
            unloadReasons.add(reason)
            val reclaimed = rssBytes
            rssBytes = 0L
            _state.value = EngineState.Unloaded
            return reclaimed
        }

        override fun cancelActiveGeneration() = Unit
        override fun onTrimMemory(level: Int) = Unit
        override fun currentMemoryBytes(): Long = rssBytes
    }

    @Test
    fun syncCatalogRejectsUnreadyLoadAllowsVerifiedLoadAndReleasesMemory() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("engine_prefs.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        val modelsRoot = tempFolder.newFolder("models")
        val downloadManager = ModelDownloadManager(
            primaryModelsDir = modelsRoot,
            externalModelsDir = null,
            okHttpClient = OkHttpClient(),
        )
        val engine = StubEdgeEngine(isBackendAvailable = true)
        val vm = EngineViewModel(engine, downloadManager, prefsRepo)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.inferenceMode.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.runtimeStatus.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.catalogItems.collect {}
        }

        vm.onIntent(EngineIntent.SelectMode(InferenceMode.CLOUD_COLLAB))
        assertEquals(
            InferenceMode.CLOUD_COLLAB,
            prefsRepo.preferencesFlow.first { it.inferenceMode == InferenceMode.CLOUD_COLLAB }.inferenceMode,
        )
        assertEquals(
            InferenceMode.CLOUD_COLLAB,
            vm.inferenceMode.first { it == InferenceMode.CLOUD_COLLAB },
        )

        vm.onIntent(EngineIntent.UpdateServerUrlInput("http://127.0.0.1:9/"))
        assertEquals("http://127.0.0.1:9/", vm.serverUrlInput.value)

        vm.onIntent(EngineIntent.SyncCatalog)
        val syncEffect = vm.effects.first() as EngineEffect.ShowMessage
        assertTrue(syncEffect.message.contains("已保存网关地址"))
        assertEquals("http://127.0.0.1:9", prefsRepo.preferencesFlow.first().serverBaseUrl)
        assertFalse(vm.isRefreshing.first { !it })

        val targetModel = ModelDownloadManager.builtInCatalog().single()

        // Honest rejection before weights are downloaded
        vm.onIntent(EngineIntent.LoadModel(targetModel))
        val rejectEffect = vm.effects.first() as EngineEffect.ShowMessage
        assertTrue(rejectEffect.message.contains("请先完整下载模型权重"))
        assertEquals(0, engine.loadCalls)
        assertEquals(EngineState.Unloaded, vm.runtimeStatus.value.engineState)

        // Stage verified model bundle with `.sha256` markers
        val modelDir = File(modelsRoot, targetModel.id).apply { mkdirs() }
        val dummySha = "a".repeat(64)
        for (spec in targetModel.files) {
            File(modelDir, spec.name).writeText(if (spec.name == "llm_config.json") "{}" else "weights")
            File(modelDir, "${spec.name}.sha256").writeText(dummySha)
        }
        downloadManager.refreshLocalModelStates()

        vm.onIntent(EngineIntent.LoadModel(targetModel))
        val loadEffect = vm.effects.first() as EngineEffect.ShowMessage
        assertTrue(loadEffect.message.contains("已加载本地量化权重"))
        assertEquals(1, engine.loadCalls)
        assertEquals(targetModel.id, prefsRepo.preferencesFlow.first().selectedModelId)
        assertTrue(vm.runtimeStatus.value.engineState is EngineState.Ready)

        vm.onIntent(EngineIntent.ReleaseMemoryNow)
        val releaseEffect = vm.effects.first() as EngineEffect.ShowMessage
        assertTrue(releaseEffect.message.contains("128.0 MB"))
        assertEquals(listOf(UnloadReason.USER_REQUESTED), engine.unloadReasons)
        assertEquals(EngineState.Unloaded, vm.runtimeStatus.value.engineState)
    }
}
