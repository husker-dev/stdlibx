package com.huskerdev.stdlibx.io

/**
 * A readable resource. I/O failures throw [IOException]; policy denials throw [SecurityException].
 * Unsupported operations throw [UnsupportedOperationException].
 */
interface Resource {
    /** Returns a synchronous view, or null if synchronous access is unavailable. */
    fun asSync(): SyncResource?

    /** Returns an asynchronous view of this resource. */
    fun asAsync(): AsyncResource
}

/** A resource that can be read through blocking operations. */
interface SyncResource: Resource {
    /** Returns the resource's MIME type. */
    fun mimeType(): String

    /** Opens a new input stream; the caller is responsible for closing it. */
    fun newInputStream(): SyncInputStream
}

/** A resource that can be read through suspending operations. */
interface AsyncResource: Resource {
    /** Returns the resource's MIME type. */
    suspend fun mimeType(): String
    
    /** Opens a new input stream; the caller is responsible for closing it. */
    suspend fun newInputStream(): AsyncInputStream
}

// Writable

/** A resource that supports both reading and writing. */
interface WritableResource: Resource {
    override fun asSync(): SyncWritableResource?
    override fun asAsync(): AsyncWritableResource
}

/** A resource that supports blocking reads and writes. */
interface SyncWritableResource: WritableResource, SyncResource {
    /** Opens a new output stream; the caller is responsible for closing it. */
    fun newOutputStream(): SyncOutputStream
}

/** A resource that supports suspending reads and writes. */
interface AsyncWritableResource: WritableResource, AsyncResource {
    /** Opens a new output stream; the caller is responsible for closing it. */
    suspend fun newOutputStream(): AsyncOutputStream
}
