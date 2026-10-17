package com.huskerdev.stdlibx.io.fs

import com.huskerdev.stdlibx.io.SyncInputStream
import com.huskerdev.stdlibx.io.SyncStream
import kotlin.random.Random
import kotlin.test.*

class WholeFileTest {
    private val fs
        get() = checkNotNull(FileSystem.default?.asSync())

    private fun withFile(action: (SyncPath) -> Unit) {
        val directory =
            fs.tmpDir()
                .resolve("os-utils-whole-${Random.nextLong().toULong().toString(16)}")
                .createDirectory()
        val file = directory.resolve("file")
        try {
            action(file)
        } finally {
            file.deleteIfExists()
            directory.deleteExisting()
        }
    }

    @Test
    fun wholeBytesCreateReplaceAndEmpty() = withFile { file ->
        val bytes = ByteArray(50000) { it.toByte() }
        assertEquals(file, file.writeBytes(bytes))
        assertContentEquals(bytes, file.readBytes())
        assertContentEquals(bytes, fs.readBytes(file))
        file.writeBytes(byteArrayOf(1, 2))
        assertContentEquals(byteArrayOf(1, 2), file.readBytes())
        file.writeBytes(ByteArray(0))
        assertEquals(0L, file.size())
        assertContentEquals(ByteArray(0), file.readBytes())
    }

    @Test
    fun wholeStringsAreStrictUtf8() = withFile { file ->
        val text = "Привет, 世界 😀\u0000\nline\r\n".repeat(300)
        file.writeString(text)
        assertEquals(text, file.readString())
        assertContentEquals(text.encodeToByteArray(), file.readBytes())
        file.writeString("")
        assertEquals("", file.readString())
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertFails { file.readString() }
        // Construct at runtime: Kotlin/JS may replace an unpaired surrogate in a string literal.
        val malformed = CharArray(1) { 0xD800.toChar() }.concatToString()
        assertFails { file.writeString(malformed) }
    }

