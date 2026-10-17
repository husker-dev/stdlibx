@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io

import kotlinx.cinterop.*
import platform.posix.*

internal actual fun writeStandardStreamBytes(fd: Int, bytes: CPointer<ByteVar>, length: Int): Int = try {
    memScoped {
        val info = alloc<stat>()
        if (fstat(fd, info.ptr) != 0) processUnixError("fstat standard stream")
        // Applications can redirect the shared descriptor after initialization.
        val kind = info.st_mode.toInt() and S_IFMT
        if (kind == S_IFIFO || kind == S_IFSOCK) configureProcessStdin(fd)
    }
    writeProcessPipe(fd, bytes, length)
} catch (error: ProcessException) {
    throw IOException(error.message, error)
}
