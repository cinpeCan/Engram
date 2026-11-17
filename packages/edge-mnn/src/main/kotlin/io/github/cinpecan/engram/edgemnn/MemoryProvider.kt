package io.github.cinpecan.engram.edgemnn

import android.os.Debug
import android.system.Os
import android.system.OsConstants
import java.io.File

fun interface MemoryProvider {
    fun residentMemoryBytes(): Long
}

class AndroidMemoryProvider : MemoryProvider {
    override fun residentMemoryBytes(): Long {
        val rssBytes = runCatching {
            val statm = File("/proc/self/statm").readText().trim().split(Regex("\\s+"))
            val rssPages = statm.getOrNull(1)?.toLongOrNull() ?: 0L
            val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).coerceAtLeast(4096L)
            rssPages * pageSize
        }.getOrDefault(0L)

        if (rssBytes > 0L) return rssBytes
        return runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
    }
}
