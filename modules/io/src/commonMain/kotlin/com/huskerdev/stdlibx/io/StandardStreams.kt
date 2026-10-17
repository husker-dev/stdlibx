package com.huskerdev.stdlibx.io

/**
 * Shared standard input. Closing it has no effect; browsers provide an empty stream.
 * Reads may block, including through async adapters. Use one reader at a time.
 */
expect val stdin: SyncInputStream

/**
 * Shared standard output. Closing it only flushes pending output.
 * Browsers print UTF-8 lines to the console; flushing also prints an unfinished line.
 * Failed writes and flushes throw [IOException], including failures reported by JVM PrintStream.
 */
expect val stdout: SyncOutputStream

/** Shared standard error. Behaves like [stdout], using the error console in browsers. */
expect val stderr: SyncOutputStream

internal abstract class StandardInputStream : SyncInputStream {
    private var pending = -2
    private val lookahead = ByteArray(1)
    private val async by lazy { WrappedAsyncInputStream(this) }
    protected abstract fun readBytes(buffer: ByteArray, offset: Int, length: Int): Int
    protected open fun available(): Long = 0

    final override fun hasNext(): Boolean {
        if (pending == -2) {
            pending = if (readBytes(lookahead, 0, 1) == 0)
                -1
            else lookahead[0].toInt() and 255
        }
        return pending != -1
    }

    final override fun read(): Byte {
        if (!hasNext()) throw NoSuchElementException()
        return pending.toByte().also { pending = -2 }
    }

    final override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkBounds(buffer, offset, length)
        if (length == 0 || pending == -1) return 0
        if (pending >= 0) {
            buffer[offset] = pending.toByte()
            pending = -2
            return 1
        }
        return readBytes(buffer, offset, length)
            .also { if (it == 0) pending = -1 }
    }

    final override fun availableBytes(): Long =
        available() + if (pending >= 0) 1 else 0

    final override fun close() = Unit

    final override fun asAsync(): AsyncInputStream = async
}

internal abstract class StandardOutputStream : SyncOutputStream {
    private val async by lazy { WrappedAsyncOutputStream(this) }

    protected abstract fun writeBytes(bytes: ByteArray, offset: Int, length: Int): Int

    final override fun write(byte: Byte) =
        write(byteArrayOf(byte))

    final override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkBounds(bytes, offset, length)
        var written = 0
        while (written < length) {
            val count = writeBytes(bytes, offset + written, length - written)
            if (count !in 1..(length - written)) throw IOException("Standard stream write made no progress")
            written += count
        }
    }

    override fun flush() = Unit

    final override fun close() = flush()
    
    final override fun asAsync(): AsyncOutputStream = async
}
