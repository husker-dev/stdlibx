package com.huskerdev.stdlibx

enum class EnvironmentKind {
    JVM,
    NATIVE,
    WEB,
    NODEJS;

    companion object {
        val current: EnvironmentKind = environmentKind

        val isWebWorker: Boolean = com.huskerdev.stdlibx.isWebWorker
        val isJS: Boolean = com.huskerdev.stdlibx.isJS
    }
}

internal expect val environmentKind: EnvironmentKind
internal expect val isWebWorker: Boolean
internal expect val isJS: Boolean

expect fun getenv(name: String): String?
expect fun env(): Map<String, String>

expect val workingDir: String

expect val systemLineSeparator: String
expect val systemPathSeparator: String
expect val systemSeparator: String

expect val isShutdownHookSupported: Boolean

expect fun addShutdownHook(action: () -> Unit)