package com.huskerdev.stdlibx.io

actual open class IOException actual constructor(message: String?, cause: Throwable?) : Exception(message, cause) {
    actual constructor(message: String?) : this(message, null)
}

actual open class SecurityException actual constructor(message: String?, cause: Throwable?) :
    RuntimeException(message, cause) {
    actual constructor(message: String?) : this(message, null)
}
