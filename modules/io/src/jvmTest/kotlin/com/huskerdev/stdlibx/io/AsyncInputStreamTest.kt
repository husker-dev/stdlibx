package com.huskerdev.stdlibx.io

import kotlin.test.*

class InputStreamTest {
    private class Bytes(private val bytes: ByteArray) : SyncInputStream {
        override fun asAsync() = WrappedAsyncInputStream(this)

        var position = 0
        var probes = 0

        override fun hasNext(): Boolean {
            probes++
            return position < bytes.size
        }

        override fun read(): Byte {
            if (position == bytes.size) throw NoSuchElementException()
            return bytes[position++]
        }

        override fun availableBytes() = (bytes.size - position).toLong()

        override fun close() = Unit
    }

    private class ShortReads(private val bytes: ByteArray) : SyncInputStream {
        override fun asAsync() = WrappedAsyncInputStream(this)

        private var position = 0

        override fun hasNext() = position < bytes.size

        override fun read(): Byte {
            if (!hasNext()) throw NoSuchElementException()
            return bytes[position++]
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = minOf(3, length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun availableBytes() = (bytes.size - position).toLong()

        override fun close() = Unit
    }

    @Test
    fun bulkReadRespectsOffsetAndLeavesOtherBytesUntouched() {
        val input = Bytes(byteArrayOf(0, -1, 42))
        val buffer = ByteArray(7) { 9 }
        assertEquals(3, input.read(buffer, 2, 4))
        assertContentEquals(byteArrayOf(9, 9, 0, -1, 42, 9, 9), buffer)
    }

    @Test
    fun bulkReadReturnsZeroAtEof() {
        assertEquals(0, Bytes(byteArrayOf()).read(ByteArray(2)))
    }

    @Test
    fun invalidBulkRangesDoNotConsumeInputOrMutateBuffer() {
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 5 to 0, 3 to 2, 1 to Int.MAX_VALUE)) {
            val input = Bytes(byteArrayOf(1, 2))
            val buffer = ByteArray(4) { 7 }
            assertFailsWith<IndexOutOfBoundsException>("offset=$offset length=$length") {
                input.read(buffer, offset, length)
            }
            assertEquals(0, input.position)
            assertEquals(0, input.probes)
            assertContentEquals(ByteArray(4) { 7 }, buffer)
        }
    }

    @Test
    fun zeroLengthBulkReadDoesNotProbeInput() {
        val input = Bytes(byteArrayOf(1))
        assertEquals(0, input.read(ByteArray(2), 2, 0))
        assertEquals(0, input.probes)
    }

    @Test
    fun zeroCountReadDoesNotProbeInput() {
        val input = Bytes(byteArrayOf(1))
        assertContentEquals(byteArrayOf(), input.read(0))
        assertEquals(0, input.probes)
    }

    @Test
    fun zeroSkipDoesNotProbeInput() {
        val input = Bytes(byteArrayOf(1))
        input.skip(0)
        assertEquals(0, input.probes)
    }

    @Test
    fun negativeCountsFailBeforeProbingInput() {
        val input = Bytes(byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { input.read(-1) }
        assertFailsWith<IllegalArgumentException> { input.skip(-1) }
        assertEquals(0, input.probes)
        assertEquals(0, input.position)
    }

    @Test
    fun readCountLeavesRemainingBytes() {
        val input = Bytes(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2), input.read(2))
        assertEquals(3.toByte(), input.read())
    }

    @Test
    fun readCountStopsAtEofWithoutPadding() {
        assertContentEquals(byteArrayOf(1, -1), Bytes(byteArrayOf(1, -1)).read(100))
    }

    @Test
    fun chunkBoundaryReadsPreserveEveryByte() {
        for (size in listOf(16383, 16384, 16385, 32769)) {
            val data = ByteArray(size) { it.toByte() }
            assertContentEquals(data, Bytes(data).readAllBytes(), "size=$size")
        }
    }

    @Test
    fun shortBulkReadsAreConcatenatedWithoutPadding() {
        val data = ByteArray(37) { it.toByte() }
        assertContentEquals(data, ShortReads(data).readAllBytes())
    }

    @Test
    fun skipConsumesExactlyRequestedBytes() {
        val input = Bytes(byteArrayOf(1, 2, 3, 4))
        input.skip(2)
        assertContentEquals(byteArrayOf(3, 4), input.readAllBytes())
    }

    @Test
    fun hugeSkipStopsAtEofWithoutIntegerOverflow() {
        val input = Bytes(byteArrayOf(1, 2, 3))
        input.skip(Long.MAX_VALUE)
        assertEquals(3, input.position)
        assertFalse(input.hasNext())
    }

    @Test
    fun skipHandlesShortBulkReads() {
        val input = ShortReads(ByteArray(20) { it.toByte() })
        input.skip(11)
        assertContentEquals(ByteArray(9) { (it + 11).toByte() }, input.readAllBytes())
    }
}
