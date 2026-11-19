package io.github.cinpecan.engram.data.remote

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCloudEndpointProviderTest {

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun aesGcmXorSplitRoundtripAndTamperRejection() {
        val sampleEndpoint = "https://gateway.example.invalid"
        val rng = SecureRandom()
        val maskA = ByteArray(32).also { rng.nextBytes(it) }
        val secretKey = ByteArray(32).also { rng.nextBytes(it) }
        val maskB = ByteArray(32) { idx -> (secretKey[idx].toInt() xor maskA[idx].toInt()).toByte() }
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secretKey, "AES"), GCMParameterSpec(128, iv))
        val payload = cipher.doFinal(sampleEndpoint.toByteArray(Charsets.UTF_8))

        val decrypted = DefaultCloudEndpointProvider.decryptEndpointOrNull(
            seedAHex = maskA.toHex(),
            seedBHex = maskB.toHex(),
            nonceHex = iv.toHex(),
            payloadHex = payload.toHex(),
        )
        assertEquals(sampleEndpoint, decrypted)

        // Flipping one byte of ciphertext must fail GCM authentication cleanly
        val tampered = payload.copyOf().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
        assertNull(
            DefaultCloudEndpointProvider.decryptEndpointOrNull(
                seedAHex = maskA.toHex(),
                seedBHex = maskB.toHex(),
                nonceHex = iv.toHex(),
                payloadHex = tampered.toHex(),
            ),
        )

        // Blank or malformed fields return null
        assertNull(DefaultCloudEndpointProvider.decryptEndpointOrNull("", "", "", ""))
        assertNull(DefaultCloudEndpointProvider.decryptEndpointOrNull("abcd", "ef01", "1234", "5678"))
    }

    @Test
    fun resolveEffectiveBaseUrlPrioritizesUserOverrideOverDefaultAndLocalFallback() {
        val overrideUrl = DefaultCloudEndpointProvider.resolveEffectiveBaseUrl("  https://custom.example.invalid/ ")
        assertEquals("https://custom.example.invalid", overrideUrl)

        val defaultResolved = DefaultCloudEndpointProvider.resolveEffectiveBaseUrl("")
        if (DefaultCloudEndpointProvider.hasBundledDefaultEndpoint) {
            val bundled = DefaultCloudEndpointProvider.resolveOrNull()
            assertTrue(!bundled.isNullOrBlank() && bundled.startsWith("https://"))
            assertEquals(bundled, defaultResolved)
        } else {
            assertNull(DefaultCloudEndpointProvider.resolveOrNull())
            assertEquals(DefaultCloudEndpointProvider.LOCAL_FALLBACK_BASE_URL, defaultResolved)
        }
    }

    @Test
    fun resolveBearerTokenFiltersBlankAndPlaceholderKeys() {
        assertNull(DefaultCloudEndpointProvider.resolveBearerTokenOrNull(null))
        assertNull(DefaultCloudEndpointProvider.resolveBearerTokenOrNull(""))
        assertNull(DefaultCloudEndpointProvider.resolveBearerTokenOrNull("   "))
        assertNull(DefaultCloudEndpointProvider.resolveBearerTokenOrNull("engram-demo"))
        assertNull(DefaultCloudEndpointProvider.resolveBearerTokenOrNull("ENGRAM-DEMO"))

        val dynamicToken = listOf("eng", "69200000", "team01", "a".repeat(24)).joinToString("_")
        assertEquals(dynamicToken, DefaultCloudEndpointProvider.resolveBearerTokenOrNull("  $dynamicToken  "))
    }
}
