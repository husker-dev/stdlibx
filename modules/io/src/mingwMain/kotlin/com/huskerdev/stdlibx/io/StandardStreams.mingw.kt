@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io

import kotlinx.cinterop.*
import platform.windows.*

private fun standardStreamError(): Nothing = throw IOException("Standard stream IO: Windows error ${GetLastError()}")

actual val stdin: SyncInputStream = object : StandardInputStream() {
    override fun readBytes(buffer: ByteArray, offset: Int, length: Int): Int = memScoped {
        val count = alloc<UIntVar>()
        if (buffer.usePinned {
            ReadFile(
                GetStdHandle(STD_INPUT_HANDLE),
                it.addressOf(offset),
                length.toUInt(),
                count.ptr,
                null
            )
        } == 0) {
            val code = GetLastError()
            if (code == ERROR_BROKEN_PIPE.toUInt() || code == ERROR_HANDLE_EOF.toUInt())
                return@memScoped 0
            standardStreamError()
        }
        count.value.toInt()
    }
}

actual val stdout: SyncOutputStream = WindowsStandardOutput(STD_OUTPUT_HANDLE)
actual val stderr: SyncOutputStream = WindowsStandardOutput(STD_ERROR_HANDLE)

private class WindowsStandardOutput(private val id: UInt) : StandardOutputStream() {
    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int): Int = memScoped {
        val count = alloc<UIntVar>()
        if (bytes.usePinned {
            WriteFile(
                GetStdHandle(id),
                it.addressOf(offset),
                length.toUInt(),
                count.ptr,
                null
            )
        } == 0) standardStreamError()
        count.value.toInt()
    }
}
