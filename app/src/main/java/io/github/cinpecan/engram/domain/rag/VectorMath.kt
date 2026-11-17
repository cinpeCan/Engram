package io.github.cinpecan.engram.domain.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object VectorMath {

    fun l2Normalize(vector: FloatArray): FloatArray {
        if (vector.isEmpty()) return vector
        var sumSq = 0.0
        for (v in vector) {
            sumSq += v.toDouble() * v.toDouble()
        }
        if (sumSq <= 1e-12) return FloatArray(vector.size)
        val inv = (1.0 / sqrt(sumSq)).toFloat()
        return FloatArray(vector.size) { i -> vector[i] * inv }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val va = a[i].toDouble()
            val vb = b[i].toDouble()
            dot += va * vb
            normA += va * va
            normB += vb * vb
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom <= 1e-12) return 0f
        return (dot / denom).toFloat()
    }

    fun toByteArray(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) {
            buffer.putFloat(v)
        }
        return buffer.array()
    }

    fun toFloatArray(bytes: ByteArray): FloatArray {
        if (bytes.isEmpty() || bytes.size % Float.SIZE_BYTES != 0) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = bytes.size / Float.SIZE_BYTES
        return FloatArray(count) { buffer.float }
    }
}
