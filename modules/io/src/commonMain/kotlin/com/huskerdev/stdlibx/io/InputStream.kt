package com.huskerdev.stdlibx.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * A byte input stream with suspending operations. I/O failures throw [IOException].
 * Accessing a closed stream throws [IllegalStateException], except for shared standard input.
 */
interface AsyncInputStream: AsyncCloseable {
    /** Returns whether another byte can be read; may wait for input. */
    suspend fun hasNext(): Boolean
    /** Reads one byte, throwing [NoSuchElementException] at the end of the stream. */
    suspend fun read(): Byte
    /** Estimates how many bytes can be read without waiting; zero does not imply end of input. */
    suspend fun availableBytes(): Long

    /**
     * Reads up to [length] bytes into [buffer] at [offset], returning zero at end of input.
     * @throws IndexOutOfBoundsException if the requested range is outside [buffer].
     */
    suspend fun read(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset
    ): Int = defaultReadImpl(buffer, offset, length, { hasNext() }, { read() })

    /** Reads up to [count] bytes. Zero returns an empty array; negative counts throw [IllegalArgumentException]. */
    suspend fun read(count: Int): ByteArray = defaultReadImpl(
        count,
        { hasNext() },
        { buffer, offset, length -> read(buffer, offset, length) }
    )

    /** Reads the remaining input into memory without closing the stream. */
    suspend fun readAllBytes() = read(Int.MAX_VALUE)

    /** Skips up to [count] bytes, stopping at EOF. Zero is a no-op; negative counts throw [IllegalArgumentException]. */
    suspend fun skip(count: Long): Long =
        defaultSkipImpl(count) { buffer, offset, length -> read(buffer, offset, length) }

    /** Returns a synchronous view of this stream, or null if none is available. */
    fun asSync(): SyncInputStream?
}

/**
 * A blocking byte input stream. I/O failures throw [IOException]. Accessing a closed stream throws
 * [IllegalStateException], except for shared standard input. Close it when no longer needed.
 */
interface SyncInputStream: AutoCloseable {
    /** Returns whether another byte can be read; may block waiting for input. */
    fun hasNext(): Boolean
    /** Reads one byte, throwing [NoSuchElementException] at the end of the stream. */
    fun read(): Byte
    /** Estimates how many bytes can be read without blocking; zero does not imply end of input. */
    fun availableBytes(): Long

    /**
     * Reads up to [length] bytes into [buffer] at [offset], returning zero at end of input.
     * @throws IndexOutOfBoundsException if the requested range is outside [buffer].
     */
    fun read(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset
    ): Int = defaultReadImpl(buffer, offset, length, ::hasNext, ::read)

    /** Reads up to [count] bytes. Zero returns an empty array; negative counts throw [IllegalArgumentException]. */
    fun read(count: Int): ByteArray = defaultReadImpl(count, ::hasNext, ::read)

    /** Reads the remaining input into memory without closing the stream. */
    fun readAllBytes() = read(Int.MAX_VALUE)

    /** Skips up to [count] bytes, stopping at EOF. Zero is a no-op; negative counts throw [IllegalArgumentException]. */
    fun skip(count: Long): Long = defaultSkipImpl(count, ::read)

    /** Returns an asynchronous view sharing this stream's position and lifetime. */
    fun asAsync(): AsyncInputStream
}

/**
 * Adapts [wrapped] to suspending operations on [dispatcher]. Closing the adapter closes [wrapped].
 * Cancellation does not necessarily interrupt an ongoing read.
 */
class WrappedAsyncInputStream(
    val wrapped: SyncInputStream,
    val dispatcher: CoroutineDispatcher = asyncDispatcher
): AsyncInputStream {
    override fun asSync(): SyncInputStream = wrapped

    override suspend fun hasNext(): Boolean = withContext(dispatcher) {
        wrapped.hasNext()
    }

    override suspend fun read(): Byte = withContext(dispatcher) {
        wrapped.read()
    }

    override suspend fun availableBytes(): Long = withContext(dispatcher) {
        wrapped.availableBytes()
    }

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int = withContext(dispatcher) {
        wrapped.read(buffer, offset, length)
    }

    override suspend fun read(
        count: Int
    ): ByteArray = withContext(dispatcher) {
        wrapped.read(count)
    }

    override suspend fun readAllBytes(): ByteArray = withContext(dispatcher) {
        wrapped.readAllBytes()
    }

    override suspend fun skip(count: Long): Long = withContext(dispatcher) {
        wrapped.skip(count)
    }

    override suspend fun close() = withContext(dispatcher) {
        wrapped.close()
    }
}


private inline fun defaultReadImpl(
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size - offset,
    hasNext: () -> Boolean,
    read: () -> Byte
): Int {
    checkBounds(buffer, offset, length)

    for(i in 0 until length) {
        if(!hasNext()) return i
        buffer[offset + i] = read()
    }
    return length
}

private inline fun defaultReadImpl(
    count: Int,
    hasNext: () -> Boolean,
    read: (buffer: ByteArray, offset: Int, length: Int) -> Int
): ByteArray {
    require(count >= 0)
    if(count == 0) return byteArrayOf()

    val chunks = arrayListOf<ByteArray>()
    var remaining = count
    var total = 0

    // Read chunks
    while (remaining > 0 && hasNext()) {
        val buffer = ByteArray(min(remaining, 16384))
        val bytesRead = read(buffer, 0, buffer.size)
        total += bytesRead
        remaining -= bytesRead
        chunks += buffer.copyOf(bytesRead)
    }

    // Collect chunks
    val result = ByteArray(total)
    var offset = 0
    remaining = total
    chunks.forEach {
        val count = min(it.size, remaining)
        it.copyInto(result, destinationOffset = offset, endIndex = count)
        offset += count
        remaining -= count
    }
    return result
}

private inline fun defaultSkipImpl(
    count: Long,
    read: (buffer: ByteArray, offset: Int, length: Int) -> Int
): Long {
    require(count >= 0)
    if(count == 0L)
        return 0

    var remaining = count
    val size = min(2048, remaining)
    val skipBuffer = ByteArray(size.toInt())

    while (remaining > 0) {
        val bytesRead = read(skipBuffer, 0, min(size, remaining).toInt())
        if (bytesRead == 0) break
        check(bytesRead in 1..min(size, remaining).toInt()) { "Invalid read count: $bytesRead" }
        remaining -= bytesRead
    }
    return count - remaining
}
