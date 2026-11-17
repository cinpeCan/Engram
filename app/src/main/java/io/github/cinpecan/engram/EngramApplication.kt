package io.github.cinpecan.engram

import android.app.Application
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.ModelDownloadManager
import io.github.cinpecan.engram.data.repository.MemoryRepository
import io.github.cinpecan.engram.di.dataModule
import io.github.cinpecan.engram.di.uiModule
import io.github.cinpecan.engram.edgemnn.EdgeAiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class EngramApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@EngramApplication)
            modules(dataModule, uiModule)
        }
        val koin = getKoin()
        val preferencesRepository: EnginePreferencesRepository = koin.get()
        val modelDownloadManager: ModelDownloadManager = koin.get()
        val edgeEngine: EdgeAiEngine = koin.get()
        val memoryRepository: MemoryRepository = koin.get()

        appScope.launch {
            val prefs = preferencesRepository.preferencesFlow.first()
            modelDownloadManager.resolveReadyModelDirPath(prefs.selectedModelId)?.let { dirPath ->
                edgeEngine.configureDefaultModel(prefs.selectedModelId, dirPath)
            }
            memoryRepository.initializeAndSeedIfNeeded()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        GlobalContext.getOrNull()?.get<EdgeAiEngine>()?.onTrimMemory(level)
    }
}
