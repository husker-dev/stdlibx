@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io

import kotlinx.cinterop.*
import platform.posix.*

internal actual fun openProcessPipe(fds: CPointer<IntVar>): Int = pipe(fds)

internal actual fun configureProcessStdin(fd: Int) {
    // Darwin may deliver pipe SIGPIPE process-wide; a thread-local mask alone is insufficient.
    if (fcntl(fd, F_SETNOSIGPIPE, 1) < 0)
        processUnixError("fcntl(F_SETNOSIGPIPE)")
}

internal actual fun writeProcessPipe(fd: Int, bytes: CPointer<ByteVar>, length: Int): Int {
    var written: Long
    do {
        written = write(fd, bytes, length.convert())
    } while (written < 0 && errno == EINTR)

    if (written < 0)
        processUnixError("write process pipe")
    return written.toInt()
}
