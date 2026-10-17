package com.huskerdev.stdlibx.io.process

import com.huskerdev.stdlibx.io.*

/** Shared pipe semantics. Platform readers return zero at EOF and may return short reads. */
internal abstract class ProcessPipeInput : SyncInputStream {
    private var closed = false
    private var pending = -2 // -2: no lookahead; -1: EOF; 0..255: cached byte.
    private val lookahead = ByteArray(1)
    private val async by lazy { WrappedAsyncInputStream(this) }

    protected open fun availableNative(): Long = 0
    protected abstract fun readNative(buffer: ByteArray, offset: Int, length: Int): Int
    protected abstract fun closeNative()

    private fun checkOpen() = check(!closed) { "Process input stream is closed" }

    final override fun hasNext(): Boolean {
        checkOpen()
        if (pending == -2) {
            pending = if (readNative(lookahead, 0, 1) == 0)
                -1 else lookahead[0].toInt() and 255
        }
        return pending != -1
    }

    final override fun read(): Byte {
        if (!hasNext())
            throw NoSuchElementException()
        return pending.toByte().also { pending = -2 }
    }

    final override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkOpen()
        checkBounds(buffer, offset, length)
        if (length == 0 || pending == -1)
            return 0
        if (pending >= 0) {
            buffer[offset] = pending.toByte()
            pending = -2
            return 1 // Never block for another byte after a successful peek.
        }
        return readNative(buffer, offset, length).also { if (it == 0) pending = -1 }
    }

    final override fun availableBytes(): Long {
        checkOpen()
        return availableNative() + if (pending >= 0) 1 else 0
    }

    final override fun close() {
        if (closed) return
        closed = true
        pending = -1
        closeNative()
    }

    final override fun asAsync(): AsyncInputStream = async
}

/** Unbuffered native stdin pipes, including partial-write handling. */
internal abstract class ProcessPipeOutput : SyncOutputStream {
    private val async by lazy { WrappedAsyncOutputStream(this) }
    private var closed = false

    protected abstract fun writeNative(bytes: ByteArray, offset: Int, length: Int): Int
    protected abstract fun closeNative()

    private fun checkOpen() = check(!closed) { "Process output stream is closed" }

    final override fun write(byte: Byte) =
        write(byteArrayOf(byte))

    final override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkOpen()
        checkBounds(bytes, offset, length)
        var count = 0
        while (count < length) {
            val written = writeNative(bytes, offset + count, length - count)
            if (written <= 0)
                throw ProcessException("Process pipe write made no progress")
            count += written
        }
    }

    final override fun flush() =
        checkOpen()

    final override fun close() {
        if (closed) return
        closed = true
        closeNative()
    }

    final override fun asAsync(): AsyncOutputStream = async
}
