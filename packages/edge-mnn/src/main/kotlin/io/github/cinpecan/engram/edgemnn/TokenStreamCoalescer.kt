package io.github.cinpecan.engram.edgemnn

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Batches rapid token emissions into fixed [windowMs] tumbling windows so UI consumers
 * avoid per-token Compose recomposition jank while preserving instant tail flush on completion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenStreamCoalescer(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    init {
        require(windowMs > 0L) { "windowMs must be positive, was $windowMs" }
    }

    fun coalesce(upstream: Flow<String>): Flow<String> = channelFlow {
        val rawChannel = produce(capacity = Channel.UNLIMITED) {
            upstream.collect { token ->
                if (token.isNotEmpty()) {
                    send(token)
                }
            }
        }
        val flushSignal = Channel<Unit>(capacity = Channel.CONFLATED)
        val buffer = StringBuilder()
        var timerJob: Job? = null
        var upstreamClosed = false

        while (!upstreamClosed) {
            select<Unit> {
                rawChannel.onReceiveCatching { result ->
                    if (result.isClosed) {
                        upstreamClosed = true
                    } else {
                        val delta = result.getOrNull()
                        if (!delta.isNullOrEmpty()) {
                            val wasEmpty = buffer.isEmpty()
                            buffer.append(delta)
                            if (wasEmpty && timerJob == null) {
                                timerJob = launch {
                                    delay(windowMs)
                                    flushSignal.trySend(Unit)
                                }
                            }
                        }
                    }
                }
                flushSignal.onReceive {
                    timerJob = null
                    if (buffer.isNotEmpty()) {
                        send(buffer.toString())
                        buffer.clear()
                    }
                }
            }
        }

        timerJob?.cancel()
        if (buffer.isNotEmpty()) {
            send(buffer.toString())
            buffer.clear()
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 32L
    }
}
