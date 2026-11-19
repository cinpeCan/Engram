package io.github.cinpecan.engram.data.remote

import io.github.cinpecan.engram.BuildConfig
import io.github.cinpecan.engram.data.preferences.EnginePreferences
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Resolves the build-time encrypted default cloud gateway URL on demand inside memory,
 * without storing plaintext URL literals in DEX constants, DataStore, or logs.
 */
object DefaultCloudEndpointProvider {

    const val LOCAL_FALLBACK_BASE_URL = "http://127.0.0.1:8787"

    val hasBundledDefaultEndpoint: Boolean
        get() = BuildConfig.CONFIG_PAYLOAD.isNotBlank() &&
            BuildConfig.CONFIG_SEED_A.length == 64 &&
            BuildConfig.CONFIG_SEED_B.length == 64 &&
            BuildConfig.CONFIG_NONCE.length == 24

    fun resolveOrNull(): String? = decryptEndpointOrNull(
        seedAHex = BuildConfig.CONFIG_SEED_A,
        seedBHex = BuildConfig.CONFIG_SEED_B,
        nonceHex = BuildConfig.CONFIG_NONCE,
        payloadHex = BuildConfig.CONFIG_PAYLOAD,
    )

    internal fun decryptEndpointOrNull(
        seedAHex: String,
        seedBHex: String,
        nonceHex: String,
        payloadHex: String,
    ): String? {
        if (seedAHex.length != 64 || seedBHex.length != 64 || nonceHex.length != 24 || payloadHex.isBlank()) {
            return null
        }
        val maskA = decodeHexOrNull(seedAHex) ?: return null
        val maskB = decodeHexOrNull(seedBHex) ?: return null
        val iv = decodeHexOrNull(nonceHex) ?: return null
        val cipherAndTag = decodeHexOrNull(payloadHex) ?: return null
        if (maskA.size != 32 || maskB.size != 32 || iv.size != 12 || cipherAndTag.size <= 16) {
            return null
        }

        val secretKey = ByteArray(32) { idx -> (maskA[idx].toInt() xor maskB[idx].toInt()).toByte() }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(secretKey, "AES"),
                GCMParameterSpec(128, iv),
            )
            val plain = cipher.doFinal(cipherAndTag)
            plain.toString(Charsets.UTF_8).trim().trimEnd('/').takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            secretKey.fill(0)
        }
    }

    fun resolveEffectiveBaseUrl(userConfiguredUrl: String): String =
        userConfiguredUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }
            ?: resolveOrNull()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: LOCAL_FALLBACK_BASE_URL

    fun resolveBearerTokenOrNull(syncKey: String?): String? {
        val cleaned = syncKey?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        if (cleaned.equals(EnginePreferences.PLACEHOLDER_SYNC_KEY, ignoreCase = true)) {
            return null
        }
        return cleaned
    }

    private fun decodeHexOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hex[i * 2].digitToIntOrNull(16) ?: return null
            val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
