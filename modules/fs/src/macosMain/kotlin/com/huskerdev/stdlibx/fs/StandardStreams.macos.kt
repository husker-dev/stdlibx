@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.io.IOException

import kotlinx.cinterop.*
import platform.posix.*

private fun streamUnixError(operation: String): Nothing =
    throw IOException("$operation: ${strerror(errno)?.toKString() ?: errno.toString()}")

internal actual fun writeStandardStreamBytes(fd: Int, bytes: CPointer<ByteVar>, length: Int): Int {
    memScoped {
        val info = alloc<stat>()
        if (fstat(fd, info.ptr) != 0) streamUnixError("fstat standard stream")
        // Applications can redirect the shared descriptor after initialization.
        val kind = info.st_mode.toInt() and S_IFMT
        if ((kind == S_IFIFO || kind == S_IFSOCK) && fcntl(fd, F_SETNOSIGPIPE, 1) < 0)
            streamUnixError("fcntl(F_SETNOSIGPIPE)")
    }
    var written: Long
    do {
        written = write(fd, bytes, length.convert())
    } while (written < 0 && errno == EINTR)
    if (written < 0) streamUnixError("write")
    return written.toInt()
}
