package io.github.cinpecan.engram.di

import io.github.cinpecan.engram.data.calendar.AndroidSystemCalendarObserver
import io.github.cinpecan.engram.data.calendar.SystemCalendarObserver
import io.github.cinpecan.engram.data.local.EngramDatabase
import io.github.cinpecan.engram.data.local.LocalMemoryStore
import io.github.cinpecan.engram.data.local.RoomMemoryStore
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.CloudAuthClient
import io.github.cinpecan.engram.data.remote.CloudSseClient
import io.github.cinpecan.engram.data.remote.MemorySyncClient
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.data.repository.AnswerRepository
import io.github.cinpecan.engram.data.repository.CloudSessionMonitor
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.data.repository.UnifiedInferenceRouter
import io.github.cinpecan.engram.domain.analyzer.MemoryAnalyzer
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import io.github.cinpecan.engram.edgemnn.MnnEngineManager
import io.github.cinpecan.engram.ui.answer.AnswerSheetRoute
import io.github.cinpecan.engram.ui.capture.CaptureRoute
import io.github.cinpecan.engram.ui.capture.RawMemoryRoute
import io.github.cinpecan.engram.ui.engine.EngineBottomSheetRoute
import io.github.cinpecan.engram.ui.graph.GraphRoute
import io.github.cinpecan.engram.ui.navigation.BottomSheetSceneStrategy
import io.github.cinpecan.engram.ui.navigation.EngramNavKey
import io.github.cinpecan.engram.ui.viewmodel.AnswerViewModel
import io.github.cinpecan.engram.ui.viewmodel.CaptureViewModel
import io.github.cinpecan.engram.ui.viewmodel.EngineViewModel
import io.github.cinpecan.engram.ui.viewmodel.GraphViewModel
import io.github.cinpecan.engram.ui.viewmodel.RawMemoryViewModel
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
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
    single<SystemCalendarObserver> { AndroidSystemCalendarObserver(androidContext()) }
    single { CloudAuthClient(okHttpClient = get(), json = get()) }
    single { CloudSseClient(okHttpClient = get(), json = get()) }
    single { MemorySyncClient(okHttpClient = get(), json = get()) }
    single {
        CloudSessionMonitor(
            preferencesRepository = get(),
            authClient = get(),
        )
    }
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
            syncClient = get(),
            calendarObserver = get(),
            cloudSessionMonitor = get(),
        )
    }
    single {
        ModelDownloadManager(
            context = androidContext(),
            okHttpClient = get(),
            json = get(),
        )
    }
    single {
        AnswerRepository(
            store = get(),
            edgeEngine = get(),
            cloudSseClient = get(),
            preferencesRepository = get(),
            cloudSessionMonitor = get(),
        )
    }
}

@OptIn(KoinExperimentalAPI::class)
val uiModule = module {
    viewModelOf(::CaptureViewModel)
    viewModel {
        EngineViewModel(
            edgeEngine = get(),
            downloadManager = get(),
            preferencesRepository = get(),
            memoryRepository = get(),
            cloudSessionMonitor = get(),
        )
    }
    viewModel { (memoryId: String) ->
        GraphViewModel(
            memoryId = memoryId,
            memoryRepository = get(),
        )
    }
    viewModel { (memoryId: String) ->
        RawMemoryViewModel(
            memoryId = memoryId,
            memoryRepository = get(),
        )
    }
    viewModel { (question: String) ->
        AnswerViewModel(
            question = question,
            answerRepository = get(),
        )
    }

    navigation<EngramNavKey.Capture> {
        CaptureRoute()
    }

    navigation<EngramNavKey.GraphSheet>(
        metadata = BottomSheetSceneStrategy.bottomSheet(expandable = true),
    ) { route ->
        GraphRoute(memoryId = route.memoryId)
    }

    navigation<EngramNavKey.RawMemorySheet>(
        metadata = BottomSheetSceneStrategy.bottomSheet(expandable = false),
    ) { route ->
        RawMemoryRoute(memoryId = route.memoryId)
    }

    navigation<EngramNavKey.EngineSheet>(
        metadata = BottomSheetSceneStrategy.bottomSheet(expandable = false),
    ) {
        EngineBottomSheetRoute()
    }

    navigation<EngramNavKey.AnswerSheet>(
        metadata = BottomSheetSceneStrategy.bottomSheet(expandable = true),
    ) { route ->
        AnswerSheetRoute(question = route.question)
    }
}
