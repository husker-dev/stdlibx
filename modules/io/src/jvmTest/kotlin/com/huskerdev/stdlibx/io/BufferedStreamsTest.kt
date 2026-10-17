package com.huskerdev.stdlibx.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.*

class BufferedStreamsTest {
    private class Input(data: ByteArray, private val maxRead: Int = Int.MAX_VALUE) :
        ByteArrayInputStream(data) {
        var reads = 0
        var closes = 0

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            reads++
            return super.read(bytes, offset, minOf(length, maxRead))
        }

        override fun close() {
            closes++
        }
    }

    private class Output : ByteArrayOutputStream() {
        var writes = 0
        var flushes = 0
        var closes = 0

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writes++
            super.write(bytes, offset, length)
        }

        override fun flush() {
            flushes++
        }

        override fun close() {
            closes++
        }
    }

    private fun SyncInputStream.asyncOnly(): AsyncInputStream =
        object : AsyncInputStream {
            override fun asSync(): SyncInputStream? = null

            override suspend fun hasNext(): Boolean {
                yield()
                return this@asyncOnly.hasNext()
            }

            override suspend fun read(): Byte {
                yield()
                return this@asyncOnly.read()
            }

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                yield()
                return this@asyncOnly.read(buffer, offset, length)
            }

            override suspend fun availableBytes(): Long {
                yield()
                return this@asyncOnly.availableBytes()
            }

            override suspend fun close() {
                yield()
                this@asyncOnly.close()
            }
        }

    private fun SyncOutputStream.asyncOnly(): AsyncOutputStream =
        object : AsyncOutputStream {
            override fun asSync(): SyncOutputStream? = null

            override suspend fun write(byte: Byte) {
                yield()
                this@asyncOnly.write(byte)
            }

            override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
                yield()
                this@asyncOnly.write(bytes, offset, length)
            }

            override suspend fun flush() {
                yield()
                this@asyncOnly.flush()
            }

            override suspend fun close() {
                yield()
                this@asyncOnly.close()
            }
        }

    @Test
    fun syncInputBatchesReadsAndPreservesLookahead() {
        val source = Input(byteArrayOf(1, 2, 3, 4, 5))
        source.wrap().buffered(4).use { input ->
            assertTrue(input.hasNext())
            assertTrue(input.hasNext())
            assertEquals(1, source.reads)
            assertEquals(5L, input.availableBytes())
            repeat(4) { assertEquals((it + 1).toByte(), input.read()) }
            assertEquals(1, source.reads)
            assertEquals(5.toByte(), input.read())
            assertFalse(input.hasNext())
            val reads = source.reads
            assertFalse(input.hasNext())
            assertEquals(0, input.read(ByteArray(1)))
            assertEquals(reads, source.reads)
            assertFailsWith<NoSuchElementException> { input.read() }
        }
        assertEquals(1, source.closes)
    }

    @Test
    fun inputSlicesAndLargeReadsPreserveContent() {
        val data = ByteArray(37) { it.toByte() }
        val source = Input(data, maxRead = 3)
        source.wrap().buffered(4).use { input ->
            val bytes = ByteArray(6) { 99 }
            assertEquals(2, input.read(bytes, 2, 2))
            assertContentEquals(byteArrayOf(99, 99, 0, 1, 99, 99), bytes)
            assertContentEquals(data.copyOfRange(2, data.size), input.readAllBytes())
        }
        val large = Input(data)
        large.wrap().buffered(4).use { input ->
            val bytes = ByteArray(data.size)
            assertEquals(data.size, input.read(bytes))
            assertContentEquals(data, bytes)
            assertEquals(1, large.reads)
        }
    }

    @Test
    fun inputInvalidAndEmptyReadsDoNotTouchSource() {
        val source = Input(byteArrayOf(1))
        val input = source.wrap().buffered(4)
        assertEquals(0, input.read(ByteArray(2), 2, 0))
        assertFailsWith<IndexOutOfBoundsException> { input.read(ByteArray(2), 1, Int.MAX_VALUE) }
        assertEquals(0, source.reads)
        input.close()
        input.close()
        assertEquals(1, source.closes)
        assertFailsWith<IllegalStateException> { input.hasNext() }
        assertFailsWith<IllegalStateException> { input.availableBytes() }
        assertFailsWith<IllegalStateException> { input.read(ByteArray(0)) }
    }

    @Test
    fun inputViewsSharePositionAndClose() = runBlocking {
        for (asyncWrapper in listOf(false, true)) {
            val source = Input(byteArrayOf(1, 2, 3, 4, 5))
            val async =
                if (asyncWrapper) source.wrap().asAsync().buffered(4)
                else source.wrap().buffered(4).asAsync()
            val sync = assertNotNull(async.asSync())
            val roundTrip = sync.asAsync()
            assertTrue(async.hasNext())
            assertEquals(1.toByte(), sync.read())
            assertEquals(2.toByte(), roundTrip.read())
            assertEquals(2L, sync.skip(2))
            assertEquals(5.toByte(), async.read())
            sync.close()
            async.close()
            assertEquals(1, source.closes)
            assertFailsWith<IllegalStateException> { async.read() }
        }
    }

    @Test
    fun asyncOnlyInputSupportsSuspendingReadsSkipAndClose() = runBlocking {
        val source = Input(ByteArray(17) { it.toByte() }, maxRead = 3)
        val input = source.wrap().asyncOnly().buffered(4)
        assertNull(input.asSync())
        input.use {
            assertTrue(it.hasNext())
            assertEquals(0.toByte(), it.read())
            assertEquals(5L, it.skip(5))
            assertContentEquals(ByteArray(11) { (it + 6).toByte() }, it.readAllBytes())
            assertEquals(0L, it.skip(100))
        }
        input.close()
        assertEquals(1, source.closes)
    }

    @Test
    fun syncOutputBatchesAndFlushesPendingBytes() {
        val destination = Output()
        destination.wrap().buffered(4).use { output ->
            repeat(3) { output.write(it.toByte()) }
            assertEquals(0, destination.writes)
            output.write(3.toByte())
            assertEquals(1, destination.writes)
            output.write(byteArrayOf(99, 4, 5, 99), 1, 2)
            assertEquals(4, destination.size())
            output.flush()
            assertEquals(1, destination.flushes)
            assertContentEquals(ByteArray(6) { it.toByte() }, destination.toByteArray())
            output.write(6.toByte())
        }
        assertContentEquals(ByteArray(7) { it.toByte() }, destination.toByteArray())
        assertEquals(1, destination.closes)
    }

    @Test
    fun outputLargeWritesFollowPendingBytes() {
        val destination = Output()
        destination.wrap().buffered(4).use { output ->
            output.write(byteArrayOf(0, 1))
            output.write(ByteArray(10) { (it + 2).toByte() })
            assertEquals(2, destination.writes)
            assertContentEquals(ByteArray(12) { it.toByte() }, destination.toByteArray())
        }
    }

    @Test
    fun outputViewsShareBufferAndClosedState() = runBlocking {
        for (asyncWrapper in listOf(false, true)) {
            val destination = Output()
            val async =
                if (asyncWrapper) destination.wrap().asAsync().buffered(4)
                else destination.wrap().buffered(4).asAsync()
            val sync = assertNotNull(async.asSync())
            val roundTrip = sync.asAsync()
            async.write(1.toByte())
            sync.write(2.toByte())
            assertEquals(0, destination.size())
            roundTrip.flush()
            sync.write(3.toByte())
            async.close()
            sync.close()
            assertContentEquals(byteArrayOf(1, 2, 3), destination.toByteArray())
            assertEquals(1, destination.closes)
            assertFailsWith<IllegalStateException> { sync.write(4.toByte()) }
            assertFailsWith<IllegalStateException> { async.write(byteArrayOf()) }
            assertFailsWith<IllegalStateException> { async.flush() }
        }
    }

    @Test
    fun asyncOnlyOutputFlushesOnSuspendClose() = runBlocking {
        val destination = Output()
        val output = destination.wrap().asyncOnly().buffered(4)
        assertNull(output.asSync())
        output.use {
            it.write(byteArrayOf(1, 2))
            assertEquals(0, destination.size())
        }
        output.close()
        assertContentEquals(byteArrayOf(1, 2), destination.toByteArray())
        assertEquals(1, destination.closes)
    }

    @Test
    fun outputClosesSourceEvenWhenEmissionFails() = runBlocking {
        for (async in listOf(false, true)) {
            val writeFailure = IOException("write")
            val closeFailure = IOException("close")
            var closes = 0
            val source =
                object : java.io.OutputStream() {
                        override fun write(value: Int): Unit = throw writeFailure

                        override fun close() {
                            closes++
                            throw closeFailure
                        }
                    }
                    .wrap()
            if (async) {
                val output = source.asyncOnly().buffered(4)
                output.write(1.toByte())
                assertSame(writeFailure, assertFailsWith<IOException> { output.close() })
                output.close()
            } else {
                val output = source.buffered(4)
                output.write(1.toByte())
                assertSame(writeFailure, assertFailsWith<IOException> { output.close() })
                output.close()
            }
            assertEquals(1, closes)
            assertContentEquals(arrayOf(closeFailure), writeFailure.suppressed)
        }
    }

    @Test
    fun invalidBufferSizesAndSlicesFailWithoutWrites() {
        val source = Input(byteArrayOf(1)).wrap()
        val destination = Output()
        val output = destination.wrap()
        for (size in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { source.buffered(size) }
            assertFailsWith<IllegalArgumentException> { source.asAsync().buffered(size) }
            assertFailsWith<IllegalArgumentException> { output.buffered(size) }
            assertFailsWith<IllegalArgumentException> { output.asAsync().buffered(size) }
        }
        output.buffered(4).use {
            assertFailsWith<IndexOutOfBoundsException> {
                it.write(byteArrayOf(1), 1, Int.MAX_VALUE)
            }
            it.write(byteArrayOf())
        }
        assertEquals(0, destination.writes)
    }

    @Test
    fun skipReturnsActualCountAndHandlesEof() = runBlocking {
        for (buffered in listOf(false, true)) {
            val sync =
                Input(byteArrayOf(1, 2, 3)).wrap().let { if (buffered) it.buffered(2) else it }
            assertEquals(0L, sync.skip(0))
            assertEquals(2L, sync.skip(2))
            assertEquals(1L, sync.skip(Long.MAX_VALUE))
            assertEquals(0L, sync.skip(1))
            assertFailsWith<IllegalArgumentException> { sync.skip(-1) }
            sync.close()
            val async =
                Input(byteArrayOf(1, 2, 3)).wrap().asyncOnly().let {
                    if (buffered) it.buffered(2) else it
                }
            async.use {
                assertEquals(0L, it.skip(0))
                assertEquals(2L, it.skip(2))
                assertEquals(1L, it.skip(Long.MAX_VALUE))
                assertEquals(0L, it.skip(1))
                assertFailsWith<IllegalArgumentException> { it.skip(-1) }
            }
        }
    }

    @Test
    fun copyToPreservesShortReadsAndDoesNotCloseOrFlush() = runBlocking {
        for (size in listOf(0, 1, 37, 20000)) {
            val data = ByteArray(size) { it.toByte() }
            for (async in listOf(false, true)) {
                val source = Input(data, maxRead = 3)
                val destination = Output()
                if (async) {
                    val input = source.wrap().asyncOnly()
                    val output = destination.wrap().asyncOnly()
                    assertEquals(size.toLong(), input.copyTo(output, 7))
                } else {
                    assertEquals(size.toLong(), source.wrap().copyTo(destination.wrap(), 7))
                }
                assertContentEquals(data, destination.toByteArray())
                assertEquals(0, source.closes)
                assertEquals(0, destination.closes)
                assertEquals(0, destination.flushes)
            }
        }
    }

    @Test
    fun copyToBufferedOutputLeavesFinalFlushToCaller() = runBlocking {
        val data = ByteArray(17) { it.toByte() }
        val destination = Output()
        Input(data).wrap().asyncOnly().buffered(4).use { input ->
            destination.wrap().asyncOnly().buffered(8).use { output ->
                assertEquals(17L, input.copyTo(output, 3))
                assertTrue(destination.size() < data.size)
            }
        }
        assertContentEquals(data, destination.toByteArray())
    }

    @Test
    fun copyToRejectsInvalidBufferSizeBeforeReading() = runBlocking {
        val source = Input(byteArrayOf(1))
        val destination = Output()
        for (size in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> {
                source.wrap().copyTo(destination.wrap(), size)
            }
            assertFailsWith<IllegalArgumentException> {
                source.wrap().asyncOnly().copyTo(destination.wrap().asyncOnly(), size)
            }
        }
        assertEquals(0, source.reads)
        assertEquals(0, destination.writes)
    }

    @Test
    fun asyncUsePreservesBodyFailureAndSuppressesCloseFailure() = runBlocking {
        val bodyFailure = IOException("body")
        val closeFailure = IOException("close")
        val resource =
            object : AsyncCloseable {
                override suspend fun close() {
                    yield()
                    throw closeFailure
                }
            }
        assertSame(bodyFailure, assertFailsWith<IOException> { resource.use { throw bodyFailure } })
        assertContentEquals(arrayOf(closeFailure), bodyFailure.suppressed)
        val thrown = assertFailsWith<IOException> { resource.use { 42 } }
        // Coroutine stack-trace recovery may copy an exception across suspension boundaries.
        assertEquals(closeFailure.message, thrown.message)
        assertTrue(thrown === closeFailure || thrown.cause === closeFailure)
    }

    @Test
    fun asyncUseClosesInCancelledCoroutine() = runBlocking {
        var closed = false
        val resource =
            object : AsyncCloseable {
                override suspend fun close() {
                    yield()
                    closed = true
                }
            }
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                resource.use { awaitCancellation() }
            }
        job.cancelAndJoin()
        assertTrue(closed)
    }

    @Test
    fun asyncUseEmitsBufferedOutputOnCancellation() = runBlocking {
        val destination = Output()
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                destination.wrap().asyncOnly().buffered(4).use {
                    it.write(byteArrayOf(1, 2))
                    awaitCancellation()
                }
            }
        job.cancelAndJoin()
        assertContentEquals(byteArrayOf(1, 2), destination.toByteArray())
        assertEquals(1, destination.closes)
    }
}
