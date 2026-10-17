@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io

import kotlinx.cinterop.*

internal actual fun writeStandardStreamBytes(fd: Int, bytes: CPointer<ByteVar>, length: Int): Int = try {
    // Reuse thread-local SIGPIPE protection without exposing process-specific errors.
    writeProcessPipe(fd, bytes, length)
} catch (error: ProcessException) {
    throw IOException(error.message, error)
}
