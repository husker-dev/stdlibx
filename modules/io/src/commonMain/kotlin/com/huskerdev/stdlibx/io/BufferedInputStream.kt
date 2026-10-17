package com.huskerdev.stdlibx.io

/** Buffers reads from [source] and closes it on close. Its sync and async views must not be used concurrently. */
class BufferedSyncInputStream internal constructor(
    private val source: SyncInputStream,
    private val common: BufferedCommonInput
): SyncInputStream {

    /** Creates a buffered reader with a positive [bufferSize]. */
    constructor(
        source: SyncInputStream,
        bufferSize: Int = DEFAULT_STREAM_BUFFER_SIZE,
    ): this(source, BufferedCommonInput(bufferSize))

    private val async: BufferedAsyncInputStream by lazy { BufferedAsyncInputStream(source.asAsync(), common) }

    override fun hasNext(): Boolean =
        common.hasNext(source::read)

    override fun read(): Byte =
        common.readByte(source::read)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        common.read(buffer, offset, length, source::read)

    override fun availableBytes(): Long =
        common.availableBytes(source::availableBytes)

    override fun close() =
        common.close(source::close)

    override fun asAsync(): BufferedAsyncInputStream = async
}

/** Buffers suspending reads from [source] and closes it on close. Its views share position and lifetime. */
class BufferedAsyncInputStream internal constructor(
    private val source: AsyncInputStream,
    private val common: BufferedCommonInput
): AsyncInputStream {

    /** Creates a buffered reader with a positive [bufferSize]. */
    constructor(
        source: AsyncInputStream,
        bufferSize: Int = DEFAULT_STREAM_BUFFER_SIZE
    ): this(source, BufferedCommonInput(bufferSize))

    private val sync: BufferedSyncInputStream? by lazy {
        source.asSync()?.let { BufferedSyncInputStream(it, common) }
    }

    override suspend fun hasNext(): Boolean = common.hasNext { bytes, offset, length ->
        source.read(bytes, offset, length)
    }

    override suspend fun read(): Byte = common.readByte { bytes, offset, length ->
        source.read(bytes, offset, length)
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        common.read(buffer, offset, length) { bytes, start, count -> source.read(bytes, start, count) }

    override suspend fun availableBytes(): Long =
        common.availableBytes { source.availableBytes() }

    override suspend fun close() =
        common.close { source.close() }

    override fun asSync(): BufferedSyncInputStream? = sync
}

internal class BufferedCommonInput(bufferSize: Int) {
    init {
        require(bufferSize > 0) { "Buffer size must be positive" }
    }

    val buffer = ByteArray(bufferSize)
    var position = 0
    var limit = 0
    var eof = false
    var closed = false

    fun checkOpen() = check(!closed) { "Input stream is closed" }

    inline fun hasNext(read: (ByteArray, Int, Int) -> Int): Boolean {
        checkOpen()
        if (position < limit) return true
        if (eof) return false
        val count = read(buffer, 0, buffer.size)
        check(count in 0..buffer.size) { "Invalid read count: $count" }
        position = 0
        limit = count
        eof = count == 0
        return !eof
    }

    inline fun readByte(read: (ByteArray, Int, Int) -> Int): Byte {
        if (!hasNext(read)) throw NoSuchElementException()
        return buffer[position++]
    }

    inline fun read(bytes: ByteArray, offset: Int, length: Int, read: (ByteArray, Int, Int) -> Int): Int {
        checkOpen()
        checkBounds(bytes, offset, length)
        if (length == 0) return 0
        if (position == limit && !eof && length >= buffer.size) {
            val count = read(bytes, offset, length)
            check(count in 0..length) { "Invalid read count: $count" }
            eof = count == 0
            return count
        }
        if (!hasNext(read)) return 0
        val count = minOf(length, limit - position)
        buffer.copyInto(bytes, offset, position, position + count)
        position += count
        return count
    }

    inline fun availableBytes(available: () -> Long): Long {
        checkOpen()
        val pending = (limit - position).toLong()
        val upstream = available().coerceAtLeast(0)
        return if (upstream > Long.MAX_VALUE - pending) Long.MAX_VALUE else upstream + pending
    }

    inline fun close(close: () -> Unit) {
        if (closed) return
        closed = true
        position = 0
        limit = 0
        close()
    }
}
