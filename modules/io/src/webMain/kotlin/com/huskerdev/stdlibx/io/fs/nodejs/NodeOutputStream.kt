package com.huskerdev.stdlibx.io.fs.nodejs

import com.huskerdev.stdlibx.io.AsyncOutputStream
import com.huskerdev.stdlibx.io.SyncOutputStream
import com.huskerdev.stdlibx.io.checkBounds
import com.huskerdev.stdlibx.io.fs.FileSystemException
import com.huskerdev.stdlibx.io.fs.Path

class NodeOutputStream(
    private val path: Path,
    private val fd: Int
): SyncOutputStream {
    private val buffer = nodeBuffer().alloc(8192)
    private var closed = false

    private val async: AsyncOutputStream by lazy {
        val wrapped = this@NodeOutputStream
        object : AsyncOutputStream {
            override suspend fun write(byte: Byte) = wrapped.write(byte)
            override suspend fun write(bytes: ByteArray, offset: Int, length: Int) =
                wrapped.write(bytes, offset, length)

            override suspend fun flush() = wrapped.flush()
            override suspend fun close() = wrapped.close()

            override fun asSync(): SyncOutputStream = wrapped
        }
    }

    private fun checkOpen() =
        check(!closed) { "Output stream is closed" }

    override fun write(byte: Byte) =
        write(byteArrayOf(byte))

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkOpen()
        checkBounds(bytes, offset, length)
        var written = 0
        while (written < length) {
            val count = minOf(length - written, buffer.length)
            for (index in 0 until count)
                buffer.writeUInt8(bytes[offset + written + index].toInt() and 255, index)

            val result = nodeCall(path) { nodeFs().writeSync(fd, buffer, 0, count, null) }
            if (result == 0) throw FileSystemException(path, "Write made no progress")
            written += result
        }
    }

    override fun flush() {
        checkOpen()
    }

    override fun close() {
        if (closed) return
        closed = true
        nodeCall(path) { nodeFs().closeSync(fd) }
    }

    override fun asAsync(): AsyncOutputStream = async
}