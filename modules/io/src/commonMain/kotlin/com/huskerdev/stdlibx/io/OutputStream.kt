package com.huskerdev.stdlibx.io

import kotlinx.coroutines.withContext

/**
 * A byte output stream with suspending operations. I/O failures throw [IOException]; writing or
 * flushing after close throws [IllegalStateException], except for shared standard output/error.
 */
interface AsyncOutputStream: AsyncCloseable {
    /** Writes one byte. */
    suspend fun write(byte: Byte)
    /**
     * Writes [length] bytes from [bytes], starting at [offset].
     * @throws IndexOutOfBoundsException if the requested range is outside [bytes].
     */
    suspend fun write(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset
    )
    /** Sends any buffered output to its destination without closing the stream. */
    suspend fun flush()

    /** Returns a synchronous view of this stream, or null if none is available. */
    fun asSync(): SyncOutputStream?
}

/**
 * A blocking byte output stream. I/O failures throw [IOException]; writing or flushing after
 * close throws [IllegalStateException], except for shared standard output/error.
 */
interface SyncOutputStream: AutoCloseable {
    /** Writes one byte. */
    fun write(byte: Byte)
    /**
     * Writes [length] bytes from [bytes], starting at [offset].
     * @throws IndexOutOfBoundsException if the requested range is outside [bytes].
     */
    fun write(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset
    )
    /** Sends any buffered output to its destination without closing the stream. */
    fun flush()

    /** Returns an asynchronous view sharing this stream's destination and lifetime. */
    fun asAsync(): AsyncOutputStream
}

/**
 * Adapts a synchronous stream to suspending operations. Closing the adapter closes the source.
 * Cancellation does not necessarily interrupt an ongoing write.
 */
class WrappedAsyncOutputStream(
    private val wrapped: SyncOutputStream
): AsyncOutputStream {

    override suspend fun write(byte: Byte) = withContext(asyncDispatcher) {
        wrapped.write(byte)
    }

    override suspend fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int
    ) = withContext(asyncDispatcher) {
        wrapped.write(bytes, offset, length)
    }

    override suspend fun flush() = withContext(asyncDispatcher) {
        wrapped.flush()
    }

    override suspend fun close() = withContext(asyncDispatcher) {
        wrapped.close()
    }

    override fun asSync(): SyncOutputStream = wrapped
}
