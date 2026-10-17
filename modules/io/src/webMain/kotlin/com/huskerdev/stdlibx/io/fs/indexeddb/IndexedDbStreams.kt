@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.io.fs.indexeddb

import com.huskerdev.stdlibx.io.*
import com.huskerdev.stdlibx.io.fs.*
import kotlinx.coroutines.*
import kotlin.js.JsAny
import kotlin.js.JsArray

/** A stable snapshot Blob, read in bounded slices; the database stores no chunks. */
internal class IndexedDbInputStream(private val path: Path, blob: DbBlob): AsyncInputStream {
    private val size = blob.size.dbSize()
    private var content: DbBlob? = blob
    private var position = 0L
    private var bytes = ByteArray(0)
    private var offset = 0
    private var closed = false

    private suspend fun checkOpen() {
        check(!closed) { "Input stream is closed" }
        currentCoroutineContext().ensureActive()
    }

    override suspend fun hasNext(): Boolean {
        checkOpen()
        if (offset < bytes.size) return true
        if (position == size) return false
        bytes = indexedDbCall(path) {
            dbBytes(checkNotNull(content)
                .slice(position.toDouble(), minOf(size, position + 8192).toDouble())
                .arrayBuffer().awaitDb()
            ).toByteArray()
        }
        offset = 0
        check(bytes.isNotEmpty()) { "IndexedDB Blob read made no progress" }
        return true
    }

    override suspend fun read(): Byte {
        if (!hasNext()) throw NoSuchElementException()
        position++
        return bytes[offset++]
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkOpen()
        checkBounds(buffer, offset, length)
        if (length == 0 || !hasNext()) return 0

        val count = minOf(length, bytes.size - this.offset)
        bytes.copyInto(buffer, offset, this.offset, this.offset + count)
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
        position += skipped
        if (skipped <= bytes.size - offset)
            offset += skipped.toInt()
        else {
            bytes = ByteArray(0)
            offset = 0
        }
        return skipped
    }

    override suspend fun close() {
        closed = true
        content = null
        bytes = ByteArray(0)
    }

    override fun asSync(): SyncInputStream? = null
}

/** Accumulates in-memory Blob parts, then stores one composed Blob in one transaction. */
internal class IndexedDbOutputStream(
    private val path: Path,
    base: DbBlob,
    private val mode: FileWriteMode,
    private val publish: suspend (DbBlob) -> Unit
): AsyncOutputStream {
    private var base: DbBlob? = base
    private var parts: JsArray<JsAny> = dbParts()
    private var closed = false

    override suspend fun write(byte: Byte) =
        write(byteArrayOf(byte))

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!closed) { "Output stream is closed" }
        checkBounds(bytes, offset, length)
        try {
            currentCoroutineContext().ensureActive()
            if (length != 0) indexedDbCall(path) {
                dbPush(parts, bytes.toDbBlob(offset, length))
            }
        } catch (error: Throwable) {
            closed = true; base = null; parts = dbParts()
            throw error
        }
    }

    override suspend fun flush() {
        check(!closed) { "Output stream is closed" }
        currentCoroutineContext().ensureActive()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        try {
            val data = indexedDbCall(path) {
                dbCompose(checkNotNull(base), dbBlob(parts), mode)
            }
            withContext(NonCancellable) {
                publish(data)
            }
        } finally {
            base = null
            parts = dbParts()
        }
    }

    override fun asSync(): SyncOutputStream? = null
}
