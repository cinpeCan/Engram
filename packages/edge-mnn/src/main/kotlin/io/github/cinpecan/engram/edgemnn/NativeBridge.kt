package io.github.cinpecan.engram.edgemnn

interface NativeStreamCallback {
    fun onToken(delta: String)
    fun onComplete(ttftMs: Long, totalMs: Long, generatedTokens: Int)
}

interface NativeBridge {
    fun hasRealMnnBackend(): Boolean
    fun createSession(modelDirPath: String): Long
    fun requestCancel(sessionHandle: Long)
    fun releaseSession(sessionHandle: Long)
    fun generateStream(
        sessionHandle: Long,
        prompt: String,
        maxTokens: Int,
        callback: NativeStreamCallback,
    )
    fun embed(sessionHandle: Long, text: String, dims: Int): FloatArray
}
