package io.github.cinpecan.engram.edgemnn

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates all on-device MNN JNI interactions on a dedicated single-thread serial dispatcher,
 * coalesces streamed tokens into frame-paced windows, and enforces [onTrimMemory] unloading.
 */
class MnnEngineManager(
    private val nativeBridge: NativeBridge = JniNativeBridge(),
    private val memoryProvider: MemoryProvider = AndroidMemoryProvider(),
    private val serialDispatcher: CoroutineDispatcher = defaultSerialDispatcher(),
    private val lifecycleScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val coalescer: TokenStreamCoalescer = TokenStreamCoalescer(TokenStreamCoalescer.DEFAULT_WINDOW_MS),
    private val uiHiddenGraceMs: Long = DEFAULT_UI_HIDDEN_GRACE_MS,
) : EdgeAiEngine {

    private val jniMutex = Mutex()

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _lastTelemetry = MutableStateFlow<InferenceTelemetry?>(null)
    override val lastTelemetry: StateFlow<InferenceTelemetry?> = _lastTelemetry.asStateFlow()

    override val isBackendAvailable: Boolean
        get() = nativeBridge.hasRealMnnBackend()

    @Volatile
    private var sessionHandle: Long = 0L

    @Volatile
    private var configuredModelId: String = DEFAULT_SESSION_ID

    @Volatile
    private var configuredModelDir: String = ""

    private var loadedModelId: String? = null
    private var loadedModelDir: String = ""
    private var loadedFootprintBytes: Long = 0L
    private var uiHiddenUnloadJob: Job? = null

    override fun configureDefaultModel(modelId: String, modelDirPath: String) {
        configuredModelId = modelId.ifBlank { DEFAULT_SESSION_ID }
        configuredModelDir = modelDirPath
    }

    override suspend fun loadModel(modelId: String, modelDirPath: String): Result<Unit> =
        withContext(serialDispatcher) {
            uiHiddenUnloadJob?.cancel()
            uiHiddenUnloadJob = null
            jniMutex.withLock {
                if (!nativeBridge.hasRealMnnBackend()) {
                    val msg = "MNN native runtime is not bundled in this build"
                    _state.value = EngineState.Error(msg)
                    return@withLock Result.failure(IllegalStateException(msg))
                }
                if (modelDirPath.isBlank()) {
                    val msg = "Model weights have not been downloaded"
                    _state.value = EngineState.Error(msg)
                    return@withLock Result.failure(IllegalArgumentException(msg))
                }
                runCatching {
                    if (sessionHandle != 0L && loadedModelId == modelId && loadedModelDir == modelDirPath) {
                        configuredModelId = modelId
                        configuredModelDir = modelDirPath
                        return@runCatching
                    }
                    if (sessionHandle != 0L) {
                        _state.value = EngineState.Releasing(UnloadReason.MODEL_SWITCH)
                        nativeBridge.releaseSession(sessionHandle)
                        sessionHandle = 0L
                        loadedModelId = null
                        loadedModelDir = ""
                        loadedFootprintBytes = 0L
                    }
                    val beforeBytes = memoryProvider.residentMemoryBytes()
                    _state.value = EngineState.Loading(modelId)
                    val handle = nativeBridge.createSession(modelDirPath)
                    check(handle != 0L) { "Native createSession returned null handle for $modelId" }
                    val afterBytes = memoryProvider.residentMemoryBytes()
                    val footprint = (afterBytes - beforeBytes).coerceAtLeast(0L)

                    sessionHandle = handle
                    loadedModelId = modelId
                    loadedModelDir = modelDirPath
                    configuredModelId = modelId
                    configuredModelDir = modelDirPath
                    loadedFootprintBytes = footprint
                    _state.value = EngineState.Ready(
                        modelId = modelId,
                        backendName = BACKEND_NAME_MNN,
                        residentMemoryBytes = afterBytes,
                        modelFootprintBytes = footprint,
                    )
                }.onFailure { err ->
                    _state.value = EngineState.Error(err.message ?: "Failed to load model $modelId")
                }
            }
        }

    override fun streamGenerate(prompt: String, maxTokens: Int): Flow<StreamChunk> = channelFlow {
        uiHiddenUnloadJob?.cancel()
        uiHiddenUnloadJob = null

        val rawTokenChannel = Channel<String>(capacity = Channel.UNLIMITED)
        var capturedTelemetry: InferenceTelemetry? = null

        val producer = launch(serialDispatcher) {
            try {
                jniMutex.withLock {
                    ensureDefaultSessionLocked()
                    val activeId = loadedModelId ?: DEFAULT_SESSION_ID
                    _state.value = EngineState.Generating(activeId, BACKEND_NAME_MNN)

                    var ttft = 0L
                    var total = 1L
                    var tokenCount = 0

                    nativeBridge.generateStream(
                        sessionHandle = sessionHandle,
                        prompt = prompt,
                        maxTokens = maxTokens,
                        callback = object : NativeStreamCallback {
                            override fun onToken(delta: String) {
                                rawTokenChannel.trySend(delta)
                            }

                            override fun onComplete(ttftMs: Long, totalMs: Long, generatedTokens: Int) {
                                ttft = ttftMs
                                total = totalMs.coerceAtLeast(1L)
                                tokenCount = generatedTokens
                            }
                        },
                    )

                    val tokPerSec = if (total > 0L) {
                        tokenCount.toDouble() * 1000.0 / total.toDouble()
                    } else {
                        0.0
                    }
                    val residentBytes = memoryProvider.residentMemoryBytes()
                    val telemetry = InferenceTelemetry(
                        engineLabel = BACKEND_NAME_MNN,
                        modelId = activeId,
                        ttftMs = ttft,
                        totalMs = total,
                        generatedTokens = tokenCount,
                        tokensPerSec = tokPerSec,
                        residentMemoryMb = residentBytes / (1024.0 * 1024.0),
                        isRealMnn = nativeBridge.hasRealMnnBackend(),
                    )
                    capturedTelemetry = telemetry
                    _lastTelemetry.value = telemetry

                    if (sessionHandle != 0L) {
                        _state.value = EngineState.Ready(
                            modelId = activeId,
                            backendName = BACKEND_NAME_MNN,
                            residentMemoryBytes = residentBytes,
                            modelFootprintBytes = loadedFootprintBytes,
                        )
                    }
                }
            } finally {
                rawTokenChannel.close()
            }
        }

        val fullBuilder = StringBuilder()
        coalescer.coalesce(rawTokenChannel.consumeAsFlow()).collect { windowDelta ->
            fullBuilder.append(windowDelta)
            send(StreamChunk.Delta(windowDelta))
        }
        producer.join()

        val telemetry = capturedTelemetry ?: InferenceTelemetry(
            engineLabel = BACKEND_NAME_MNN,
            modelId = loadedModelId ?: DEFAULT_SESSION_ID,
            ttftMs = 0L,
            totalMs = 1L,
            generatedTokens = 0,
            tokensPerSec = 0.0,
            residentMemoryMb = memoryProvider.residentMemoryBytes() / (1024.0 * 1024.0),
            isRealMnn = nativeBridge.hasRealMnnBackend(),
        )
        send(StreamChunk.Completed(fullBuilder.toString(), telemetry))
    }

    override suspend fun unload(reason: UnloadReason): Long =
        withContext(serialDispatcher) {
            uiHiddenUnloadJob?.cancel()
            uiHiddenUnloadJob = null
            jniMutex.withLock {
                val handle = sessionHandle
                if (handle == 0L) {
                    loadedFootprintBytes = 0L
                    _state.value = EngineState.Unloaded
                    return@withLock 0L
                }
                val beforeBytes = memoryProvider.residentMemoryBytes()
                _state.value = EngineState.Releasing(reason)
                nativeBridge.releaseSession(handle)
                sessionHandle = 0L
                loadedModelId = null
                loadedModelDir = ""
                loadedFootprintBytes = 0L
                val afterBytes = memoryProvider.residentMemoryBytes()
                _state.value = EngineState.Unloaded
                (beforeBytes - afterBytes).coerceAtLeast(0L)
            }
        }

    override fun cancelActiveGeneration() {
        val handle = sessionHandle
        if (handle != 0L) {
            nativeBridge.requestCancel(handle)
        }
    }

    override fun onTrimMemory(level: Int) {
        when {
            level == TRIM_MEMORY_UI_HIDDEN -> {
                uiHiddenUnloadJob?.cancel()
                uiHiddenUnloadJob = lifecycleScope.launch {
                    delay(uiHiddenGraceMs)
                    unload(UnloadReason.UI_HIDDEN_TIMEOUT)
                }
            }
            level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND -> {
                cancelActiveGeneration()
                uiHiddenUnloadJob?.cancel()
                uiHiddenUnloadJob = null
                lifecycleScope.launch {
                    unload(UnloadReason.MEMORY_PRESSURE)
                }
            }
        }
    }

    override fun currentMemoryBytes(): Long = memoryProvider.residentMemoryBytes()

    private fun ensureDefaultSessionLocked() {
        if (sessionHandle != 0L) return
        if (!nativeBridge.hasRealMnnBackend()) {
            error("MNN native runtime is not bundled in this build")
        }
        val id = loadedModelId ?: configuredModelId
        val dir = loadedModelDir.ifBlank { configuredModelDir }
        if (dir.isBlank()) {
            error("On-device MNN model is not loaded")
        }
        val beforeBytes = memoryProvider.residentMemoryBytes()
        _state.value = EngineState.Loading(id)
        val handle = nativeBridge.createSession(dir)
        if (handle == 0L) {
            _state.value = EngineState.Error("Failed to initialize MNN session: $id")
            error("Native createSession failed during lazy initialization")
        }
        val afterBytes = memoryProvider.residentMemoryBytes()
        val footprint = (afterBytes - beforeBytes).coerceAtLeast(0L)
        sessionHandle = handle
        loadedModelId = id
        loadedModelDir = dir
        loadedFootprintBytes = footprint
        _state.value = EngineState.Ready(
            modelId = id,
            backendName = BACKEND_NAME_MNN,
            residentMemoryBytes = afterBytes,
            modelFootprintBytes = footprint,
        )
    }

    companion object {
        const val DEFAULT_SESSION_ID: String = "qwen2.5-0.5b-instruct-mnn"
        const val BACKEND_NAME_MNN: String = "MNN-LLM (ARM64)"
        const val DEFAULT_UI_HIDDEN_GRACE_MS: Long = 15_000L

        const val TRIM_MEMORY_RUNNING_CRITICAL: Int = 15
        const val TRIM_MEMORY_UI_HIDDEN: Int = 20
        const val TRIM_MEMORY_BACKGROUND: Int = 40

        private fun defaultSerialDispatcher(): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "mnn-jni-serial").apply { isDaemon = true }
            }.asCoroutineDispatcher()
    }
}
