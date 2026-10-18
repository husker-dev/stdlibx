@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx

import kotlin.js.*

private val isNodeJs: Boolean =
    js("typeof window === 'undefined' && typeof process !== 'undefined'")

private fun environmentEntries(): JsArray<EnvironmentEntry> = js("""
    typeof process !== 'undefined' && process.env
        ? Object.keys(process.env).map(function(name) { return {name: name, value: process.env[name]}; })
            .filter(function(entry) { return typeof entry.value === 'string'; })
        : []
""")

private external interface EnvironmentEntry : JsAny {
    val name: String
    val value: String
}

internal actual val environmentKind =
    if(isNodeJs) EnvironmentKind.NODEJS
    else EnvironmentKind.WEB

internal actual val isWebWorker: Boolean =
    js("typeof window === 'undefined' && typeof process === 'undefined'")

actual fun getenv(name: String): String? =
    js("typeof process !== 'undefined' ? process.env[name] : null")

actual fun env(): Map<String, String> {
    if (!isNodeJs) return emptyMap()
    val entries = environmentEntries()
    return buildMap {
        for (index in 0 until entries.length) {
            val entry = checkNotNull(entries[index])
            put(entry.name, entry.value)
        }
    }
}

actual val workingDir: String =
    js("typeof process !== 'undefined' ? process.cwd() : \"/\"")

actual val systemLineSeparator: String
    get() = if(isNodeJs && OS.isWindows) "\r\n" else "\n"

actual val systemPathSeparator: String
    get() = if(isNodeJs && OS.isWindows) ";" else ":"

actual val systemSeparator: String
    get() = if(isNodeJs && OS.isWindows) "\\" else "/"

actual fun addShutdownHook(action: () -> Unit): Unit =
    js("if(typeof process !== 'undefined') process.on(\"exit\", action)")

actual val isShutdownHookSupported: Boolean = isNodeJs
