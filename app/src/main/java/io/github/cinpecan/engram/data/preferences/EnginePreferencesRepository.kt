package io.github.cinpecan.engram.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.cinpecan.engram.domain.model.InferenceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.enginePrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "engram_engine_prefs",
)

data class EnginePreferences(
    val inferenceMode: InferenceMode = InferenceMode.OFFLINE_EDGE,
    val selectedModelId: String = DEFAULT_MODEL_ID,
    val serverBaseUrl: String = DEFAULT_SERVER_BASE_URL,
    val syncKey: String = DEFAULT_SYNC_KEY,
    val hasSeededStoryline: Boolean = false,
) {
    companion object {
        const val DEFAULT_MODEL_ID = "qwen2.5-0.5b-instruct-mnn"
        const val DEFAULT_SERVER_BASE_URL = ""
        const val DEFAULT_SYNC_KEY = ""
        const val PLACEHOLDER_SYNC_KEY = "engram-demo"
    }
}

/**
 * Stores lightweight runtime configuration switches in DataStore Preferences (never memory payloads).
 */
class EnginePreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.enginePrefsDataStore)

    val preferencesFlow: Flow<EnginePreferences> = dataStore.data.map { prefs ->
        val modeName = prefs[KEY_INFERENCE_MODE] ?: InferenceMode.OFFLINE_EDGE.name
        val mode = runCatching { InferenceMode.valueOf(modeName) }
            .getOrDefault(InferenceMode.OFFLINE_EDGE)
        EnginePreferences(
            inferenceMode = mode,
            selectedModelId = prefs[KEY_SELECTED_MODEL_ID] ?: EnginePreferences.DEFAULT_MODEL_ID,
            serverBaseUrl = prefs[KEY_SERVER_BASE_URL] ?: EnginePreferences.DEFAULT_SERVER_BASE_URL,
            syncKey = prefs[KEY_SYNC_KEY] ?: EnginePreferences.DEFAULT_SYNC_KEY,
            hasSeededStoryline = prefs[KEY_HAS_SEEDED] ?: false,
        )
    }

    suspend fun setInferenceMode(mode: InferenceMode) {
        dataStore.edit { prefs ->
            prefs[KEY_INFERENCE_MODE] = mode.name
        }
    }

    suspend fun setSelectedModelId(modelId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_SELECTED_MODEL_ID] = modelId
        }
    }

    suspend fun setServerBaseUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        dataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(KEY_SERVER_BASE_URL)
            } else {
                prefs[KEY_SERVER_BASE_URL] = cleaned
            }
        }
    }

    suspend fun setSyncKey(syncKey: String) {
        val cleaned = syncKey.trim()
        dataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(KEY_SYNC_KEY)
            } else {
                prefs[KEY_SYNC_KEY] = cleaned
            }
        }
    }

    suspend fun updateSyncConfig(serverBaseUrl: String, syncKey: String) {
        val cleanedUrl = serverBaseUrl.trim().trimEnd('/')
        val cleanedKey = syncKey.trim()
        dataStore.edit { prefs ->
            if (cleanedUrl.isEmpty()) {
                prefs.remove(KEY_SERVER_BASE_URL)
            } else {
                prefs[KEY_SERVER_BASE_URL] = cleanedUrl
            }
            if (cleanedKey.isEmpty()) {
                prefs.remove(KEY_SYNC_KEY)
            } else {
                prefs[KEY_SYNC_KEY] = cleanedKey
            }
        }
    }

    suspend fun markStorylineSeeded() {
        dataStore.edit { prefs ->
            prefs[KEY_HAS_SEEDED] = true
        }
    }

    private companion object {
        val KEY_INFERENCE_MODE = stringPreferencesKey("inference_mode")
        val KEY_SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        val KEY_SERVER_BASE_URL = stringPreferencesKey("server_base_url")
        val KEY_SYNC_KEY = stringPreferencesKey("sync_key")
        val KEY_HAS_SEEDED = booleanPreferencesKey("has_seeded_storyline")
    }
}
