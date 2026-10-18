@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx

import kotlinx.cinterop.*
import platform.posix.EINTR
import platform.posix.ERANGE
import platform.posix.errno
import platform.posix.getcwd

actual val workingDir: String
    get() = memScoped {
        var size = 256
        while (true) {
            val buffer = ByteArray(size)
            val result = buffer.usePinned { getcwd(it.addressOf(0), size.convert()) }
            if (result != null) return buffer.decodeToString(endIndex = buffer.indexOf(0))
            val code = errno
            if (code == EINTR) continue
            if (code != ERANGE) throw Exception("$code")
            if (size > Int.MAX_VALUE / 2) throw Exception("Working directory path is too long")
            size *= 2
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

actual val systemLineSeparator: String = "\n"
actual val systemPathSeparator: String = ":"
actual val systemSeparator: String = "/"

actual fun env(): Map<String, String> = buildMap {
    val entries = platform.posix.__environ
    var index = 0
    while (true) {
        val entry = entries?.get(index++)?.toKString() ?: break
        val separator = entry.indexOf('=')
        if (separator > 0) put(entry.substring(0, separator), entry.substring(separator + 1))
    }
}
