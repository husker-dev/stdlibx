@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.process

import com.huskerdev.stdlibx.process.interop.pipe2
import kotlinx.cinterop.*
import platform.posix.*

internal actual fun openProcessPipe(fds: CPointer<IntVar>): Int = pipe2(fds, O_CLOEXEC)
internal actual fun configureProcessStdin(fd: Int) = Unit

/** Block/consume only this write's SIGPIPE without changing the application's signal handler. */
internal actual fun writeProcessPipe(fd: Int, bytes: CPointer<ByteVar>, length: Int): Int = memScoped {
    val blocked = alloc<sigset_t>()
    val original = alloc<sigset_t>()
    val pending = alloc<sigset_t>()

    sigemptyset(blocked.ptr)
    sigaddset(blocked.ptr, SIGPIPE)
    val maskError = pthread_sigmask(SIG_BLOCK, blocked.ptr, original.ptr)
    if (maskError != 0)
        processUnixError("pthread_sigmask", maskError)

    try {
        if (sigpending(pending.ptr) != 0)
            processUnixError("sigpending")
        val hadPending = sigismember(pending.ptr, SIGPIPE) == 1

        var written: Long
        do {
            written = write(fd, bytes, length.convert())
        } while (written < 0 && errno == EINTR)

        val error = if (written < 0) errno else 0
        if (error == EPIPE && !hadPending &&
            sigpending(pending.ptr) == 0 &&
            sigismember(pending.ptr, SIGPIPE) == 1
        ) {
            val signal = alloc<IntVar>()
            val waitError = sigwait(blocked.ptr, signal.ptr)
            if (waitError != 0) processUnixError("sigwait", waitError)
        }
        if (written < 0)
            processUnixError("write process pipe", error)
        written.toInt()
    } finally {
        pthread_sigmask(SIG_SETMASK, original.ptr, null)
    }
}
