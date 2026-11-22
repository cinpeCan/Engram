package io.github.cinpecan.engram.edgemnn

import androidx.annotation.Keep

class JniNativeBridge : NativeBridge {

    private external fun nativeHasRealMnnBackend(): Boolean
    private external fun nativeCreateSession(modelDir: String): Long
    private external fun nativeRequestCancel(handle: Long)
    private external fun nativeReleaseSession(handle: Long)
    private external fun nativeGenerateStream(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        callback: JniStreamCallbackProxy,
    )

    private external fun nativeGenerateChatStream(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        callback: JniStreamCallbackProxy,
    )

    override fun hasRealMnnBackend(): Boolean = nativeHasRealMnnBackend()

    override fun createSession(modelDirPath: String): Long = nativeCreateSession(modelDirPath)

    override fun requestCancel(sessionHandle: Long) {
        if (sessionHandle != 0L) {
            nativeRequestCancel(sessionHandle)
        }
    }

    override fun releaseSession(sessionHandle: Long) {
        if (sessionHandle != 0L) {
            nativeReleaseSession(sessionHandle)
        }
    }

    override fun generateStream(
        sessionHandle: Long,
        prompt: String,
        maxTokens: Int,
        callback: NativeStreamCallback,
    ) {
        nativeGenerateStream(
            handle = sessionHandle,
            prompt = prompt,
            maxTokens = maxTokens,
            callback = JniStreamCallbackProxy(callback),
        )
    }

    override fun generateChatStream(
        sessionHandle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        callback: NativeStreamCallback,
    ) {
        nativeGenerateChatStream(
            handle = sessionHandle,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            maxTokens = maxTokens,
            callback = JniStreamCallbackProxy(callback),
        )
    }

    @Keep
    private class JniStreamCallbackProxy(
        private val delegate: NativeStreamCallback,
    ) {
        @Suppress("unused")
        fun onToken(delta: String) {
            delegate.onToken(delta)
        }

        @Suppress("unused")
        fun onComplete(ttftMs: Long, totalMs: Long, generatedTokens: Int) {
            delegate.onComplete(ttftMs, totalMs, generatedTokens)
        }
    }

    companion object {
        init {
            runCatching { System.loadLibrary("c++_shared") }
            runCatching { System.loadLibrary("MNN") }
            runCatching { System.loadLibrary("MNN_CL") }
            runCatching { System.loadLibrary("MNN_Express") }
            runCatching { System.loadLibrary("llm") }
            System.loadLibrary("engram_mnn_jni")
        }
    }
}
