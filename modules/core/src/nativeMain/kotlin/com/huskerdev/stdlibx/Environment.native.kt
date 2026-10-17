@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual val environmentKind = EnvironmentKind.NATIVE
internal actual val isWebWorker: Boolean = false
internal actual val isJS: Boolean = false

actual fun getenv(name: String): String? = getenv(name)?.toKString()
