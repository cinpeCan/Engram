package io.github.cinpecan.engram.di

import android.content.Context
import io.github.cinpecan.engram.data.local.EngramSqliteStore
import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.data.repository.UnifiedInferenceRouter
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.MnnEngineManager
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class EngramContainer(context: Context) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val edgeEngine: EdgeAiEngine = MnnEngineManager()

    val memoryStore: LocalMemoryStore = EngramSqliteStore(context, json)

    val preferencesRepository: EnginePreferencesRepository = EnginePreferencesRepository(context)

    val memoryAnalyzer: MemoryAnalyzer = MemoryAnalyzer(edgeEngine, json)

    val memoryRepository: MemoryRepository = MemoryRepository(
        store = memoryStore,
        engine = edgeEngine,
        analyzer = memoryAnalyzer,
        preferencesRepository = preferencesRepository,
    )

    val cloudSseClient: CloudSseClient = CloudSseClient(okHttpClient, json)

    val inferenceRouter: UnifiedInferenceRouter = UnifiedInferenceRouter(
        memoryRepository = memoryRepository,
        edgeEngine = edgeEngine,
        cloudSseClient = cloudSseClient,
    )

    val modelDownloadManager: ModelDownloadManager = ModelDownloadManager(
        context = context,
        okHttpClient = okHttpClient,
        json = json,
    )
}
