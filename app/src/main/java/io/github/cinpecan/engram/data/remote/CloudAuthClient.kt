package io.github.cinpecan.engram.data.remote

import io.github.cinpecan.engram.data.repository.CloudSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lightweight zero-quota auth/status probe against the cloud gateway (`GET /api/v1/auth/status`).
 */
class CloudAuthClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun verifySession(
        baseUrl: String,
        syncKey: String,
    ): CloudSessionState = withContext(ioDispatcher) {
        val effectiveBaseUrl = DefaultCloudEndpointProvider.resolveEffectiveBaseUrl(baseUrl)
        val token = DefaultCloudEndpointProvider.resolveBearerTokenOrNull(syncKey)
        val url = "$effectiveBaseUrl/api/v1/auth/status"

        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        if (token != null) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                when (response.code) {
                    200 -> {
                        val dto = runCatching { json.decodeFromString<AuthStatusResponseDto>(raw) }
                            .getOrDefault(AuthStatusResponseDto())
                        CloudSessionState.Ready(remainingQuota = dto.remainingQuota)
                    }
                    401 -> CloudSessionState.InvalidToken
                    429 -> CloudSessionState.QuotaExhausted
                    404 -> verifyLegacyHealth(baseUrl = baseUrl, effectiveBaseUrl = effectiveBaseUrl)
                    else -> CloudSessionState.Unreachable
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CloudSessionState.Unreachable
        }
    }

    private fun verifyLegacyHealth(
        baseUrl: String,
        effectiveBaseUrl: String,
    ): CloudSessionState {
        if (baseUrl.trim().isEmpty()) {
            return CloudSessionState.Unreachable
        }
        val healthReq = Request.Builder()
            .url("$effectiveBaseUrl/health")
            .get()
            .build()
        return okHttpClient.newCall(healthReq).execute().use { resp ->
            if (resp.isSuccessful) {
                CloudSessionState.Ready(remainingQuota = null)
            } else {
                CloudSessionState.Unreachable
            }
        }
    }
}
