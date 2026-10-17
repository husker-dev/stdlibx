package com.huskerdev.stdlibx.io

/** Buffers writes to [source]; closing sends pending output and closes [source]. Do not use its views concurrently. */
class BufferedSyncOutputStream internal constructor(
    private val source: SyncOutputStream,
    private val common: CommonBufferedOutput
): SyncOutputStream {

    /** Creates a buffered writer with a positive [bufferSize]. */
    constructor(
        source: SyncOutputStream,
        bufferSize: Int = DEFAULT_STREAM_BUFFER_SIZE
    ): this(source, CommonBufferedOutput(bufferSize))

    private val async: BufferedAsyncOutputStream by lazy { BufferedAsyncOutputStream(source.asAsync(), common) }

    override fun write(byte: Byte) =
        common.writeByte(byte, source::write)

    override fun write(bytes: ByteArray, offset: Int, length: Int) =
        common.write(bytes, offset, length, source::write)

    override fun flush() =
        common.flush(source::write, source::flush)

    override fun close() =
        common.close(source::write, source::close)

    override fun asAsync(): BufferedAsyncOutputStream = async
}

/**
 * Buffers suspending writes to [source]; closing sends pending output and closes [source].
 * Its sync and async views must not be used concurrently.
 */
class BufferedAsyncOutputStream internal constructor(
    private val source: AsyncOutputStream,
    private val common: CommonBufferedOutput
): AsyncOutputStream {

    /** Creates a buffered writer with a positive [bufferSize]. */
    constructor(
        source: AsyncOutputStream,
        bufferSize: Int = DEFAULT_STREAM_BUFFER_SIZE
    ): this(source, CommonBufferedOutput(bufferSize))

    private val sync: BufferedSyncOutputStream? by lazy {
        source.asSync()?.let { BufferedSyncOutputStream(it, common) }
    }

    override suspend fun write(byte: Byte) = common.writeByte(byte) { bytes, offset, length ->
        source.write(bytes, offset, length)
    }

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) =
        common.write(bytes, offset, length) { buffer, start, count -> source.write(buffer, start, count) }

    override suspend fun flush() = common.flush(
        { bytes, offset, length -> source.write(bytes, offset, length) },
        { source.flush() }
    )

    override suspend fun close() = common.close(
        { bytes, offset, length -> source.write(bytes, offset, length) },
        { source.close() }
    )

    override fun asSync(): BufferedSyncOutputStream? = sync
}

internal class CommonBufferedOutput(bufferSize: Int) {
    init {
        require(bufferSize > 0) { "Buffer size must be positive" }
    }

    val buffer = ByteArray(bufferSize)
    var count = 0
    var closed = false

    fun checkOpen() = check(!closed) { "Output stream is closed" }

    inline fun emit(write: (ByteArray, Int, Int) -> Unit) {
        if (count == 0) return
        write(buffer, 0, count)
        count = 0
    }

    inline fun writeByte(byte: Byte, write: (ByteArray, Int, Int) -> Unit) {
        checkOpen()
        if (count == buffer.size) emit(write)
        buffer[count++] = byte
        if (count == buffer.size) emit(write)
    }

    inline fun write(bytes: ByteArray, offset: Int, length: Int, write: (ByteArray, Int, Int) -> Unit) {
        checkOpen()
        checkBounds(bytes, offset, length)
        if (length == 0) return
        if (length >= buffer.size) {
            emit(write)
            write(bytes, offset, length)
            return
        }
        if (length > buffer.size - count) emit(write)
        bytes.copyInto(buffer, count, offset, offset + length)
        count += length
        if (count == buffer.size) emit(write)
    }

    inline fun flush(write: (ByteArray, Int, Int) -> Unit, flush: () -> Unit) {
        checkOpen()
        emit(write)
        flush()
    }

    inline fun close(write: (ByteArray, Int, Int) -> Unit, close: () -> Unit) {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            emit(write)
        } catch (error: Throwable) {
            failure = error
        }
        try {
            close()
        } catch (error: Throwable) {
            if (failure == null) failure = error
            else if (failure !== error) failure.addSuppressed(error)
        } finally {
            count = 0
        }
        failure?.let { throw it }
    }
}
