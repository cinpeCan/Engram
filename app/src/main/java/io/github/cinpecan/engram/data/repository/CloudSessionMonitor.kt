package io.github.cinpecan.engram.data.repository

import androidx.compose.runtime.Immutable
import io.github.cinpecan.engram.data.preferences.EnginePreferences
import io.github.cinpecan.engram.data.preferences.EnginePreferencesRepository
import io.github.cinpecan.engram.data.remote.CloudAuthClient
import io.github.cinpecan.engram.data.remote.DefaultCloudEndpointProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

@Immutable
sealed interface CloudSessionState {
    data object IdleUnconfigured : CloudSessionState
    data object Verifying : CloudSessionState
    data class Ready(val remainingQuota: Int? = null) : CloudSessionState
    data object InvalidToken : CloudSessionState
    data object QuotaExhausted : CloudSessionState
    data object Unreachable : CloudSessionState
}

/**
 * Shared reactive MVI session monitor subscribed by both the root status pill and EngineSheet.
 * Applies an instant local format/length pre-filter before debounced network verification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSessionMonitor internal constructor(
    preferencesRepository: EnginePreferencesRepository,
    private val authClient: CloudAuthClient,
    private val hasBundledDefaultEndpoint: Boolean = DefaultCloudEndpointProvider.hasBundledDefaultEndpoint,
    private val debounceMs: Long = 350L,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(
        preferencesRepository: EnginePreferencesRepository,
        authClient: CloudAuthClient,
    ) : this(
        preferencesRepository = preferencesRepository,
        authClient = authClient,
        hasBundledDefaultEndpoint = DefaultCloudEndpointProvider.hasBundledDefaultEndpoint,
        debounceMs = 350L,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val liveDraftFlow = MutableStateFlow<Pair<String, String>?>(null)
    private val refreshNonce = MutableStateFlow(0L)

    val state: StateFlow<CloudSessionState> = combine(
        preferencesRepository.preferencesFlow.onStart { emit(EnginePreferences()) },
        liveDraftFlow,
        refreshNonce,
    ) { prefs, draft, nonce ->
        val url = (draft?.first ?: prefs.serverBaseUrl).trim().trimEnd('/')
        val key = (draft?.second ?: prefs.syncKey).trim()
        Triple(url, key, nonce)
    }
        .distinctUntilChanged()
        .flatMapLatest { (url, key, _) ->
            if (!passesLengthFilter(url, key, hasBundledDefaultEndpoint)) {
                flowOf<CloudSessionState>(CloudSessionState.IdleUnconfigured)
            } else {
                flow<CloudSessionState> {
                    emit(CloudSessionState.Verifying)
                    if (debounceMs > 0L) {
                        delay(debounceMs)
                    }
                    emit(authClient.verifySession(baseUrl = url, syncKey = key))
                }
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = CloudSessionState.IdleUnconfigured,
        )

    fun onConfigEdited(serverBaseUrl: String, syncKey: String) {
        liveDraftFlow.value = serverBaseUrl.trim().trimEnd('/') to syncKey.trim()
    }

    fun retryVerification() {
        refreshNonce.value += 1L
    }

    companion object {
        private const val MIN_BUNDLED_TOKEN_LEN = 31
        private const val MIN_CUSTOM_KEY_LEN = 4

        fun passesLengthFilter(
            serverBaseUrl: String,
            syncKey: String,
            hasBundledDefaultEndpoint: Boolean = DefaultCloudEndpointProvider.hasBundledDefaultEndpoint,
        ): Boolean {
            val cleanUrl = serverBaseUrl.trim()
            val cleanKey = syncKey.trim()
            if (cleanKey.isEmpty() || cleanKey.equals(EnginePreferences.PLACEHOLDER_SYNC_KEY, ignoreCase = true)) {
                return false
            }
            return if (cleanUrl.isEmpty() && hasBundledDefaultEndpoint) {
                cleanKey.startsWith("eng_") && cleanKey.length >= MIN_BUNDLED_TOKEN_LEN
            } else {
                cleanKey.length >= MIN_CUSTOM_KEY_LEN
            }
        }
    }
}
