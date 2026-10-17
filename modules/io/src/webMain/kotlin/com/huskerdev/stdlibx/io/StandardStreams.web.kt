@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.io

import com.huskerdev.stdlibx.io.fs.nodejs.nodeBuffer
import com.huskerdev.stdlibx.io.fs.nodejs.nodeFs

private fun isNodeStandardStream(): Boolean =
    js("typeof process !== 'undefined' && !!process.versions && !!process.versions.node")

private fun consoleWrite(text: String, error: Boolean): Unit =
    js("error ? console.error(text) : console.log(text)")

actual val stdin: SyncInputStream = object : StandardInputStream() {
    override fun readBytes(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isNodeStandardStream()) return 0
        val bytes = nodeBuffer().alloc(length)
        val count = ioCall { nodeFs().readSync(0, bytes, 0, length, null) }
        repeat(count) {
            buffer[offset + it] = bytes.readUInt8(it).toByte()
        }
        return count
    }
}

actual val stdout: SyncOutputStream = WebStandardOutput(1)
actual val stderr: SyncOutputStream = WebStandardOutput(2)

private class WebStandardOutput(private val fd: Int) : StandardOutputStream() {
    private val pending = ArrayList<Byte>()

    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int): Int {
        if (isNodeStandardStream()) {
            val buffer = nodeBuffer().alloc(length)
            repeat(length) {
                buffer.writeUInt8(bytes[offset + it].toInt() and 255, it)
            }
            return ioCall { nodeFs().writeSync(fd, buffer, 0, length, null) }
        }
        for (index in offset until offset + length) {
            if (bytes[index] == 10.toByte()) emit() else pending.add(bytes[index])
        }
        return length
    }

    private fun emit() {
        ioCall { consoleWrite(pending.toByteArray().decodeToString(), fd == 2) }
        pending.clear()
    }

    override fun flush() {
        if (pending.isNotEmpty()) emit()
    }
}
