package io.github.cinpecan.engram.di

import io.github.cinpecan.engram.data.local.EngramDatabase
import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.local.RoomMemoryStore
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.data.repository.UnifiedInferenceRouter
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.MnnEngineManager
import io.github.cinpecan.engram.ui.capture.CaptureRoute
import io.github.cinpecan.engram.ui.engine.EngineBottomSheetRoute
import io.github.cinpecan.engram.ui.graph.GraphRoute
import io.github.cinpecan.engram.ui.navigation.BottomSheetSceneStrategy
import io.github.cinpecan.engram.ui.navigation.EngramNavKey
import io.github.cinpecan.engram.ui.viewmodel.CaptureViewModel
import io.github.cinpecan.engram.ui.viewmodel.EngineViewModel
import io.github.cinpecan.engram.ui.viewmodel.GraphViewModel
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

val dataModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
    single {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    single<EdgeAiEngine> { MnnEngineManager() }
    single { EngramDatabase.create(androidContext()) }
    single { get<EngramDatabase>().memoryDao() }
    single<LocalMemoryStore> {
        RoomMemoryStore(
            db = get(),
            dao = get(),
            json = get(),
        )
    }
    single { EnginePreferencesRepository(androidContext()) }
    single { MemoryAnalyzer(json = get()) }
    single { CloudSseClient(okHttpClient = get(), json = get()) }
    single {
        UnifiedInferenceRouter(
            edgeEngine = get(),
            cloudSseClient = get(),
            analyzer = get(),
        )
    }
    single {
        MemoryRepository(
            store = get(),
            inferenceRouter = get(),
            preferencesRepository = get(),
        )
    }
    single {
        ModelDownloadManager(
            context = androidContext(),
            okHttpClient = get(),
            json = get(),
        )
    }
}

@OptIn(KoinExperimentalAPI::class)
val uiModule = module {
    viewModelOf(::CaptureViewModel)
    viewModelOf(::GraphViewModel)
    viewModelOf(::EngineViewModel)

    navigation<EngramNavKey.Capture> {
        CaptureRoute()
    }

    navigation<EngramNavKey.GraphSheet>(
        metadata = BottomSheetSceneStrategy.bottomSheet(expandable = true),
    ) { route ->
        GraphRoute(initialMemoryId = route.initialMemoryId)
    }

    navigation<EngramNavKey.EngineSheet>(
        metadata = BottomSheetSceneStrategy.bottomSheet(expandable = false),
    ) {
        EngineBottomSheetRoute()
    }
}
