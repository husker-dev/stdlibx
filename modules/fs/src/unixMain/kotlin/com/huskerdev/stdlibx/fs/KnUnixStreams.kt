@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.io.AsyncOutputStream
import com.huskerdev.stdlibx.io.WrappedAsyncInputStream
import com.huskerdev.stdlibx.io.WrappedAsyncOutputStream
import com.huskerdev.stdlibx.io.SyncInputStream
import com.huskerdev.stdlibx.io.SyncOutputStream
import com.huskerdev.stdlibx.io.IOException
import kotlinx.cinterop.*
import platform.posix.*

internal class UnixInputStream(
    private val path: Path,
    private val fd: Int
): SyncInputStream {
    private val async by lazy { WrappedAsyncInputStream(this) }

    private var closed = false
    private var pending: Byte? = null
    private var eof = false

    private fun ensureOpen() = check(!closed) { "Input stream is closed" }

    private fun readNative(buffer: ByteArray, offset: Int, length: Int): Int = buffer.usePinned {
        retryUnix(path) { read(fd, it.addressOf(offset), length.convert()) }.toInt()
    }

    override fun hasNext(): Boolean {
        ensureOpen()
        if (pending != null) return true
        if (eof) return false

        val byte = ByteArray(1)
        if (readNative(byte, 0, 1) == 0) { eof = true; return false }
        pending = byte[0]
        return true
    }

    override fun read(): Byte {
        if (!hasNext()) throw NoSuchElementException()
        return pending!!.also { pending = null }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        ensureOpen()
        checkBounds(buffer, offset, length)
        if (length == 0) return 0
        var count = 0
        pending?.let { buffer[offset] = it; pending = null; count = 1 }
        if (count < length && !eof) {
            val read = readNative(buffer, offset + count, length - count)
            if (read == 0) eof = true
            count += read
        }
        return count
    }

    override fun availableBytes(): Long {
        ensureOpen()
        return memScoped {
            val info = alloc<stat>()
            checkUnixError(path, fstat(fd, info.ptr))
            val position = lseek(fd, 0, SEEK_CUR)
            if (position < 0) {
                if (errno == ESPIPE) return@memScoped if (pending != null) 1L else 0L
                throwUnixError(path)
            }
            (info.st_size - position).coerceAtLeast(0) + if (pending != null) 1 else 0
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pending = null
        checkUnixError(path, close(fd))
    }

    override fun asAsync() = async
}

internal class UnixOutputStream(
    private val path: Path,
    private val fd: Int
): SyncOutputStream {
    private val async by lazy { WrappedAsyncOutputStream(this) }

    private var closed = false

    private fun ensureOpen() =
        check(!closed) { "Output stream is closed" }

    override fun write(byte: Byte) =
        write(byteArrayOf(byte), 0, 1)

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        ensureOpen()
        checkBounds(bytes, offset, length)
        writeUnixFully(path, fd, bytes, offset, length)
    }

    // Writes are unbuffered
    override fun flush() {
        ensureOpen()
    }

    override fun close() {
        if (closed) return
        closed = true
        checkUnixError(path, close(fd))
    }

    override fun asAsync(): AsyncOutputStream = async
}

/** Writes a validated byte range, retrying interruptions and handling partial writes. */
internal fun writeUnixFully(path: Path, fd: Int, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
    if (length == 0) return
    bytes.usePinned { pinned ->
        var count = 0
        while (count < length) {
            val written = try {
                writeStandardStreamBytes(fd, pinned.addressOf(offset + count), length - count)
            } catch (error: IOException) {
                throw FileSystemException(path, error.message ?: "File write failed", error)
            }
            if (written == 0) throw FileSystemException(path, "Write made no progress")
            count += written
        }
    }
}