    @Test
    fun writeModesAndCreationAreIndependent() = withFile { file ->
        assertTrue(file.notExist())
        assertFails {
            file.writeBytes(byteArrayOf(1), creation = FileCreation.OPEN_EXISTING)
        }
        file.writeBytes(byteArrayOf(1, 2, 3), creation = FileCreation.CREATE_NEW)
        assertFails {
            file.writeBytes(byteArrayOf(9), creation = FileCreation.CREATE_NEW)
        }
        assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())
        file.writeBytes(
            byteArrayOf(4),
            mode = FileWriteMode.FROM_START,
            creation = FileCreation.OPEN_EXISTING,
        )
        assertContentEquals(byteArrayOf(4, 2, 3), file.readBytes())
        file
            .newOutputStream(mode = FileWriteMode.APPEND, creation = FileCreation.OPEN_EXISTING)
            .use {
                it.write(byteArrayOf(5))
            }
        assertContentEquals(byteArrayOf(4, 2, 3, 5), file.readBytes())
        file.writeBytes(byteArrayOf(6), mode = FileWriteMode.APPEND)
        assertContentEquals(byteArrayOf(4, 2, 3, 5, 6), file.readBytes())
        file.writeBytes(ByteArray(0), creation = FileCreation.OPEN_EXISTING)
        assertEquals(0L, file.size())
        assertFalse(file.notExist())
    }

    @Test
    fun viewsShareFilesystemIdentityAndLexicalEquality() = withFile { file ->
        val async = file.asAsync()
        assertSame(file.fileSystem.identity, async.fileSystem.identity)
        assertEquals<Path>(file, async)
        assertEquals(file.hashCode(), async.hashCode())
        assertEquals(file, async.asSync())
    }

    @Test
    fun attributeSnapshotsDoNotChangeAfterWrites() = withFile { file ->
        file.writeBytes(byteArrayOf(1))
        val before = file.readAttributes()
        val changed = kotlin.time.Instant.fromEpochSeconds(1_600_000_000)
        file.writeAttributes(FileAttributesChanges(lastModifiedTime = changed))
        assertEquals(changed, file.readAttributes().lastModifiedTime)
        assertNotEquals(changed, before.lastModifiedTime)
        assertTrue(file.isReadable())
        assertTrue(file.isWritable())
    }

    @Test
    fun wholeLinesHaveNioTerminatorSemantics() = withFile { file ->
        val cases =
            mapOf(
                "" to emptyList(),
                "a" to listOf("a"),
                "a\n" to listOf("a"),
                "\n" to listOf(""),
                "\r\n" to listOf(""),
                "a\n\n" to listOf("a", ""),
                "a\rb\r\nc\nd" to listOf("a", "b", "c", "d"),
            )
        for ((text, lines) in cases) {
            file.writeString(text)
            assertEquals(lines, file.readLines(), text)
            file.lines().use { assertEquals(lines, it.asSequence().toList(), text) }
            fs.lines(file).use { assertEquals(lines, it.asSequence().toList(), text) }
        }
        val lines = listOf("Привет", "", "😀")
        file.writeLines(lines)
        val separator = if (fs.separator == "\\") "\r\n" else "\n"
        assertEquals(lines.joinToString(separator, postfix = separator), file.readString())
        assertEquals(lines, file.readLines())
        file.writeLines(emptyList())
        assertEquals("", file.readString())
    }

    @Test
    fun lazyLinesHandleUnicodeAndSeparatorsAcrossBufferBoundaries() = withFile { file ->
        for (prefix in listOf(8190, 8191, 8192, 8193, 50000)) {
            val lines = listOf("a".repeat(prefix) + "😀Привет世界", "", "tail")
            file.writeString(lines.joinToString("\r\n", postfix = "\r\n"))
            file.lines().use { assertEquals(lines, it.asSequence().toList()) }
            file.writeString("a".repeat(prefix) + "\r\nend")
            file.lines().use {
                assertEquals(listOf("a".repeat(prefix), "end"), it.asSequence().toList())
            }
        }
    }

    @Test
    fun lazyLinesDoNotReadUntilIterationAndCloseAfterPartialConsumption() = withFile { file ->
        file.writeString("first\n" + "second".repeat(10000))
        var reads = 0
        var closes = 0
        val tracked =
            object : SyncFileSystem by fs {
                override fun lines(path: Path): SyncStream<String> =
                    super<SyncFileSystem>.lines(path)

                override fun newInputStream(path: Path): SyncInputStream {
                    val input = fs.newInputStream(path)
                    return object : SyncInputStream by input {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            reads++
                            return input.read(buffer, offset, length)
                        }

                        override fun close() {
                            closes++
                            input.close()
                        }
                    }
                }
            }
        val stream = tracked.lines(file)
        assertEquals(0, reads)
        stream.use {
            assertTrue(it.hasNext())
            assertTrue(it.hasNext())
            assertEquals("first", it.next())
            assertEquals(1, reads)
        }
        stream.close()
        assertEquals(1, closes)
        assertFalse(stream.hasNext())
        assertFailsWith<IllegalStateException> { stream.next() }
    }

    @Test
    fun lazyLinesReportMalformedUtf8OnlyWhenReached() = withFile { file ->
        file.writeBytes("valid\n".encodeToByteArray() + byteArrayOf(0xc3.toByte(), 0x28))
        file.lines().use {
            assertEquals("valid", it.next())
            assertFailsWith<FileSystemException> { it.hasNext() }
        }
        file.writeString("")
        file.lines().use {
            assertFalse(it.hasNext())
            assertFailsWith<NoSuchElementException> { it.next() }
        }
        file.deleteExisting()
        assertFailsWith<NoSuchFileException> { file.lines() }
    }

    @Test
    fun rejectsForeignPaths() = withFile { file ->
        val other =
            object : com.huskerdev.stdlibx.io.fs.unix.UnixFileSystem, FileSystem by fs {
                override val identity: Any = Any()

                override fun path(vararg path: String) =
                    com.huskerdev.stdlibx.io.fs.unix.UnixPath(path.joinToString("/"), this)
            }
        val path = com.huskerdev.stdlibx.io.fs.unix.UnixPath(file.pathString, other)
        assertFailsWith<IllegalArgumentException> { fs.readBytes(path) }
        assertFailsWith<IllegalArgumentException> {
            fs.writeBytes(
                path,
                byteArrayOf(1),
                FileWriteMode.TRUNCATE,
                FileCreation.CREATE_IF_MISSING,
            )
        }
    }

    @Test
    fun nativeWholeReaderHandlesShortReadsAndChangingSize() {
        val path = fs.path("whole-reader")
        val expected = ByteArray(100) { it.toByte() }
        for (size in listOf(0L, 1L, 2L, 100L, 200L)) {
            var position = 0
            val actual =
                readWholeFileBytes(path, size) { bytes, offset, length ->
                    val count = minOf(length, 3, expected.size - position)
                    expected.copyInto(bytes, offset, position, position + count)
                    position += count
                    count
                }
            assertContentEquals(expected, actual, "size hint $size")
        }
        assertFailsWith<FileSystemException> {
            readWholeFileBytes(path, Int.MAX_VALUE.toLong() + 1) { _, _, _ -> 0 }
        }
        assertContentEquals(ByteArray(0), readWholeFileBytes(path, 0) { _, _, _ -> 0 })
    }
}
