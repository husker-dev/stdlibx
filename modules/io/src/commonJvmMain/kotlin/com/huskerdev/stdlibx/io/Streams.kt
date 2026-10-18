package com.huskerdev.stdlibx.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import java.io.OutputStream

internal actual val asyncDispatcher: CoroutineDispatcher get() = Dispatchers.IO

/** Adapts a Java input stream; I/O failures use [IOException], and closing closes the original stream. */
fun InputStream.wrap(): SyncInputStream = object: SyncInputStream {
    private val async by lazy { WrappedAsyncInputStream(this) }

    private var cached = -2
    private var closed = false

    private fun checkOpen() = check(!closed) { "Input stream is closed" }

    override fun hasNext(): Boolean {
        checkOpen()
        if (cached == -2) cached = this@wrap.read()
        return cached != -1
    }

    override fun read(): Byte {
        if (!hasNext()) throw NoSuchElementException()
        return cached.toByte().also { cached = -2 }
    }

    override fun availableBytes(): Long {
        checkOpen()
        return this@wrap.available().toLong() + if (cached >= 0) 1 else 0
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        checkOpen()
        checkBounds(buffer, offset, length)
        if (length == 0 || cached == -1) return 0

        var count = 0
        if (cached >= 0) {
            buffer[offset] = cached.toByte()
            cached = -2
            count = 1
        }
        if (count < length) {
            val read = this@wrap.read(buffer, offset + count, length - count)
            if (read < 0) cached = -1 else count += read
        }
        return count
    }

    override fun close() {
        if (!closed) {
            closed = true
            this@wrap.close()
        }
    }

    override fun asAsync(): AsyncInputStream = async
}

/** Adapts a Java output stream; I/O failures use [IOException], and closing closes the original stream. */
fun OutputStream.wrap(): SyncOutputStream = object: SyncOutputStream {
    private val async by lazy { WrappedAsyncOutputStream(this) }

    private var closed = false

    private fun checkOpen() = check(!closed) { "Output stream is closed" }

    override fun write(byte: Byte) {
        checkOpen()
        this@wrap.write(byte.toInt() and 0xFF)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkOpen()
        checkBounds(bytes, offset, length)
        this@wrap.write(bytes, offset, length)
    }

    override fun flush() {
        checkOpen()
        this@wrap.flush()
    }

    override fun close() {
        if (!closed) {
            closed = true
            this@wrap.close()
        }
    }

    override fun asAsync(): AsyncOutputStream = async
}
