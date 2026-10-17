package com.huskerdev.stdlibx.io

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A resource that supports suspending cleanup. */
interface AsyncCloseable {
    /** Releases the resource and any resources it owns. */
    suspend fun close()
}

/** Closes the resource even when the block fails or its coroutine is cancelled. */
suspend inline fun <T : AsyncCloseable, R> T.use(block: (T) -> R): R {
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        withContext(NonCancellable) {
            try {
                close()
            } catch (error: Throwable) {
                if (failure == null) throw error
                if (failure !== error) failure.addSuppressed(error)
            }
        }
    }
}
