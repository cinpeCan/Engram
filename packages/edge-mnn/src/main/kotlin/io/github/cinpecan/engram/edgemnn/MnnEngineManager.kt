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

    @Volatile
    private var sessionHandle: Long = 0L

    private var loadedModelId: String? = null
    private var loadedModelDir: String = ""
    private var uiHiddenUnloadJob: Job? = null

    override suspend fun loadModel(modelId: String, modelDirPath: String): Result<Unit> =
        withContext(serialDispatcher) {
            uiHiddenUnloadJob?.cancel()
            uiHiddenUnloadJob = null
            jniMutex.withLock {
                runCatching {
                    if (sessionHandle != 0L && loadedModelId == modelId && loadedModelDir == modelDirPath) {
                        return@runCatching
                    }
                    if (sessionHandle != 0L) {
                        _state.value = EngineState.Releasing(UnloadReason.MODEL_SWITCH)
                        nativeBridge.releaseSession(sessionHandle)
                        sessionHandle = 0L
                        loadedModelId = null
                    }
                    _state.value = EngineState.Loading(modelId)
                    val handle = nativeBridge.createSession(modelDirPath)
                    check(handle != 0L) { "Native createSession returned null handle for $modelId" }
                    sessionHandle = handle
                    loadedModelId = modelId
                    loadedModelDir = modelDirPath
                    _state.value = EngineState.Ready(
                        modelId = modelId,
                        backendName = resolveBackendName(modelDirPath),
                        nativeHeapBytes = memoryProvider.nativeHeapAllocatedBytes(),
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
                    val backendName = resolveBackendName(loadedModelDir)
                    _state.value = EngineState.Generating(activeId, backendName)

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
                    val heapBytes = memoryProvider.nativeHeapAllocatedBytes()
                    val telemetry = InferenceTelemetry(
                        engineLabel = backendName,
                        modelId = activeId,
                        ttftMs = ttft,
                        totalMs = total,
                        generatedTokens = tokenCount,
                        tokensPerSec = tokPerSec,
                        nativeHeapMb = heapBytes / (1024.0 * 1024.0),
                        isRealMnn = nativeBridge.hasRealMnnBackend(),
                    )
                    capturedTelemetry = telemetry
                    _lastTelemetry.value = telemetry

                    if (sessionHandle != 0L) {
                        _state.value = EngineState.Ready(
                            modelId = activeId,
                            backendName = backendName,
                            nativeHeapBytes = heapBytes,
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
            engineLabel = resolveBackendName(loadedModelDir),
            modelId = loadedModelId ?: DEFAULT_SESSION_ID,
            ttftMs = 0L,
            totalMs = 1L,
            generatedTokens = 0,
            tokensPerSec = 0.0,
            nativeHeapMb = memoryProvider.nativeHeapAllocatedBytes() / (1024.0 * 1024.0),
            isRealMnn = nativeBridge.hasRealMnnBackend(),
        )
        send(StreamChunk.Completed(fullBuilder.toString(), telemetry))
    }

    override suspend fun embedText(text: String, dims: Int): FloatArray =
        withContext(serialDispatcher) {
            uiHiddenUnloadJob?.cancel()
            uiHiddenUnloadJob = null
            jniMutex.withLock {
                ensureDefaultSessionLocked()
                nativeBridge.embed(sessionHandle, text, dims)
            }
        }

    override suspend fun unload(reason: UnloadReason): Long =
        withContext(serialDispatcher) {
            uiHiddenUnloadJob?.cancel()
            uiHiddenUnloadJob = null
            jniMutex.withLock {
                val handle = sessionHandle
                if (handle == 0L) {
                    _state.value = EngineState.Unloaded
                    return@withLock 0L
                }
                val beforeBytes = memoryProvider.nativeHeapAllocatedBytes()
                _state.value = EngineState.Releasing(reason)
                nativeBridge.releaseSession(handle)
                sessionHandle = 0L
                loadedModelId = null
                loadedModelDir = ""
                val afterBytes = memoryProvider.nativeHeapAllocatedBytes()
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

    override fun currentNativeHeapBytes(): Long = memoryProvider.nativeHeapAllocatedBytes()

    private fun ensureDefaultSessionLocked() {
        if (sessionHandle != 0L) return
        val id = loadedModelId ?: DEFAULT_SESSION_ID
        _state.value = EngineState.Loading(id)
        val handle = nativeBridge.createSession(loadedModelDir)
        check(handle != 0L) { "Native createSession failed during lazy initialization" }
        sessionHandle = handle
        loadedModelId = id
        _state.value = EngineState.Ready(
            modelId = id,
            backendName = resolveBackendName(loadedModelDir),
            nativeHeapBytes = memoryProvider.nativeHeapAllocatedBytes(),
        )
    }

    private fun resolveBackendName(modelDir: String): String =
        if (nativeBridge.hasRealMnnBackend() && modelDir.isNotBlank()) {
            "MNN-LLM (ARM64)"
        } else {
            "MNN JNI Edge Runtime"
        }

    companion object {
        const val DEFAULT_SESSION_ID: String = "engram-edge-default"
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
