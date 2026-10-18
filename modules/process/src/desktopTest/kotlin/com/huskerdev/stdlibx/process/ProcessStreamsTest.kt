package com.huskerdev.stdlibx.process

import kotlin.test.*

class ProcessStreamsTest {
    private class Input(private val data: ByteArray) : ProcessPipeInput() {
        var reads = 0
        var closes = 0
        private var position = 0

        override fun readNative(buffer: ByteArray, offset: Int, length: Int): Int {
            reads++
            val count = minOf(length, 2, data.size - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun availableNative(): Long = (data.size - position).toLong()

        override fun closeNative() {
            closes++
        }
    }

    private class Output(private val maxWrite: Int = 2) : ProcessPipeOutput() {
        val data = mutableListOf<Byte>()
        var writes = 0
        var closes = 0

        override fun writeNative(bytes: ByteArray, offset: Int, length: Int): Int {
            writes++
            val count = minOf(maxWrite, length)
            repeat(count) { data += bytes[offset + it] }
            return count
        }

        override fun closeNative() {
            closes++
        }
    }

    @Test
    fun inputCachesUnsignedLookaheadAndReturnsItWithoutAnotherRead() {
        val input = Input(byteArrayOf(-1, 0, 1, 2))
        assertTrue(input.hasNext())
        assertTrue(input.hasNext())
        assertEquals(1, input.reads)
        assertEquals(4L, input.availableBytes())
        val buffer = ByteArray(8) { 99 }
        assertEquals(1, input.read(buffer, 2, 4))
        assertEquals((-1).toByte(), buffer[2])
        assertEquals(99.toByte(), buffer[3])
        assertEquals(1, input.reads)
        assertContentEquals(byteArrayOf(0, 1, 2), input.readAllBytes())
        assertFalse(input.hasNext())
        val reads = input.reads
        assertFalse(input.hasNext())
        assertEquals(0, input.read(buffer))
        assertEquals(reads, input.reads)
        assertFailsWith<NoSuchElementException> { input.read() }
        input.close()
    }

    @Test
    fun inputValidatesBeforeTouchingThePipeAndSharesCloseState() {
        val input = Input(byteArrayOf(1))
        assertEquals(0, input.read(ByteArray(0)))
        assertFailsWith<IndexOutOfBoundsException> { input.read(ByteArray(2), -1, 1) }
        assertFailsWith<IndexOutOfBoundsException> { input.read(ByteArray(2), 1, Int.MAX_VALUE) }
        assertEquals(0, input.reads)
        assertSame(input, input.asAsync().asSync())
        input.close()
        input.close()
        assertEquals(1, input.closes)
        assertFailsWith<IllegalStateException> { input.hasNext() }
        assertFailsWith<IllegalStateException> { input.read(ByteArray(0)) }
        assertFailsWith<IllegalStateException> { input.availableBytes() }
    }

    @Test
    fun outputHandlesPartialWritesAndValidatedSlices() {
        val output = Output()
        output.write(byteArrayOf(99, 0, 1, 2, 3, 4, 99), 1, 5)
        assertContentEquals(byteArrayOf(0, 1, 2, 3, 4), output.data.toByteArray())
        assertEquals(3, output.writes)
        output.write(5)
        output.flush()
        assertContentEquals(byteArrayOf(0, 1, 2, 3, 4, 5), output.data.toByteArray())
        val writes = output.writes
        output.write(ByteArray(0))
        assertFailsWith<IndexOutOfBoundsException> { output.write(ByteArray(2), 1, Int.MAX_VALUE) }
        assertEquals(writes, output.writes)
        assertSame(output, output.asAsync().asSync())
        output.close()
        output.close()
        assertEquals(1, output.closes)
        assertFailsWith<IllegalStateException> { output.write(ByteArray(0)) }
        assertFailsWith<IllegalStateException> { output.flush() }
    }

    @Test
    fun aStalledWriteFailsInsteadOfLoopingForever() {
        val output = Output(maxWrite = 0)
        assertFailsWith<ProcessException> { output.write(1) }
        assertEquals(1, output.writes)
        output.close()
    }

    @Test
    fun closeIsNotRetriedAfterNativeFailure() {
        var closes = 0
        val failure = ProcessException("close failed")
        val input =
            object : ProcessPipeInput() {
                override fun readNative(buffer: ByteArray, offset: Int, length: Int): Int =
                    error("Unexpected read")

                override fun closeNative() {
                    closes++
                    throw failure
                }
            }
        assertSame(failure, assertFailsWith<ProcessException> { input.close() })
        input.close()
        assertEquals(1, closes)
        assertFailsWith<IllegalStateException> { input.read() }
    }

    @Test
    fun pathLookupPreservesEmptyEntriesAndOnlyUnquotesWindowsPaths() {
        assertEquals(
            listOf("tool", "/bin/tool", "tool"),
            processExecutablePaths("tool", false) { ":/bin:" },
        )
        assertEquals(
            listOf("tool", "C:\\Program Files\\tool", "tool"),
            processExecutablePaths("tool", true) { ";\"C:\\Program Files\";" },
        )
        assertEquals(listOf("\"/bin\"/tool"), processExecutablePaths("tool", false) { "\"/bin\"" })
        assertEquals(listOf("/bin/a\\b"), processExecutablePaths("a\\b", false) { "/bin" })
    }

    @Test
    fun explicitPathsNeverInspectTheEnvironment() {
        for ((path, windows) in
            listOf("./tool" to false, "/bin/tool" to false, "C:tool" to true, "\\tool" to true)) {
            assertEquals(
                listOf(path),
                processExecutablePaths(path, windows) { error("Unexpected PATH lookup") },
            )
        }
    }
}
