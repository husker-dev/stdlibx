package com.huskerdev.stdlibx

actual val isShutdownHookSupported: Boolean = false

actual fun addShutdownHook(action: () -> Unit) = Unit
