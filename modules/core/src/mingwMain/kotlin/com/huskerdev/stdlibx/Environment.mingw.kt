@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx

import kotlinx.cinterop.*
import platform.windows.FreeEnvironmentStringsW
import platform.windows.GetCurrentDirectoryW
import platform.windows.GetEnvironmentStringsW
import platform.windows.GetLastError

actual val workingDir: String
    get() = memScoped {
        var capacity = 256
        while(true) {
            val buffer = allocArray<UShortVar>(capacity)
            val count = GetCurrentDirectoryW(capacity.toUInt(), buffer)

            if (count == 0u)
                throw Exception()
            if (count >= Int.MAX_VALUE.toUInt())
                throw Exception("Windows path is too long")
            if (count < capacity.toUInt())
                return@memScoped buffer.toKStringFromUtf16()

            capacity = count.toInt() + 1
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

actual fun env(): Map<String, String> {
    val block = GetEnvironmentStringsW() ?: error("GetEnvironmentStringsW failed: ${GetLastError()}")
    try {
        return buildMap {
            var offset = 0
            while (block[offset] != 0.toUShort()) {
                val entry = (block + offset)!!.toKStringFromUtf16()
                // Windows includes special entries such as '=C:=C:\\directory'. Preserve them.
                val separator = entry.indexOf('=', startIndex = 1)
                if (separator > 0) put(entry.substring(0, separator), entry.substring(separator + 1))
                offset += entry.length + 1
            }
        }
    } finally { FreeEnvironmentStringsW(block) }
}

actual val systemLineSeparator: String = "\r\n"
actual val systemPathSeparator: String = ";"
actual val systemSeparator: String = "\\"