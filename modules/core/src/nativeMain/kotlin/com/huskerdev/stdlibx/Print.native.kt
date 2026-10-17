@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import platform.posix.fprintf
import platform.posix.stderr

private val END_LINE = if(OS.current == OS.WINDOWS) "\r\n" else "\n"

actual fun printError(message: Any?) = memScoped {
    fprintf(stderr, "%s", message.toString().cstr.ptr)
    Unit
}

actual fun printlnError(message: Any?) = memScoped {
    fprintf(stderr, "%s$END_LINE", message.toString().cstr.ptr)
    Unit
}

actual fun printlnError() {
    fprintf(stderr, END_LINE)
}