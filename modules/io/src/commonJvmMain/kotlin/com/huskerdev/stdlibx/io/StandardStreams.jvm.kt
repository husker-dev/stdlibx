package com.huskerdev.stdlibx.io

actual val stdin: SyncInputStream = object : StandardInputStream() {
    override fun readBytes(buffer: ByteArray, offset: Int, length: Int): Int =
        System.`in`.read(buffer, offset, length).coerceAtLeast(0)

    override fun available(): Long = System.`in`.available().toLong()
}

actual val stdout: SyncOutputStream = JvmStandardOutput { System.out }
actual val stderr: SyncOutputStream = JvmStandardOutput { System.err }

private class JvmStandardOutput(private val stream: () -> java.io.PrintStream) : StandardOutputStream() {
    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int): Int {
        val output = stream()
        output.write(bytes, offset, length)
        if (output.checkError()) throw IOException("Standard output write failed")
        return length
    }
    
    override fun flush() {
        val output = stream()
        output.flush()
        if (output.checkError()) throw IOException("Standard output flush failed")
    }
}
