package com.huskerdev.stdlibx.fs.nodejs

import com.huskerdev.stdlibx.io.AsyncInputStream
import com.huskerdev.stdlibx.io.SyncInputStream
import com.huskerdev.stdlibx.fs.checkBounds
import com.huskerdev.stdlibx.fs.Path

class NodeInputStream(
    private val path: Path,
    private val fd: Int
): SyncInputStream {
    private val buffer = nodeBuffer().alloc(8192)
    private var closed = false
    private var pending: Byte? = null
    private var eof = false
    private var consumed = 0L

    override fun asAsync(): AsyncInputStream = object : AsyncInputStream {
        private val sync: SyncInputStream get() = this@NodeInputStream
        override fun asSync(): SyncInputStream = sync
        override suspend fun hasNext() = sync.hasNext()
        override suspend fun read() = sync.read()
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = sync.read(buffer, offset, length)
        override suspend fun availableBytes() = sync.availableBytes()
        override suspend fun close() = sync.close()
    }

    private fun checkOpen() =
        check(!closed) { "Input stream is closed" }

    private fun nativeRead(length: Int): Int =
        nodeCall(path) { nodeFs().readSync(fd, buffer, 0, length, null) }

    override fun hasNext(): Boolean {
        checkOpen()
        if (pending != null) return true
        if (eof) return false
        if (nativeRead(1) == 0) {
            eof = true
            return false
        }
        pending = buffer.readUInt8(0).toByte()
        return true
    }

    override fun read(): Byte {
        if (!hasNext()) throw NoSuchElementException()
        return pending!!.also {
            pending = null
            consumed++
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkOpen()
        checkBounds(buffer, offset, length)
        if (length == 0) return 0
        var count = 0
        if(pending != null) {
            buffer[offset] = pending!!
            pending = null
            count++
        }
        if (!eof && count < length) {
            val read = nativeRead(minOf(length - count, this.buffer.length))
            if (read == 0) eof = true
            for (index in 0 until read)
                buffer[offset + count + index] = this.buffer.readUInt8(index).toByte()
            count += read
        }
        consumed += count
        return count
    }

    override fun availableBytes(): Long {
        checkOpen()
        val stat = NodeStat(nodeCall(path) { nodeFs().fstatSync(fd, statOptions()) })
        return if (stat.regular)
                (stat.size - consumed).coerceAtLeast(0)
        else if (pending == null) 0 else 1
    }

    override fun close() {
        if (closed) return
        closed = true
        pending = null
        nodeCall(path) { nodeFs().closeSync(fd) }
    }
}
