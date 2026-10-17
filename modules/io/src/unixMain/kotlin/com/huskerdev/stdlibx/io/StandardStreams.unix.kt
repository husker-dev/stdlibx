@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io

import kotlinx.cinterop.*
import platform.posix.*

private fun standardStreamError(): Nothing =
    throw IOException("Standard stream IO: ${strerror(errno)?.toKString() ?: errno.toString()}")

actual val stdin: SyncInputStream = object : StandardInputStream() {
    override fun readBytes(buffer: ByteArray, offset: Int, length: Int): Int = buffer.usePinned {
        var count: Long
        do {
            count = read(STDIN_FILENO, it.addressOf(offset), length.convert())
        } while (count < 0 && errno == EINTR)

        if (count < 0) standardStreamError()
        count.toInt()
    }
}

actual val stdout: SyncOutputStream = UnixStandardOutput(STDOUT_FILENO)
actual val stderr: SyncOutputStream = UnixStandardOutput(STDERR_FILENO)

private class UnixStandardOutput(private val fd: Int) : StandardOutputStream() {
    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int): Int = bytes.usePinned {
        writeStandardStreamBytes(fd, it.addressOf(offset), length)
    }
}

internal expect fun writeStandardStreamBytes(fd: Int, bytes: CPointer<ByteVar>, length: Int): Int
