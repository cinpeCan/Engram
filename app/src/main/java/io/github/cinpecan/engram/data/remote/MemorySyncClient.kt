package io.github.cinpecan.engram.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MemorySyncClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    suspend fun syncMemories(
        baseUrl: String,
        requestPayload: MemorySyncRequestDto,
        authToken: String? = null,
    ): MemorySyncResponseDto = withContext(Dispatchers.IO) {
        val effectiveBaseUrl = DefaultCloudEndpointProvider.resolveEffectiveBaseUrl(baseUrl)
        val url = "$effectiveBaseUrl/api/v1/memories/sync"
        val body = json.encodeToString(requestPayload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
        DefaultCloudEndpointProvider.resolveBearerTokenOrNull(authToken)?.let { token ->
            requestBuilder.header("Authorization", "Bearer $token")
        }
        val request = requestBuilder.build()

        okHttpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CloudGatewayHttpException(response.code)
            }
            json.decodeFromString<MemorySyncResponseDto>(raw)
        }
    }

    suspend fun pushDeletedMemories(
        baseUrl: String,
        syncKey: String,
        deletedIds: List<String>,
        authToken: String? = null,
    ): MemoryDeleteResponseDto = withContext(Dispatchers.IO) {
        if (deletedIds.isEmpty()) return@withContext MemoryDeleteResponseDto(0)
        val effectiveBaseUrl = DefaultCloudEndpointProvider.resolveEffectiveBaseUrl(baseUrl)
        val url = "$effectiveBaseUrl/api/v1/memories/delete"
        val body = json.encodeToString(
            MemoryDeleteRequestDto(
                syncKey = syncKey,
                deletedIds = deletedIds.take(500),
            ),
        ).toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
        DefaultCloudEndpointProvider.resolveBearerTokenOrNull(authToken)?.let { token ->
            requestBuilder.header("Authorization", "Bearer $token")
        }
        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (response.code == 404) {
                return@withContext MemoryDeleteResponseDto(0)
            }
            if (!response.isSuccessful) {
                throw CloudGatewayHttpException(response.code)
            }
            runCatching { json.decodeFromString<MemoryDeleteResponseDto>(raw) }
                .getOrDefault(MemoryDeleteResponseDto(0))
        }
    }
}
