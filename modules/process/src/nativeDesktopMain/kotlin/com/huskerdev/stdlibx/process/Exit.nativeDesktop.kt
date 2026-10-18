package com.huskerdev.stdlibx.process

import platform.posix.exit

actual fun exitProcess(status: Int): Nothing {
    exit(status)
    throw Exception()
}