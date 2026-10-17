package com.huskerdev.stdlibx.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class JvmStreamAdapterTest {
    private class CountingInput(
        private val data: ByteArray,
        private val advertisedAvailable: Int? = null,
    ) : java.io.InputStream() {
        var reads = 0
        var position = 0
        var closes = 0

        override fun read(): Int {
            reads++
            return if (position < data.size) data[position++].toInt() and 255 else -1
        }

        override fun available() = advertisedAvailable ?: (data.size - position)

        override fun close() {
            closes++
        }
    }

    @Test
    fun constructionIsLazy() {
        val source = CountingInput(byteArrayOf(1))
        source.wrap()
        assertEquals(0, source.reads)
    }

    @Test
    fun repeatedHasNextDoesNotConsumeAdditionalBytes() {
        val source = CountingInput(byteArrayOf(1, 2, 3))
        val input = source.wrap()
        repeat(3) { assertTrue(input.hasNext()) }
        assertEquals(1, source.reads)
        assertEquals(1.toByte(), input.read())
        assertEquals(2.toByte(), input.read())
    }

    @Test
    fun cachedNextDoesNotReadAhead() {
        val source = CountingInput(byteArrayOf(1, 2))
        val input = source.wrap()
        assertTrue(input.hasNext())
        assertEquals(1.toByte(), input.read())
        assertEquals(1, source.reads)
    }

    @Test
    fun directNextPreservesAllByteValues() {
        val input = ByteArrayInputStream(byteArrayOf(0, 127, -128, -1)).wrap()
        assertEquals(0.toByte(), input.read())
        assertEquals(127.toByte(), input.read())
        assertEquals((-128).toByte(), input.read())
        assertEquals((-1).toByte(), input.read())
        assertFailsWith<NoSuchElementException> { input.read() }
    }

    @Test
    fun ordinaryIterationPreservesAllBytes() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, -1)).wrap()
        val bytes = mutableListOf<Byte>()
        while (input.hasNext()) bytes += input.read()
        assertContentEquals(byteArrayOf(1, 2, 3, -1), bytes.toByteArray())
    }

    @Test
    fun readAllBytesDoesNotLoseLookaheadBytes() {
        val data = ByteArray(16385) { it.toByte() }
        assertContentEquals(data, ByteArrayInputStream(data).wrap().readAllBytes())
    }

    @Test
    fun readCountPreservesRequestedAndRemainingBytes() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)).wrap()
        assertContentEquals(byteArrayOf(1, 2), input.read(2))
        assertEquals(3.toByte(), input.read())
        assertEquals(4.toByte(), input.read())
    }

    @Test
    fun readCountDoesNotReadAheadAfterRequestedBytes() = runBlocking {
        for (size in listOf(2, 16385)) {
            for (async in listOf(false, true)) {
                val source = CountingInput(ByteArray(size + 1) { it.toByte() })
                source.wrap().use { input ->
                    val result = if (async) input.asAsync().read(size) else input.read(size)
                    assertContentEquals(ByteArray(size) { it.toByte() }, result)
                    assertEquals(size, source.position)
                    assertEquals(size, source.reads)
                    assertEquals(size.toByte(), input.read())
                }
            }
        }
    }

    @Test
    fun skipDoesNotReadAheadAfterRequestedBytes() = runBlocking {
        for (size in listOf(2, 2049)) {
            for (async in listOf(false, true)) {
                val source = CountingInput(ByteArray(size + 1) { it.toByte() })
                source.wrap().use { input ->
                    if (async) input.asAsync().skip(size.toLong()) else input.skip(size.toLong())
                    assertEquals(size, source.position)
                    assertEquals(size, source.reads)
                    assertEquals(size.toByte(), input.read())
                }
            }
        }
    }

    @Test
    fun skipPreservesFirstUnskippedByte() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)).wrap()
        input.skip(2)
        assertEquals(3.toByte(), input.read())
        assertEquals(4.toByte(), input.read())
    }

    @Test
    fun zeroCountOperationsDoNotReadDelegate() {
        val source = CountingInput(byteArrayOf(1, 2))
        val input = source.wrap()
        input.read(0)
        input.skip(0)
        assertEquals(0, source.reads)
    }

    @Test
    fun availableIncludesCachedByte() {
        val input = CountingInput(byteArrayOf(1, 2)).wrap()
        assertEquals(2L, input.availableBytes())
        assertTrue(input.hasNext())
        assertEquals(2L, input.availableBytes())
    }

    @Test
    fun cachedAvailabilityUsesLongArithmetic() {
        val input = CountingInput(byteArrayOf(1), Int.MAX_VALUE).wrap()
        assertTrue(input.hasNext())
        assertEquals(Int.MAX_VALUE.toLong() + 1, input.availableBytes())
    }

    @Test
    fun zeroAvailabilityDoesNotMeanEof() {
        val input = CountingInput(byteArrayOf(1, 2), 0).wrap()
        assertEquals(0L, input.availableBytes())
        assertEquals(1.toByte(), input.read())
        assertEquals(2.toByte(), input.read())
        assertFalse(input.hasNext())
    }

    @Test
    fun emptyDelegateReportsEofAndNextThrows() {
        val input = ByteArrayInputStream(byteArrayOf()).wrap()
        assertFalse(input.hasNext())
        assertFailsWith<NoSuchElementException> { input.read() }
    }

    @Test
    fun closingUnusedAdapterDoesNotRead() {
        val source = CountingInput(byteArrayOf(1))
        source.wrap().close()
        assertEquals(1, source.closes)
        assertEquals(0, source.reads)
    }

    @Test
    fun useClosesDelegateAndSuppressesCloseFailureOnReadFailure() {
        val readFailure = IOException("read failure")
        val closeFailure = IOException("close failure")
        val source =
            object : java.io.InputStream() {
                override fun read(): Int = throw readFailure

                override fun close(): Unit = throw closeFailure
            }
        val failure =
            assertFailsWith<IOException> {
                source.wrap().use { it.read() }
            }
        assertSame(readFailure, failure)
        assertContentEquals(arrayOf(closeFailure), failure.suppressed)
    }

    @Test
    fun outputPreservesLowEightBitsAndBulkSlice() {
        val destination = ByteArrayOutputStream()
        val output = destination.wrap()
        output.write((-1).toByte())
        output.write(byteArrayOf(1, 2, 3, 4), 1, 2)
        assertContentEquals(byteArrayOf(-1, 2, 3), destination.toByteArray())
    }

    @Test
    fun outputDelegatesFlushAndClose() {
        var flushes = 0
        var closes = 0
        val output =
            object : java.io.OutputStream() {
                    override fun write(value: Int) = Unit

                    override fun flush() {
                        flushes++
                    }

                    override fun close() {
                        closes++
                    }
                }
                .wrap()
        output.use { it.flush() }
        assertEquals(1, flushes)
        assertEquals(1, closes)
    }

    @Test
    fun outputRejectsInvalidSliceWithoutWriting() {
        val destination = ByteArrayOutputStream()
        val output = destination.wrap()
        assertFailsWith<IndexOutOfBoundsException> { output.write(byteArrayOf(1), 1, 1) }
        assertEquals(0, destination.size())
    }

    @Test
    fun outputValidatesBoundsBeforeCallingDelegate() {
        var writes = 0
        val output =
            object : java.io.OutputStream() {
                    override fun write(value: Int) {
                        writes++
                    }

                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        writes++
                    }
                }
                .wrap()
        output.use {
            for ((offset, length) in listOf(-1 to 1, 0 to -1, 5 to 0, 3 to 2, 1 to Int.MAX_VALUE)) {
                assertFailsWith<IndexOutOfBoundsException>("offset=$offset length=$length") {
                    it.write(ByteArray(4), offset, length)
                }
            }
            assertEquals(0, writes)
            it.write(ByteArray(4), 4, 0)
            assertEquals(1, writes)
        }
    }

    @Test
    fun closedOutputRejectsWritesAndFlushThroughBothViews() = runBlocking {
        var closes = 0
        val destination =
            object : ByteArrayOutputStream() {
                override fun close() {
                    closes++
                }
            }
        val output = destination.wrap()
        output.write(1.toByte())
        output.asAsync().close()
        output.close()
        assertEquals(1, closes)
        assertFailsWith<IllegalStateException> { output.write(2.toByte()) }
        assertFailsWith<IllegalStateException> { output.write(byteArrayOf(2)) }
        assertFailsWith<IllegalStateException> { output.write(byteArrayOf()) }
        assertFailsWith<IllegalStateException> { output.flush() }
        assertFailsWith<IllegalStateException> { output.asAsync().write(2.toByte()) }
        assertFailsWith<IllegalStateException> { output.asAsync().write(byteArrayOf(2)) }
        assertFailsWith<IllegalStateException> { output.asAsync().flush() }
        assertContentEquals(byteArrayOf(1), destination.toByteArray())
    }

    @Test
    fun outputRemainsClosedWhenDelegateCloseFails() {
        var closes = 0
        val failure = IOException("close failure")
        val output =
            object : java.io.OutputStream() {
                    override fun write(value: Int) = Unit

                    override fun close() {
                        closes++
                        throw failure
                    }
                }
                .wrap()
        assertSame(failure, assertFailsWith<IOException> { output.close() })
        output.close()
        assertEquals(1, closes)
        assertFailsWith<IllegalStateException> { output.write(1.toByte()) }
        assertFailsWith<IllegalStateException> { output.flush() }
    }
}
