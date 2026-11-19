package io.github.cinpecan.engram.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.CloudAuthClient
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSessionMonitorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun lengthFilterRejectsBlankPlaceholderAndShortDefaultTokens() {
        assertFalse(CloudSessionMonitor.passesLengthFilter("", "", hasBundledDefaultEndpoint = true))
        assertFalse(CloudSessionMonitor.passesLengthFilter("", "engram-demo", hasBundledDefaultEndpoint = true))
        assertFalse(CloudSessionMonitor.passesLengthFilter("", "eng_short", hasBundledDefaultEndpoint = true))
        assertFalse(CloudSessionMonitor.passesLengthFilter("", "x".repeat(40), hasBundledDefaultEndpoint = true))

        val validDefaultToken = listOf("eng", "69200000", "team01", "a".repeat(24)).joinToString("_")
        assertTrue(CloudSessionMonitor.passesLengthFilter("", validDefaultToken, hasBundledDefaultEndpoint = true))

        // Custom URL allows concise >=4 char team sync codes
        assertFalse(CloudSessionMonitor.passesLengthFilter("http://127.0.0.1:8787", "abc", hasBundledDefaultEndpoint = true))
        assertTrue(CloudSessionMonitor.passesLengthFilter("http://127.0.0.1:8787", "team", hasBundledDefaultEndpoint = true))
    }

    @Test
    fun reactiveMonitorFiltersLocallyThenDebouncesAndTransitionsToReady401And429() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.IO),
            produceFile = { tempFolder.newFile("monitor_prefs.preferences_pb") },
        )
        val prefsRepo = EnginePreferencesRepository(dataStore)
        val requestCount = AtomicInteger(0)
        val seenAuths = mutableListOf<String?>()
        val responseStatus = intArrayOf(200)

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                requestCount.incrementAndGet()
                seenAuths.add(req.header("Authorization"))
                val code = responseStatus[0]
                val body = when (code) {
                    200 -> """{"status":"ok","mode":"demo","remainingQuota":42}"""
                    401 -> """{"detail":"invalid"}"""
                    else -> """{"detail":"quota"}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("HTTP $code")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val monitor = CloudSessionMonitor(
            preferencesRepository = prefsRepo,
            authClient = CloudAuthClient(mockHttp, ioDispatcher = testDispatcher),
            hasBundledDefaultEndpoint = true,
            debounceMs = 200L,
            scope = CoroutineScope(backgroundScope.coroutineContext + testDispatcher),
        )

        backgroundScope.launch(testDispatcher) {
            monitor.state.collect {}
        }
        prefsRepo.preferencesFlow.first()
        runCurrent()

        // Short/incomplete token stays IdleUnconfigured with zero HTTP calls
        monitor.onConfigEdited("", "eng_123")
        runCurrent()
        assertEquals(CloudSessionState.IdleUnconfigured, monitor.state.value)
        assertEquals(0, requestCount.get())

        // Rapid edits coalesce: Verifying immediately, then only final valid token hits network after debounce
        val token1 = listOf("eng", "69200000", "team01", "a".repeat(24)).joinToString("_")
        val token2 = listOf("eng", "69200000", "team01", "b".repeat(24)).joinToString("_")
        monitor.onConfigEdited("", token1)
        advanceTimeBy(100L)
        monitor.onConfigEdited("", token2)
        runCurrent()
        assertEquals(CloudSessionState.Verifying, monitor.state.value)
        assertEquals(0, requestCount.get())

        advanceTimeBy(250L)
        advanceUntilIdle()
        val ready = monitor.state.first { it is CloudSessionState.Ready } as CloudSessionState.Ready
        assertEquals(42, ready.remainingQuota)
        assertEquals(1, requestCount.get())
        assertEquals("Bearer $token2", seenAuths.single())

        // 401 -> InvalidToken
        responseStatus[0] = 401
        val tokenBad = listOf("eng", "69200000", "team01", "c".repeat(24)).joinToString("_")
        monitor.onConfigEdited("", tokenBad)
        advanceTimeBy(250L)
        advanceUntilIdle()
        assertEquals(CloudSessionState.InvalidToken, monitor.state.value)

        // 429 -> QuotaExhausted
        responseStatus[0] = 429
        monitor.retryVerification()
        advanceTimeBy(250L)
        advanceUntilIdle()
        assertEquals(CloudSessionState.QuotaExhausted, monitor.state.value)
    }
}
