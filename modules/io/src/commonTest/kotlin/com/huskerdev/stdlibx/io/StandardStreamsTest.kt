package com.huskerdev.stdlibx.io

import kotlin.test.*

class StandardStreamsTest {
    @Test
    fun inputLookaheadIsUnsignedAndCloseDoesNotConsumeOrDisableInput() {
        val data = byteArrayOf(-1, 0, 42)
        var position = 0
        var reads = 0
        val input =
            object : StandardInputStream() {
                override fun readBytes(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    val count = minOf(length, data.size - position)
                    data.copyInto(buffer, offset, position, position + count)
                    position += count
                    return count
                }

                override fun available(): Long = (data.size - position).toLong()
            }
        assertTrue(input.hasNext())
        assertTrue(input.hasNext())
        assertEquals(1, reads)
        assertEquals(3L, input.availableBytes())
        input.close()
        val buffer = ByteArray(8)
        assertEquals(1, input.read(buffer))
        assertEquals((-1).toByte(), buffer[0])
        assertEquals(1, reads)
        assertContentEquals(byteArrayOf(0, 42), input.readAllBytes())
        assertFalse(input.hasNext())
        input.close()
        assertEquals(0, input.read(buffer))
        assertFailsWith<NoSuchElementException> { input.read() }
        assertSame(input, input.asAsync().asSync())
    }

    @Test
    fun outputHandlesPartialWritesAndCloseOnlyFlushes() {
        val bytes = ArrayList<Byte>()
        var flushes = 0
        val output =
            object : StandardOutputStream() {
                override fun writeBytes(data: ByteArray, offset: Int, length: Int): Int {
                    val count = minOf(length, 2)
                    repeat(count) { bytes.add(data[offset + it]) }
                    return count
                }

                override fun flush() {
                    flushes++
                }
            }
        output.write(byteArrayOf(99, -1, 0, 42, 99), 1, 3)
        output.close()
        output.write(7)
        output.close()
        assertEquals(2, flushes)
        assertContentEquals(byteArrayOf(-1, 0, 42, 7), bytes.toByteArray())
        assertSame(output, output.asAsync().asSync())
        assertFailsWith<IndexOutOfBoundsException> { output.write(ByteArray(1), 1, 1) }
    }

    @Test
    fun stalledOutputFailsAndEmptyOperationsDoNotTouchNativeStreams() {
        var calls = 0
        val output =
            object : StandardOutputStream() {
                override fun writeBytes(bytes: ByteArray, offset: Int, length: Int): Int {
                    calls++
                    return 0
                }
            }
        output.write(ByteArray(0))
        assertEquals(0, calls)
        assertFailsWith<IOException> { output.write(0) }
        val input =
            object : StandardInputStream() {
                override fun readBytes(buffer: ByteArray, offset: Int, length: Int): Int =
                    error("Unexpected read")
            }
        assertEquals(0, input.read(ByteArray(0)))
        assertFailsWith<IndexOutOfBoundsException> { input.read(ByteArray(1), -1, 1) }
    }
}
