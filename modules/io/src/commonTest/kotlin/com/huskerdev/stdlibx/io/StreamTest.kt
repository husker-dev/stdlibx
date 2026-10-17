package com.huskerdev.stdlibx.io

import kotlin.test.*

class StreamTest {
    @Test
    fun listStreamsAreGenericSinglePassAndCloseable() {
        val stream = SyncStream.OfList(listOf(1, 2, 3))
        val generic: Stream<Number> = stream
        assertSame(stream, generic)
        assertTrue(stream.hasNext())
        assertTrue(stream.hasNext())
        assertEquals(1, stream.next())
        assertEquals(listOf(2, 3), stream.asSequence().toList())
        assertFalse(stream.hasNext())
        assertFailsWith<NoSuchElementException> { stream.next() }
        stream.close()
        stream.close()
        assertFalse(stream.hasNext())
        assertFailsWith<IllegalStateException> { stream.next() }
    }

    @Test
    fun closingListStreamDiscardsRemainingValues() {
        val stream = SyncStream.OfList(listOf("first", "second"))
        assertEquals("first", stream.next())
        stream.close()
        assertFalse(stream.hasNext())
        assertFailsWith<IllegalStateException> { stream.next() }
    }
}
