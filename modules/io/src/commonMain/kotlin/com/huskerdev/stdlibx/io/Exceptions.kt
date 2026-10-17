package com.huskerdev.stdlibx.io

/**
 * An input/output failure. File operations use [com.huskerdev.stdlibx.io.fs.FileSystemException]
 * and its subclasses. Invalid arguments, closed resources and unsupported operations use
 * [IllegalArgumentException], [IllegalStateException] and [UnsupportedOperationException].
 * Security-policy denials use [SecurityException], not an I/O exception.
 */
expect open class IOException : Exception {
    constructor(message: String?)
    constructor(message: String?, cause: Throwable?)
}

/**
 * A security policy forbids the operation. Unlike
 * [com.huskerdev.stdlibx.io.fs.AccessDeniedException], this is not a file permission failure and
 * does not belong to the [IOException] hierarchy.
 */
expect open class SecurityException : RuntimeException {
    constructor(message: String?)
    constructor(message: String?, cause: Throwable?)
}