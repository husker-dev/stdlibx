package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.io.*
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Public API checks shared by disk and browser providers; no platform-specific exceptions. */
internal class AsyncFileSystemContract(private val base: AsyncPath) {
    private suspend fun isolated(block: suspend (AsyncPath) -> Unit) {
        val root =
            base
                .resolve("stdlibx-contract-${Random.nextLong().toULong().toString(16)}")
                .createDirectory()
        try {
            block(root)
        } finally {
            withContext(NonCancellable) { root.deleteRecursively() }
        }
    }

    suspend fun creationAndWriteModes() = isolated { root ->
        val missing = root.resolve("missing")
        assertFailsWith<NoSuchFileException> { missing.readBytes() }
        assertFailsWith<NoSuchFileException> { missing.readAttributes() }
        assertFailsWith<NoSuchFileException> { missing.newInputStream() }
        assertFailsWith<NoSuchFileException> { missing.deleteExisting() }
        assertFailsWith<NoSuchFileException> { missing.streamDirectory() }
        assertFailsWith<NoSuchFileException> { missing.realPath() }
        assertFailsWith<NoSuchFileException> { missing.copyTo(root.resolve("copy-missing")) }
        assertFailsWith<NoSuchFileException> { missing.moveTo(root.resolve("move-missing")) }
        assertFailsWith<NoSuchFileException> {
            missing.newOutputStream(creation = FileCreation.OPEN_EXISTING)
        }
        val parentFile = root.resolve("parent-file").writeString("file")
        try {
            parentFile.readSymbolicLink()
            fail("Reading a non-link must fail")
        } catch (error: NotLinkException) {
            assertEquals(parentFile, error.path)
        } catch (_: UnsupportedOperationException) {
            /* This provider has no symbolic links. */
        }
        assertFailsWith<FileAlreadyExistsException> { parentFile.createFile() }
        assertFailsWith<FileAlreadyExistsException> { parentFile.createDirectory() }
        assertFailsWith<FileAlreadyExistsException> { parentFile.createDirectories() }
        assertFailsWith<FileSystemException> { parentFile.streamDirectory() }
        val blocked = parentFile.resolve("child")
        assertFailsWith<FileSystemException> { blocked.readAttributes() }
        assertFailsWith<FileSystemException> { blocked.newInputStream() }
        assertFailsWith<FileSystemException> { blocked.newOutputStream() }
        assertFalse(blocked.exists())
        // A provider may report this unresolvable path as absent or as an unknown status.
        val permissions = parentFile.readAttributes().posixPermissions
        if (permissions != null) {
            try {
                parentFile.writeAttributes(FileAttributesChanges(posixPermissions = emptySet()))
                // Elevated test users may still be allowed to read mode-000 files.
                if (!parentFile.isReadable()) {
                    assertFailsWith<AccessDeniedException> { parentFile.newInputStream() }
                    assertFailsWith<AccessDeniedException> { parentFile.readBytes() }
                }
            } finally {
                parentFile.writeAttributes(FileAttributesChanges(posixPermissions = permissions))
            }
        }
        val readOnly = parentFile.readAttributes().dosIsReadOnly
        if (root.fileSystem.separator == "\\" && readOnly != null) {
            try {
                parentFile.writeAttributes(FileAttributesChanges(dosIsReadOnly = true))
                assertFailsWith<AccessDeniedException> {
                    parentFile.newOutputStream(creation = FileCreation.OPEN_EXISTING).use {}
                }
            } finally {
                parentFile.writeAttributes(FileAttributesChanges(dosIsReadOnly = readOnly))
            }
        }
        for (mode in FileWriteMode.entries) {
            val file = root.resolve(mode.name)
            assertTrue(file.notExist())
            assertFailsWith<NoSuchFileException> {
                file.writeBytes(byteArrayOf(9), mode, FileCreation.OPEN_EXISTING)
            }
            assertTrue(file.notExist())
            file.writeBytes(byteArrayOf(1, 2, 3), mode, FileCreation.CREATE_NEW)
            assertFailsWith<FileAlreadyExistsException> {
                file.writeBytes(byteArrayOf(9), mode, FileCreation.CREATE_NEW)
            }
            assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())
            file.writeBytes(byteArrayOf(4), mode, FileCreation.OPEN_EXISTING)
            val expected =
                when (mode) {
                    FileWriteMode.FROM_START -> byteArrayOf(4, 2, 3)
                    FileWriteMode.TRUNCATE -> byteArrayOf(4)
                    FileWriteMode.APPEND -> byteArrayOf(1, 2, 3, 4)
                }
            assertContentEquals(expected, file.readBytes(), mode.name)
            file.writeBytes(ByteArray(0), mode, FileCreation.CREATE_IF_MISSING)
            assertContentEquals(
                if (mode == FileWriteMode.TRUNCATE) ByteArray(0) else expected,
                file.readBytes(),
            )
        }
    }

    suspend fun textAndWholeFiles() = isolated { root ->
        val file = root.resolve("данные-文件-😀")
        for (size in listOf(0, 1, 8191, 8192, 8193, 50000)) {
            val bytes = ByteArray(size) { (it * 31).toByte() }
            assertEquals(file, file.writeBytes(bytes))
            assertContentEquals(bytes, file.readBytes(), "size=$size")
            assertEquals(size.toLong(), file.size())
        }
        val text = "Привет 世界 😀\u0000".repeat(2000)
        file.writeString(text)
        assertEquals(text, file.readString())
        file.writeString("a\rb\r\nc\nd\n")
        assertEquals(listOf("a", "b", "c", "d"), file.readLines())
        val lineCases =
            listOf(
                "",
                "a",
                "\n",
                "\r",
                "\r\n",
                "a\n\n",
                "a\rb\r\nc\nd\n",
                "x".repeat(8191) + "\r\n😀\n",
                "x".repeat(8191) + "😀Привет世界\rtail",
                "z".repeat(50000),
            )
        for (text in lineCases) {
            file.writeString(text)
            val expected = file.readLines()
            file.lines().use { stream ->
                val actual = buildList {
                    while (stream.hasNext()) {
                        assertTrue(stream.hasNext())
                        add(stream.next())
                    }
                }
                assertEquals(expected, actual)
                assertFailsWith<NoSuchElementException> { stream.next() }
            }
        }
        file.writeBytes("valid\n".encodeToByteArray() + byteArrayOf(0xc3.toByte(), 0x28))
        val stream = file.fileSystem.lines(file)
        stream.use {
            assertEquals("valid", it.next())
            assertFailsWith<FileSystemException> { it.next() }
        }
        stream.close()
        assertFalse(stream.hasNext())
        assertFailsWith<IllegalStateException> { stream.next() }

        val values = AsyncStream.OfList(listOf(1, 2))
        assertTrue(values.hasNext())
        assertEquals(1, values.next())
        assertEquals(2, values.next())
        assertFalse(values.hasNext())
        assertFailsWith<NoSuchElementException> { values.next() }
        values.close()
        values.close()
        assertFalse(values.hasNext())
        assertFailsWith<IllegalStateException> { values.next() }
        file.writeLines(listOf("Привет", "", "😀"))
        assertEquals(listOf("Привет", "", "😀"), file.readLines())
        file.writeLines(emptyList())
        assertEquals("", file.readString())
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertFailsWith<FileSystemException> { file.readString() }
        assertFailsWith<IllegalArgumentException> {
            file.writeString(CharArray(1) { 0xd800.toChar() }.concatToString())
        }
    }

    suspend fun streamsAndBounds() = isolated { root ->
        val file = root.resolve("stream")
        val data = ByteArray(25001) { it.toByte() }
        file.newOutputStream().use { output ->
            output.write(data, 0, 1)
            output.write(data, 1, 8192)
            output.write(data, 8193, data.size - 8193)
            output.write(ByteArray(0))
            assertFailsWith<IndexOutOfBoundsException> { output.write(data, -1, 1) }
            assertFailsWith<IndexOutOfBoundsException> { output.write(data, 1, Int.MAX_VALUE) }
            output.flush()
        }
        assertContentEquals(data, file.readBytes())
        file.newInputStream().use { input ->
            assertEquals(0, input.read(ByteArray(0)))
            assertTrue(input.hasNext())
            assertTrue(input.hasNext())
            assertEquals(data[0], input.read())
            assertEquals(8192L, input.skip(8192))
            val buffer = ByteArray(9) { 99 }
            val count = input.read(buffer, 2, 5)
            assertTrue(count in 1..5)
            assertContentEquals(
                data.copyOfRange(8193, 8193 + count),
                buffer.copyOfRange(2, 2 + count),
            )
            assertEquals(99.toByte(), buffer.first())
            assertEquals(99.toByte(), buffer.last())
            assertContentEquals(data.copyOfRange(8193 + count, data.size), input.readAllBytes())
            assertFalse(input.hasNext())
            assertEquals(0, input.read(ByteArray(1)))
            assertEquals(0L, input.skip(Long.MAX_VALUE))
            assertFailsWith<NoSuchElementException> { input.read() }
        }
        val input = file.newInputStream()
        input.close()
        input.close()
        assertEquals(0L, input.skip(0))
        assertContentEquals(ByteArray(0), input.read(0))
        assertFailsWith<IllegalArgumentException> { input.skip(-1) }
        assertFailsWith<IllegalArgumentException> { input.read(-1) }
        assertFailsWith<IllegalStateException> { input.hasNext() }
        assertFailsWith<IllegalStateException> { input.read(ByteArray(0)) }
        assertFailsWith<IllegalStateException> { input.read(ByteArray(1)) }
        assertFailsWith<IllegalStateException> { input.availableBytes() }
        val output = file.newOutputStream()
        output.close()
        output.close()
        assertFailsWith<IllegalStateException> { output.write(ByteArray(0)) }
        assertFailsWith<IllegalStateException> { output.flush() }
        val entries = root.streamDirectory()
        entries.close()
        entries.close()
        assertFalse(entries.hasNext())
        assertFailsWith<IllegalStateException> { entries.next() }
    }

    suspend fun directoriesCopyMoveAndDelete() = isolated { root ->
        val directory = root.resolve("a/b").createDirectories()
        directory.createDirectories()
        val file = directory.resolve("file").writeString("original")
        assertTrue(directory.isDirectory()!!)
        assertTrue(file.isRegularFile()!!)
        assertFailsWith<FileAlreadyExistsException> { directory.createDirectory() }
        assertFailsWith<DirectoryNotEmptyException> { directory.deleteExisting() }
        directory.streamDirectory().use { stream ->
            assertTrue(stream.hasNext())
            assertTrue(stream.hasNext())
            assertEquals(file, stream.next())
            assertFalse(stream.hasNext())
        }
        assertEquals(setOf(file), directory.list().toSet())
        val copy = file.copyTo(root.resolve("copy"))
        assertFalse(file.isSameFileAs(copy))
        assertEquals("original", copy.readString())
        file.writeString("changed")
        assertEquals("original", copy.readString())
        assertFailsWith<FileAlreadyExistsException> { file.copyTo(copy) }
        assertEquals("original", copy.readString())
        file.copyTo(copy, overwrite = true)
        assertEquals("changed", copy.readString())
        val moved = copy.moveTo(root.resolve("moved"))
        assertTrue(copy.notExist())
        assertEquals("changed", moved.readString())
        assertTrue(moved.deleteIfExists())
        assertFalse(moved.deleteIfExists())
        directory.deleteRecursively()
        assertTrue(directory.notExist())
    }

    suspend fun snapshotsAndViews() = isolated { root ->
        val fs = root.fileSystem
        val file = root.resolve("file").writeBytes(byteArrayOf(1))
        val before = file.readAttributes()
        file.writeBytes(byteArrayOf(2, 3, 4))
        assertEquals(1L, before.size)
        assertEquals(3L, file.readAttributes().size)
        assertTrue(file.isReadable())
        assertTrue(file.isWritable())
        assertTrue(file.isSameFileAs(file))
        assertSame(fs.identity, fs.asAsync().identity)
        file.asSync()?.let { sync ->
            assertSame(fs.identity, sync.fileSystem.identity)
            assertEquals<Path>(file, sync)
            assertEquals(file.hashCode(), sync.hashCode())
            assertEquals<Path>(file, sync.asAsync())
            assertContentEquals(byteArrayOf(2, 3, 4), sync.readBytes())
        }
    }

    suspend fun walkingDepthAndControl() = isolated { root ->
        root.resolve("branch/deep").createDirectories().resolve("leaf").writeString("leaf")
        root.resolve("file").writeString("file")
        val events = mutableListOf<Pair<String, AsyncPath>>()
        root.walkTree(
            visitor =
                object : AsyncFileTreeVisitor {
                    override suspend fun preVisitDirectory(
                        path: AsyncPath,
                        attributes: FileAttributesSnapshot,
                    ): FileTreeVisitResult {
                        events += "pre" to path
                        return FileTreeVisitResult.CONTINUE
                    }

                    override suspend fun visit(
                        path: AsyncPath,
                        attributes: FileAttributesSnapshot,
                    ): FileTreeVisitResult {
                        events += "file" to path
                        return FileTreeVisitResult.CONTINUE
                    }

                    override suspend fun postVisitDirectory(
                        path: AsyncPath,
                        attributes: FileAttributesSnapshot,
                    ): FileTreeVisitResult {
                        events += "post" to path
                        return FileTreeVisitResult.CONTINUE
                    }
                }
        )
        assertEquals("pre" to root, events.first())
        assertEquals("post" to root, events.last())
        assertEquals(8, events.size)
        val stack = mutableListOf<AsyncPath>()
        for ((event, path) in events) when (event) {
            "pre" -> stack.add(path)
            "post" -> assertEquals(path, stack.removeAt(stack.lastIndex))
            else -> assertEquals(stack.last(), path.parent)
        }
        val limited = mutableListOf<AsyncPath>()
        root.walkTree(maxDepth = 0) { path, _ ->
            limited += path
            FileTreeVisitResult.CONTINUE
        }
        assertEquals(listOf(root), limited)
        val skipped = mutableListOf<AsyncPath>()
        root.walkTree { path, _ ->
            skipped += path
            if (path.name == "branch") FileTreeVisitResult.SKIP_SUBTREE
            else FileTreeVisitResult.CONTINUE
        }
        assertEquals(setOf(root, root.resolve("branch"), root.resolve("file")), skipped.toSet())
        var visits = 0
        root.walkTree { _, _ ->
            visits++
            FileTreeVisitResult.TERMINATE
        }
        assertEquals(1, visits)
        val loop = root.resolve("cycle")
        val supportsLinks =
            try {
                loop.createSymbolicLink(root)
                true
            } catch (_: UnsupportedOperationException) {
                false
            } catch (_: AccessDeniedException) {
                false
            }
        if (supportsLinks) {
            assertFailsWith<FileSystemLoopException> {
                root.walkTree(followLinks = true) { _, _ -> FileTreeVisitResult.CONTINUE }
            }
        }
    }
}
