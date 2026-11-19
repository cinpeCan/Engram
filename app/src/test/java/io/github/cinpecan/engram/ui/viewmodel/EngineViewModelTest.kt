package io.github.cinpecan.engram.ui.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.data.remote.MemorySyncClient
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.data.repository.UnifiedInferenceRouter
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.domain.model.AnalysisSource
import io.github.cinpecan.engram.domain.model.InferenceMode
import io.github.cinpecan.engram.domain.model.MemoryAnalysisPayload
import io.github.cinpecan.engram.domain.model.MemoryItem
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    private class EmptyStore : LocalMemoryStore {
        override fun observeMemories(): Flow<List<MemoryItem>> = flow { emit(emptyList()) }
        override fun observeMemoryById(memoryId: String): Flow<MemoryItem?> = flow { emit(null) }
        override fun observeMemoriesForEntity(entityName: String): Flow<List<MemoryItem>> = flow { emit(emptyList()) }
        override suspend fun insertRawMemory(item: MemoryItem) = Unit
        override suspend fun updateMemoryAnalysis(
            memoryId: String,
            payload: MemoryAnalysisPayload,
            analysisSource: AnalysisSource,
            telemetryBadge: String,
        ) = Unit
        override suspend fun setCalendarSaved(memoryId: String, saved: Boolean) = Unit
        override suspend fun mergeRemoteMemories(remoteItems: List<MemoryItem>): Int = 0
        override suspend fun getAllMemories(): List<MemoryItem> = emptyList()
        override suspend fun countMemories(): Int = 0
    }

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
    fun syncConfigSavesPreferencesLoadRejectsUnreadyAndReleasesMemory() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("engine_prefs.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        val modelsRoot = tempFolder.newFolder("models")
        val okHttp = OkHttpClient()
        val baseModel = ModelDownloadManager.builtInCatalog().single()
        val testCatalogModel = baseModel.copy(
            files = baseModel.files.map { it.copy(sha256 = null) },
        )
        val downloadManager = ModelDownloadManager(
            primaryModelsDir = modelsRoot,
            externalModelsDir = null,
            okHttpClient = okHttp,
            initialCatalog = listOf(testCatalogModel),
        )
        val engine = StubEdgeEngine(isBackendAvailable = true)
        val router = UnifiedInferenceRouter(engine, CloudSseClient(okHttp), MemoryAnalyzer())
        val memoryRepo = MemoryRepository(
            store = EmptyStore(),
            inferenceRouter = router,
            preferencesRepository = prefsRepo,
            syncClient = MemorySyncClient(okHttp),
            backgroundScope = backgroundScope,
        )
        val vm = EngineViewModel(engine, downloadManager, prefsRepo, memoryRepo)

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
        vm.onIntent(EngineIntent.UpdateSyncKeyInput("team-room"))
        assertEquals("http://127.0.0.1:9/", vm.serverUrlInput.value)
        assertEquals("team-room", vm.syncKeyInput.value)

        vm.onIntent(EngineIntent.SyncMemories)
        val syncEffect = vm.effects.first() as EngineEffect.ShowMessage
        assertTrue(syncEffect.message.contains("已保存配置"))
        val savedPrefs = prefsRepo.preferencesFlow.first { it.serverBaseUrl == "http://127.0.0.1:9" }
        assertEquals("http://127.0.0.1:9", savedPrefs.serverBaseUrl)
        assertEquals("team-room", savedPrefs.syncKey)
        assertFalse(vm.isSyncing.first { !it })

        val targetModel = testCatalogModel

        vm.onIntent(EngineIntent.LoadModel(targetModel))
        val rejectEffect = vm.effects.first() as EngineEffect.ShowMessage
        assertTrue(rejectEffect.message.contains("请先下载模型文件"))
        assertEquals(0, engine.loadCalls)
        assertEquals(EngineState.Unloaded, vm.runtimeStatus.value.engineState)

        val modelDir = File(modelsRoot, targetModel.id).apply { mkdirs() }
        val dummySha = "a".repeat(64)
        for (spec in targetModel.files) {
            File(modelDir, spec.name).writeText(if (spec.name == "llm_config.json") "{}" else "weights")
            File(modelDir, "${spec.name}.sha256").writeText(dummySha)
        }
        downloadManager.refreshLocalModelStates()

        vm.onIntent(EngineIntent.LoadModel(targetModel))
        val loadEffect = vm.effects.first() as EngineEffect.ShowMessage
        assertTrue(loadEffect.message.contains("模型已加载就绪"))
        assertEquals(1, engine.loadCalls)
        assertEquals(targetModel.id, prefsRepo.preferencesFlow.first().selectedModelId)
        assertTrue(vm.runtimeStatus.value.engineState is EngineState.Ready)

        vm.onIntent(EngineIntent.ReleaseMemoryNow)
        val releaseEffect = vm.effects.first() as EngineEffect.ShowMessage
        assertTrue(releaseEffect.message.contains("128.0 MB"))
        assertEquals(listOf(UnloadReason.USER_REQUESTED), engine.unloadReasons)
        assertEquals(EngineState.Unloaded, vm.runtimeStatus.value.engineState)
    }

    @Test
    fun defaultsAreEmptyAnd401And429SyncNoticesDoNotLeakHosts() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("engine_auth_prefs.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        val initialPrefs = prefsRepo.preferencesFlow.first()
        assertEquals("", initialPrefs.serverBaseUrl)
        assertEquals("", initialPrefs.syncKey)

        val statusToReturn = intArrayOf(401)
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val code = statusToReturn[0]
                if (code == 0) {
                    throw java.net.UnknownHostException("secret-internal-host.invalid")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("HTTP $code")
                    .body("{\"detail\":\"blocked\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val modelsRoot = tempFolder.newFolder("auth_models")
        val downloadManager = ModelDownloadManager(
            primaryModelsDir = modelsRoot,
            externalModelsDir = null,
            okHttpClient = mockHttp,
            initialCatalog = emptyList(),
        )
        val engine = StubEdgeEngine(isBackendAvailable = false)
        val router = UnifiedInferenceRouter(engine, CloudSseClient(mockHttp), MemoryAnalyzer())
        val memoryRepo = MemoryRepository(
            store = EmptyStore(),
            inferenceRouter = router,
            preferencesRepository = prefsRepo,
            syncClient = MemorySyncClient(mockHttp),
            backgroundScope = backgroundScope,
        )
        val vm = EngineViewModel(engine, downloadManager, prefsRepo, memoryRepo)

        assertEquals("", vm.serverUrlInput.value)
        assertEquals("", vm.syncKeyInput.value)

        // 401 without demo token
        vm.onIntent(EngineIntent.SyncMemories)
        val msg401 = (vm.effects.first() as EngineEffect.ShowMessage).message
        assertTrue(msg401.contains("有效体验码"))
        assertFalse(vm.isSyncing.first { !it })

        // 429 quota cap
        statusToReturn[0] = 429
        val token = listOf("eng", "69200000", "team01", "d".repeat(24)).joinToString("_")
        vm.onIntent(EngineIntent.UpdateSyncKeyInput(token))
        vm.onIntent(EngineIntent.SyncMemories)
        val msg429 = (vm.effects.first() as EngineEffect.ShowMessage).message
        assertTrue(msg429.contains("额度已达上限"))
        assertFalse(vm.isSyncing.first { !it })

        // Network/DNS failure must never echo raw hostname in UI notice
        statusToReturn[0] = 0
        vm.onIntent(EngineIntent.SyncMemories)
        val msgNet = (vm.effects.first() as EngineEffect.ShowMessage).message
        assertFalse(msgNet.contains("secret-internal-host.invalid"))
        assertTrue(msgNet.contains("暂未连上同步服务"))
    }
}
