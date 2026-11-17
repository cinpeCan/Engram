package io.github.cinpecan.engram

import android.app.Application
import io.github.cinpecan.engram.di.EngramContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EngramApplication : Application() {

    lateinit var container: EngramContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = EngramContainer(this)
        appScope.launch {
            container.memoryRepository.initializeAndSeedIfNeeded()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized) {
            container.edgeEngine.onTrimMemory(level)
        }
    }
}
