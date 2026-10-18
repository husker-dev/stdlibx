package com.huskerdev.stdlibx

import java.io.File

internal actual val environmentKind = EnvironmentKind.JVM
internal actual val isWebWorker: Boolean = false
internal actual val isJS: Boolean = false

actual fun getenv(name: String): String? = System.getenv(name)

actual fun env(): Map<String, String> = System.getenv().toMap()

actual val workingDir: String = System.getProperty("user.dir")

actual val systemLineSeparator: String = System.lineSeparator()
actual val systemPathSeparator: String = File.pathSeparator
actual val systemSeparator: String = File.separator

actual val isShutdownHookSupported: Boolean = true

actual fun addShutdownHook(action: () -> Unit) {
    Runtime.getRuntime().addShutdownHook(object: Thread() {
        override fun run() {
            action()
        }
    })
}
