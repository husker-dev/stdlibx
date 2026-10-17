package com.huskerdev.stdlibx

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.NSProcessInfo
import platform.posix.EINTR
import platform.posix.ERANGE
import platform.posix.errno
import platform.posix.getcwd
import kotlin.collections.iterator

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
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

actual fun env(): Map<String, String> = buildMap {
    for ((name, value) in NSProcessInfo.processInfo.environment) {
        if (name is String && value is String) put(name, value)
    }
}
