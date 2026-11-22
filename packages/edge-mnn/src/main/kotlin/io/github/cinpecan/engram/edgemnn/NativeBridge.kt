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

    fun generateChatStream(
        sessionHandle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        callback: NativeStreamCallback,
    ) {
        val combinedPrompt = buildString {
            if (systemPrompt.isNotEmpty()) {
                append(systemPrompt)
                append("\n\n")
            }
            append(userPrompt)
        }
        generateStream(sessionHandle, combinedPrompt, maxTokens, callback)
    }
}
