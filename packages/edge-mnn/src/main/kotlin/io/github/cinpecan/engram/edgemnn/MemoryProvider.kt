package io.github.cinpecan.engram.edgemnn

import android.os.Debug

fun interface MemoryProvider {
    fun nativeHeapAllocatedBytes(): Long
}

class AndroidMemoryProvider : MemoryProvider {
    override fun nativeHeapAllocatedBytes(): Long = Debug.getNativeHeapAllocatedSize()
}
