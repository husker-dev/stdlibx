@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.fs.opfs

import com.huskerdev.stdlibx.io.*
import com.huskerdev.stdlibx.fs.Path
import com.huskerdev.stdlibx.fs.checkBounds
import kotlinx.coroutines.*

/** Reads bounded Blob slices. The browser may invalidate this snapshot after external changes. */
internal class OpfsInputStream(private val path: Path, private val file: OpfsFile): AsyncInputStream {
    private val size = file.size.opfsSize()
    private var position = 0L
    private var buffer = ByteArray(0)
    private var offset = 0
    private var closed = false

    private suspend fun checkOpen() {
        check(!closed) { "Input stream is closed" }
        currentCoroutineContext().ensureActive()
    }

    override suspend fun hasNext(): Boolean {
        checkOpen()
        if (offset < buffer.size) return true
        if (position == size) return false
        buffer = opfsCall(path) {
            opfsBytes(
                file.slice(position.toDouble(), minOf(size, position + 8192).toDouble())
                    .arrayBuffer()
                    .awaitOpfs()
            ).toByteArray()
        }
        offset = 0
        if (buffer.isEmpty()) throw com.huskerdev.stdlibx.fs.FileSystemException(path, "OPFS read made no progress")
        return true
    }

    override suspend fun read(): Byte {
        if (!hasNext()) throw NoSuchElementException()
        position++
        return buffer[offset++]
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkOpen()
        checkBounds(buffer, offset, length)
        if (length == 0 || !hasNext()) return 0

        val count = minOf(length, this.buffer.size - this.offset)
        this.buffer.copyInto(buffer, offset, this.offset, this.offset + count)
        this.offset += count
        position += count
        return count
    }

    override suspend fun availableBytes(): Long {
        checkOpen()
        return size - position
    }

    override suspend fun skip(count: Long): Long {
        require(count >= 0) { "count must be non-negative" }
        if (count == 0L) return 0
        checkOpen()

        val skipped = minOf(count, size - position)
        if (skipped == 0L) return 0
        position += skipped
        if (skipped <= buffer.size - offset)
            offset += skipped.toInt()
        else {
            buffer = ByteArray(0)
            offset = 0
        }
        return skipped
    }

    override suspend fun close() {
        closed = true
        buffer = ByteArray(0)
    }

    override fun asSync(): SyncInputStream? = null
}

/** Writes are published by close, not flush; a failed/cancelled write aborts the transaction. */
internal class OpfsOutputStream(private val path: Path, private val writer: OpfsWriter): AsyncOutputStream {
    private var closed = false

    private suspend fun checkOpen() {
        check(!closed) { "Output stream is closed" }
        currentCoroutineContext().ensureActive()
    }

    override suspend fun write(byte: Byte) =
        write(byteArrayOf(byte))

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!closed) { "Output stream is closed" }
        checkBounds(bytes, offset, length)
        writeOperation {
            if (length != 0) opfsCall(path) {
                writer.write(bytes.toOpfsBytes(offset, length)).awaitOpfs()
            }
        }
    }

    internal suspend fun writeData(data: JsAny) = writeOperation {
        opfsCall(path) { writer.write(data).awaitOpfs() }
    }

    private suspend fun writeOperation(block: suspend () -> Unit) {
        try {
            checkOpen()
            block()
        } catch (error: Throwable) {
            try {
                abort()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    override suspend fun flush() {
        checkOpen()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        withContext(NonCancellable) {
            try {
                opfsCall(path) { writer.close().awaitOpfs() }
            } catch (error: Throwable) {
                try {
                    opfsCall(path) { writer.abort().awaitOpfs() }
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }
    }

    internal suspend fun abort() {
        if (closed) return
        closed = true
        withContext(NonCancellable) { opfsCall(path) { writer.abort().awaitOpfs() } }
    }

    override fun asSync(): SyncOutputStream? = null
}

/** Internal handle iterator shared by public directory streams and tree copying. */
internal class OpfsDirectoryEntries(private val path: Path, directory: OpfsHandle): AsyncCloseable {
    private val iterator = directory.values()
    private var closed = false
    private var ended = false

    suspend fun next(): OpfsHandle? {
        check(!closed) { "Directory stream is closed" }
        if (ended) return null
        val step = opfsCall(path) { iterator.next().awaitOpfs() }
        if (step.done) { ended = true; return null }
        return checkNotNull(step.value)
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        withContext(NonCancellable) { opfsCall(path) { opfsIteratorClose(iterator).awaitOpfs() } }
    }
}
