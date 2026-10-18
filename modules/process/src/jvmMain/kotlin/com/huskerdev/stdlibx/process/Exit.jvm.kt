package com.huskerdev.stdlibx.process

import kotlin.system.exitProcess

actual fun exitProcess(status: Int): Nothing = exitProcess(status)