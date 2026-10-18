@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.io.IOException

import kotlinx.cinterop.*
import platform.posix.*

private fun streamUnixError(operation: String, code: Int = errno): Nothing =
    throw IOException("$operation: ${strerror(code)?.toKString() ?: "Unix error $code"}")

/** Suppresses only this write's SIGPIPE without changing the application's signal handler. */
internal actual fun writeStandardStreamBytes(fd: Int, bytes: CPointer<ByteVar>, length: Int): Int = memScoped {
    val blocked = alloc<sigset_t>()
    val original = alloc<sigset_t>()
    val pending = alloc<sigset_t>()
    sigemptyset(blocked.ptr)
    sigaddset(blocked.ptr, SIGPIPE)
    val maskError = pthread_sigmask(SIG_BLOCK, blocked.ptr, original.ptr)
    if (maskError != 0) streamUnixError("pthread_sigmask", maskError)
    try {
        if (sigpending(pending.ptr) != 0) streamUnixError("sigpending")
        val hadPending = sigismember(pending.ptr, SIGPIPE) == 1
        var written: Long
        do {
            written = write(fd, bytes, length.convert())
        } while (written < 0 && errno == EINTR)
        val error = if (written < 0) errno else 0
        if (error == EPIPE && !hadPending &&
            sigpending(pending.ptr) == 0 && sigismember(pending.ptr, SIGPIPE) == 1
        ) {
            val signal = alloc<IntVar>()
            val waitError = sigwait(blocked.ptr, signal.ptr)
            if (waitError != 0) streamUnixError("sigwait", waitError)
        }
        if (written < 0) streamUnixError("write", error)
        written.toInt()
    } finally {
        pthread_sigmask(SIG_SETMASK, original.ptr, null)
    }
}
