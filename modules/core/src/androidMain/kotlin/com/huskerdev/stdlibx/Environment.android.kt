package com.huskerdev.stdlibx

internal actual val environmentKind = EnvironmentKind.JVM
internal actual val isWebWorker: Boolean = false
internal actual val isJS: Boolean = false

actual fun getenv(name: String): String? = System.getenv(name)

actual fun env(): Map<String, String> = System.getenv().toMap()

actual val workingDir: String
    get() = ContextProvider.applicationContext?.filesDir?.absolutePath ?: ""

actual val isShutdownHookSupported: Boolean = false

actual fun addShutdownHook(action: () -> Unit) = Unit
