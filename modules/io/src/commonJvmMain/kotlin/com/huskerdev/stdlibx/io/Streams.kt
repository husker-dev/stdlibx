package com.huskerdev.stdlibx.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import java.io.OutputStream
import com.huskerdev.stdlibx.io.fs.Path
import com.huskerdev.stdlibx.io.fs.FileSystemException

internal actual val asyncDispatcher: CoroutineDispatcher get() = Dispatchers.IO

/** Adapts a Java input stream; I/O failures use [IOException], and closing closes the original stream. */
fun InputStream.wrap(): SyncInputStream = wrap(null)

internal inline fun <T> streamCall(path: Path?, block: () -> T): T =
    if (path == null) block() else try {
        block()
    } catch (error: FileSystemException) {
        throw error
    } catch (error: java.io.IOException) {
        throw FileSystemException(path, error.message ?: "File I/O failed", error)
    }

internal fun InputStream.wrap(path: Path?): SyncInputStream = object: SyncInputStream {
    private val async by lazy { WrappedAsyncInputStream(this) }

    private var cached = -2
    private var closed = false

    private fun checkOpen() = check(!closed) { "Input stream is closed" }

    override fun hasNext(): Boolean {
        checkOpen()
        if (cached == -2) cached = streamCall(path) { this@wrap.read() }
        return cached != -1
    }

    override fun read(): Byte {
        if (!hasNext()) throw NoSuchElementException()
        return cached.toByte().also { cached = -2 }
    }

    override fun availableBytes(): Long {
        checkOpen()
        return streamCall(path) { this@wrap.available().toLong() } + if (cached >= 0) 1 else 0
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
            val read = streamCall(path) { this@wrap.read(buffer, offset + count, length - count) }
            if (read < 0) cached = -1 else count += read
        }
        return count
    }

    override fun close() {
        if (!closed) {
            closed = true
            streamCall(path) { this@wrap.close() }
        }
    }

    override fun asAsync(): AsyncInputStream = async
}

/** Adapts a Java output stream; I/O failures use [IOException], and closing closes the original stream. */
fun OutputStream.wrap(): SyncOutputStream = wrap(null)

internal fun OutputStream.wrap(path: Path?): SyncOutputStream = object: SyncOutputStream {
    private val async by lazy { WrappedAsyncOutputStream(this) }

    private var closed = false

    private fun checkOpen() = check(!closed) { "Output stream is closed" }

    override fun write(byte: Byte) {
        checkOpen()
        streamCall(path) { this@wrap.write(byte.toInt() and 0xFF) }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkOpen()
        checkBounds(bytes, offset, length)
        streamCall(path) { this@wrap.write(bytes, offset, length) }
    }

    override fun flush() {
        checkOpen()
        streamCall(path) { this@wrap.flush() }
    }

    override fun close() {
        if (!closed) {
            closed = true
            streamCall(path) { this@wrap.close() }
        }
    }

    override fun asAsync(): AsyncOutputStream = async
}
