package io.github.cinpecan.engram.edgemnn

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TokenStreamCoalescerTest {

    @Test
    fun batchesRapidTokensWithin32msWindowWithoutStarving() = runTest {
        val coalescer = TokenStreamCoalescer(windowMs = 32L)
        val upstream = flow {
            emit("A")
            delay(10)
            emit("B")
            delay(10)
            emit("C")
            // Crosses the 32ms tumbling boundary from t=0
            delay(20)
            emit("D")
            delay(10)
            emit("E")
        }

        val emissions = coalescer.coalesce(upstream).toList()

        assertEquals(2, emissions.size)
        assertEquals("ABC", emissions[0])
        assertEquals("DE", emissions[1])
    }

    @Test
    fun flushesRemainingTokensImmediatelyOnCompletion() = runTest {
        val coalescer = TokenStreamCoalescer(windowMs = 32L)
        val upstream = flow {
            emit("on-")
            delay(5)
            emit("device ")
            delay(5)
            emit("inference")
        }

        val emissions = coalescer.coalesce(upstream).toList()
        assertEquals(1, emissions.size)
        assertEquals("on-device inference", emissions.single())
        assertTrue(testScheduler.currentTime < 32L)
    }
}
