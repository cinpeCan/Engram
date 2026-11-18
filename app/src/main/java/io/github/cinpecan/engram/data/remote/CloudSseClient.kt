package io.github.cinpecan.engram.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class CloudSseClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    fun streamAnalyze(
        baseUrl: String,
        requestPayload: AnalysisStreamRequestDto,
    ): Flow<AnalysisStreamChunkDto> = flow {
        val url = baseUrl.trim().trimEnd('/') + "/api/v1/analyze/stream"
        val body = json.encodeToString(requestPayload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Cloud gateway HTTP ${response.code}")
            }
            val source = response.body?.source() ?: error("Empty SSE body")
            var sawDoneChunk = false
            var sawDoneMarker = false

            while (currentCoroutineContext().isActive && !source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank() || !line.startsWith("data:")) continue
                val raw = line.removePrefix("data:").trim()
                if (raw == "[DONE]") {
                    sawDoneMarker = true
                    break
                }
                val chunk = json.decodeFromString<AnalysisStreamChunkDto>(raw)
                if (!chunk.error.isNullOrBlank()) {
                    error(chunk.error)
                }
                if (chunk.done) {
                    sawDoneChunk = true
                }
                emit(chunk)
            }

            if (!sawDoneChunk && !sawDoneMarker) {
                error("Incomplete cloud SSE stream")
            }
        }
    }.flowOn(Dispatchers.IO)
}
