package io.github.cinpecan.engram.domain.rag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorMathTest {

    @Test
    fun normalizesAndComputesCosineSimilarityAccurately() {
        val a = VectorMath.l2Normalize(floatArrayOf(3f, 4f, 0f))
        val b = VectorMath.l2Normalize(floatArrayOf(6f, 8f, 0f))
        val orthogonal = VectorMath.l2Normalize(floatArrayOf(0f, 0f, 5f))

        assertEquals(1.0f, VectorMath.cosineSimilarity(a, b), 1e-5f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(a, orthogonal), 1e-5f)
    }

    @Test
    fun roundTripsFloatArrayThroughLittleEndianBytes() {
        val original = floatArrayOf(-0.25f, 0.5f, 0.875f, 1.0f)
        val encoded = VectorMath.toByteArray(original)
        val decoded = VectorMath.toFloatArray(encoded)

        assertArrayEquals(original, decoded, 1e-6f)
    }
}
