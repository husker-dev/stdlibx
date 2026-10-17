@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.io

actual open class SecurityException actual constructor(message: String?, cause: Throwable?) :
    RuntimeException(message, cause) {
    actual constructor(message: String?) : this(message, null)
}

actual open class IOException actual constructor(message: String?, cause: Throwable?) : Exception(message, cause) {
    actual constructor(message: String?) : this(message, null)
}

internal inline fun <T> ioCall(block: () -> T): T = try {
    block()
} catch (error: IOException) {
    throw error
} catch (error: RuntimeException) {
    throw error
} catch (error: JsException) {
    if (isSecurityPolicyError(error.thrownValue)) throw SecurityException(error.message, error)
    throw IOException(error.message ?: "Input/output failed", error)
}

private fun isSecurityPolicyError(error: JsAny?): Boolean =
    js("error != null && (error.name === 'SecurityError' || error.code === 'ERR_ACCESS_DENIED')")
