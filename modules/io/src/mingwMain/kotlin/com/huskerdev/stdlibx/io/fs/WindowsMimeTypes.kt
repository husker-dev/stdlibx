@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io.fs

import kotlinx.cinterop.*
import platform.windows.*

internal fun windowsMimeType(extension: String): String {
    if (extension.isEmpty()) return ""
    val key = ".${extension.lowercase()}"
    val registered = memScoped {
        val size = alloc<UIntVar>()
        size.value = 0u
        if (RegGetValueW(
                HKEY_CLASSES_ROOT,
                key,
                "Content Type",
                RRF_RT_REG_SZ.toUInt(),
                null,
                null,
                size.ptr
        ) != ERROR_SUCCESS)
            return@memScoped null
        if (size.value !in 2u..65536u) return@memScoped null

        val buffer = allocArray<UShortVar>((size.value / 2u + 1u).toInt())
        if (RegGetValueW(
                HKEY_CLASSES_ROOT,
                key,
                "Content Type",
                RRF_RT_REG_SZ.toUInt(),
                null,
                buffer,
                size.ptr
        ) != ERROR_SUCCESS)
            return@memScoped null
        buffer.toKStringFromUtf16().takeIf { it.contains('/') && it.isNotBlank() }
    }
    return registered ?: fallbackMimeType(extension)
}
