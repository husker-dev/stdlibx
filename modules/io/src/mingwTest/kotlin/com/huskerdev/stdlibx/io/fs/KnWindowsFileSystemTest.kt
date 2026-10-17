@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io.fs

import com.huskerdev.stdlibx.io.fs.windows.WindowsPath
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.cinterop.*
import platform.windows.*

class KnWindowsFileSystemTest {
    private val fs = KnWindowsFileSystem()

    private fun withDirectory(block: (SyncPath) -> Unit) {
        val dir =
            fs.tmpDir()
                .resolve("os-utils-${Random.nextLong().toULong().toString(16)}")
                .createDirectory()
        try {
            block(dir)
        } finally {
            cleanup(dir)
        }
    }

    private fun cleanup(path: SyncPath) {
        if (!path.exists(false)) return
        val attributes = path.readAttributes(false)
        if (attributes.isDirectory == true) path.list().forEach(::cleanup)
        if (attributes.dosIsReadOnly == true)
            path.writeAttributes(FileAttributesChanges(dosIsReadOnly = false), false)
        path.deleteExisting()
    }

    private fun SyncPath.write(bytes: ByteArray) = newOutputStream().use { it.write(bytes) }

    private fun SyncPath.read() = newInputStream().use { it.readAllBytes() }

    @Test
    fun defaultFilesystemAndDirectories() {
        assertIs<KnWindowsFileSystem>(FileSystem.default)
        assertIs<WindowsPath>(fs.path("C:\\file"))
        assertTrue(fs.workingDir().isAbsolute)
        assertTrue(fs.tmpDir().isDirectory()!!)
        assertTrue(fs.homeDir()?.isAbsolute != false)
        assertTrue(fs.rootDirectories().any())
        assertTrue(fs.rootDirectories().all { it.isAbsolute && it.names.isEmpty() })
        assertSame(fs, fs.tmpDir().fileSystem)
    }

    @Test
    fun absoluteResolvesCurrentDriveAndDriveRelativePaths() {
        val working = fs.workingDir()
        val root = working.root!!
        assertEquals(working, fs.absolute(fs.path("")))
        assertEquals(working.resolve("file"), fs.absolute(fs.path("file")))
        assertEquals(root.resolve("file"), fs.absolute(fs.path("\\file")))
        val drive = root.pathString.take(2)
        assertEquals(working.resolve("file"), fs.absolute(fs.path("${drive}file")))
    }

    @Test
    fun creationAndShallowDirectoryCopy() = withDirectory { dir ->
        val file = dir.resolve("a/b/file")
        file.parent!!.createDirectories()
        file.parent!!.createDirectories()
        file.createFile()
        assertFailsWith<FileAlreadyExistsException> { file.createFile() }
        assertFailsWith<FileAlreadyExistsException> { file.createDirectories() }
        assertFailsWith<NoSuchFileException> { dir.resolve("missing/file").createFile() }
        assertFailsWith<UnsupportedOperationException> {
            dir.resolve("permissions").createFile(emptySet())
        }
        file.parent!!.copyTo(dir.resolve("copy"))
        assertTrue(dir.resolve("copy").isDirectory()!!)
        assertTrue(dir.resolve("copy").list().isEmpty())
        assertFailsWith<DirectoryNotEmptyException> { file.parent!!.deleteExisting() }
    }

    @Test
    fun unicodeNamesAndBulkStreams() = withDirectory { dir ->
        val file = dir.resolve("данные-文件-😀.bin")
        val data = ByteArray(50000) { it.toByte() }
        file.write(data)
        assertEquals(data.size.toLong(), file.size())
        file.newInputStream().use { input ->
            assertEquals(data.size.toLong(), input.availableBytes())
            assertTrue(input.hasNext())
            assertTrue(input.hasNext())
            assertEquals(data.size.toLong(), input.availableBytes())
            val buffer = ByteArray(10) { 99 }
            assertEquals(5, input.read(buffer, 2, 5))
            assertContentEquals(data.copyOfRange(0, 5), buffer.copyOfRange(2, 7))
            assertEquals(99.toByte(), buffer[0])
            assertEquals(7L, input.skip(7))
            assertContentEquals(data.copyOfRange(12, data.size), input.readAllBytes())
            assertEquals(0, input.read(buffer))
            assertFalse(input.hasNext())
            assertFailsWith<NoSuchElementException> { input.read() }
            assertFailsWith<IndexOutOfBoundsException> { input.read(buffer, 1, Int.MAX_VALUE) }
        }
        file.write(byteArrayOf(1, 2))
        assertContentEquals(byteArrayOf(1, 2), file.read())
    }

    @Test
    fun streamsRejectOperationsAfterClose() = withDirectory { dir ->
        val file = dir.resolve("closed")
        file.write(byteArrayOf(1))
        val input = file.newInputStream()
        input.hasNext()
        input.close()
        input.close()
        assertFailsWith<IllegalStateException> { input.hasNext() }
        assertFailsWith<IllegalStateException> { input.read(ByteArray(1)) }
        val output = file.newOutputStream()
        output.close()
        output.close()
        assertFailsWith<IllegalStateException> { output.write(1.toByte()) }
        assertFailsWith<IllegalStateException> { output.flush() }
    }

    @Test
    fun directoryStreamLifecycleAndOwnership() = withDirectory { dir ->
        dir.resolve("child").createFile()
        val stream = dir.streamDirectory()
        val iterator = stream
        assertTrue(iterator.hasNext())
        val child = iterator.next()
        assertEquals("child", child.name)
        assertSame(fs, child.fileSystem)
        assertFalse(iterator.hasNext())
        stream.close()
        stream.close()
        assertFalse(iterator.hasNext())
        assertFailsWith<IllegalStateException> { iterator.next() }
    }

    @Test
    fun copyOverwriteAndSameFileIdentity() = withDirectory { dir ->
        val source = dir.resolve("source")
        val data = ByteArray(20000) { it.toByte() }
        source.write(data)
        val target = dir.resolve("target")
        source.copyTo(target)
        assertContentEquals(data, target.read())
        assertFailsWith<FileAlreadyExistsException> { source.copyTo(target) }
        source.copyTo(target, overwrite = true)
        source.copyTo(source)
        assertContentEquals(data, source.read())
        val hard = dir.resolve("hard")
        checkWindows(hard, CreateHardLinkW(hard.pathString, source.pathString, null))
        assertTrue(source.isSameFileAs(hard))
        source.copyTo(hard)
        source.moveTo(hard)
        assertTrue(source.exists())
        val populated = dir.resolve("populated").createDirectory()
        populated.resolve("keep").createFile()
        assertFailsWith<DirectoryNotEmptyException> { source.copyTo(populated, overwrite = true) }
        assertTrue(populated.resolve("keep").exists())
        assertFailsWith<NoSuchFileException> {
            dir.resolve("missing").copyTo(target, overwrite = true)
        }
        assertContentEquals(data, target.read())
    }

    @Test
    fun moveAndDelete() = withDirectory { dir ->
        val source = dir.resolve("source")
        source.write(byteArrayOf(42))
        val target = dir.resolve("target").createFile()
        assertFailsWith<FileAlreadyExistsException> { source.moveTo(target) }
        source.moveTo(target, overwrite = true, atomicMove = true)
        assertFalse(source.exists())
        assertContentEquals(byteArrayOf(42), target.read())
        assertTrue(target.deleteIfExists())
        assertFalse(target.deleteIfExists())
        assertFailsWith<NoSuchFileException> { target.deleteExisting() }
    }

    @Test
    fun dosAttributesAndTimestamps() = withDirectory { dir ->
        val file = dir.resolve("file").createFile()
        val snapshot = file.readAttributes()
        file.writeAttributes(FileAttributesChanges(isHidden = true))
        assertTrue(file.readAttributes().isHidden!!)
        assertFalse(snapshot.isHidden!!)
        // Opening/truncating a hidden file must not fail as CREATE_ALWAYS would.
        file.write(byteArrayOf(1))
        file.writeAttributes(FileAttributesChanges(isHidden = false, dosIsReadOnly = true))
        assertTrue(file.readAttributes().dosIsReadOnly!!)
        file.writeAttributes(FileAttributesChanges(dosIsReadOnly = false))
        assertFalse(file.readAttributes().dosIsReadOnly!!)
        assertNull(file.readAttributes().posixPermissions)
        assertFailsWith<UnsupportedOperationException> {
            file.writeAttributes(FileAttributesChanges(posixPermissions = emptySet()))
        }
        val time = Instant.fromEpochSeconds(123456789, 123456700)
        file.writeAttributes(FileAttributesChanges(lastModifiedTime = time, creationTime = time))
        if (file.fileStore().type.equals("NTFS", ignoreCase = true)) {
            assertEquals(time, file.readAttributes().lastModifiedTime)
            assertEquals(time, file.readAttributes().creationTime)
        }
        assertFailsWith<IllegalArgumentException> {
            file.writeAttributes(
                FileAttributesChanges(lastModifiedTime = Instant.fromEpochSeconds(-11644473601L))
            )
        }
        val copy = dir.resolve("copy")
        file.copyTo(copy, copyAttributes = true)
        assertEquals(file.readAttributes().lastModifiedTime, copy.readAttributes().lastModifiedTime)
        assertEquals(file.readAttributes().creationTime, copy.readAttributes().creationTime)
    }

    @Test
    fun symbolicLinksWhenWindowsAllowsTheirCreation() = withDirectory { dir ->
        val file = dir.resolve("file")
        file.write(byteArrayOf(9))
        val link = dir.resolve("link")
        try {
            link.createSymbolicLink(fs.path("file"))
        } catch (error: FileSystemException) {
            // Developer Mode or SeCreateSymbolicLinkPrivilege is required; no other failure is
            // ignored.
            if (error.message?.contains("Windows error 1314") == true) return@withDirectory
            throw error
        }
        assertTrue(link.isSymbolicLink()!!)
        assertFalse(link.readAttributes(false).isRegularFile!!)
        assertEquals("file", link.readSymbolicLink().pathString)
        assertTrue(link.isSameFileAs(file))
        assertEquals(file.realPath(), link.realPath())
        val directory = dir.resolve("directory").createDirectory()
        directory.resolve("keep").createFile()
        val directoryLink = dir.resolve("directory-link").createSymbolicLink(fs.path("directory"))
        assertTrue(directoryLink.isDirectory()!!)
        assertFalse(directoryLink.readAttributes(false).isDirectory!!)
        assertTrue(directoryLink.isSymbolicLink()!!)
        directoryLink.deleteExisting()
        assertTrue(directory.resolve("keep").exists())
        val copied = dir.resolve("copied")
        link.copyTo(copied, followLinks = false)
        assertEquals(link.readSymbolicLink(), copied.readSymbolicLink())
        link.copyTo(dir.resolve("followed"))
        assertContentEquals(byteArrayOf(9), dir.resolve("followed").read())
        file.deleteExisting()
        assertFalse(link.exists())
        assertTrue(link.exists(false))
        assertTrue(link.deleteIfExists())
    }

    @Test
    fun storesMimeAndForeignPaths() = withDirectory { dir ->
        assertTrue(fs.fileStores().any())
        val store = dir.fileStore()
        assertTrue(store.name!!.isNotEmpty())
        assertTrue(store.type!!.isNotEmpty())
        assertTrue(store.totalSpace!! > 0)
        assertTrue(store.usableSpace!! >= 0)
        assertTrue(store.unallocatedSpace!! >= store.usableSpace!!)
        assertFalse(store.isReadonly!!)
        assertEquals("text/plain", dir.resolve("FILE.TXT").mimeType())
        assertEquals("", dir.resolve("file.os-utils-unknown-extension").mimeType())
        val foreign = KnWindowsFileSystem().path(dir.pathString)
        assertFailsWith<IllegalArgumentException> { fs.absolute(foreign) }
        assertFailsWith<IllegalArgumentException> { fs.exists(foreign, true) }
        assertFailsWith<IllegalArgumentException> { fs.readAttributes(foreign, false) }
        assertFailsWith<IllegalArgumentException> { fs.copyTo(dir, foreign, false, false, true) }
    }

    @Test
    fun windowsTimestampConversionIsPointerSizeIndependent(): Unit = memScoped {
        for (instant in
            listOf(
                Instant.fromEpochSeconds(-11644473600L),
                Instant.fromEpochSeconds(-1, 123456700),
                Instant.fromEpochSeconds(0),
                Instant.fromEpochSeconds(123456789, 987654300),
            )) {
            val time = alloc<FILETIME>()
            time.set(instant)
            assertEquals(instant, time.toInstant())
        }
        assertFailsWith<IllegalArgumentException> {
            Instant.fromEpochSeconds(-11644473601L).toWindowsTicks()
        }
    }

    @Test
    fun reparseTargetsAreDecodedWithExplicitBounds() {
        val file = fs.path("C:\\link")
        fun symlink(target: String): ByteArray {
            val bytes = ByteArray(20 + target.length * 2)
            fun putShort(offset: Int, value: Int) {
                bytes[offset] = value.toByte()
                bytes[offset + 1] = (value ushr 8).toByte()
            }
            putShort(0, (IO_REPARSE_TAG_SYMLINK and 0xffffu).toInt())
            putShort(2, (IO_REPARSE_TAG_SYMLINK shr 16).toInt())
            putShort(4, bytes.size - 8)
            putShort(10, target.length * 2)
            putShort(
                16,
                1,
            ) // SYMLINK_FLAG_RELATIVE; the target is not interpreted as a native pointer.
            target.forEachIndexed { index, char -> putShort(20 + index * 2, char.code) }
            return bytes
        }
        val relative = symlink("dir\\文件")
        assertEquals("dir\\文件", decodeWindowsLink(file, relative, relative.size))
        val absolute = symlink("\\??\\C:\\target")
        assertEquals("C:\\target", decodeWindowsLink(file, absolute, absolute.size))
        assertFailsWith<FileSystemException> {
            decodeWindowsLink(file, relative, relative.size - 1)
        }
        assertFailsWith<FileSystemException> { decodeWindowsLink(file, ByteArray(4), 4) }
        assertFailsWith<FileSystemException> { decodeWindowsLink(file, ByteArray(20), 20) }
    }

    @Test
    fun extendedPathsSupportLongAndLiteralNames() = withDirectory { dir ->
        val ordinary = dir.absolute().pathString
        val prefix =
            if (ordinary.startsWith("\\\\")) "\\\\?\\UNC\\" + ordinary.substring(2)
            else "\\\\?\\" + ordinary
        val extended = fs.path(prefix)
        val literal = fs.path("$prefix\\literal. ").createFile()
        assertTrue(literal.exists())
        assertTrue(extended.list().any { it.name == "literal. " })
        literal.deleteExisting()
        val long =
            extended
                .resolve("a".repeat(100))
                .resolve("b".repeat(100))
                .resolve("c".repeat(100))
                .createDirectories()
        val file = long.resolve("file")
        file.write(byteArrayOf(42))
        assertContentEquals(byteArrayOf(42), file.read())
        file.deleteExisting()
        long.deleteExisting()
        long.parent!!.deleteExisting()
        long.parent!!.parent!!.deleteExisting()
    }

    @Test
    fun windowsStringBuffersHandleSingleAndMultipleStrings() {
        val text = "文件" + "a".repeat(300)
        var singleCalls = 0
        assertEquals(
            text,
            windowsString(null) { capacity, buffer ->
                singleCalls++
                if (capacity <= text.length.toUInt()) (text.length + 1).toUInt()
                else {
                    text.forEachIndexed { index, char -> buffer[index] = char.code.toUShort() }
                    buffer[text.length] = 0.toUShort()
                    text.length.toUInt()
                }
            },
        )
        assertEquals(2, singleCalls)

        val entries = listOf("C:\\", "D:\\", text)
        val multi = entries.joinToString("\u0000", postfix = "\u0000\u0000")
        var multiCalls = 0
        assertEquals(
            entries,
            windowsStrings(null) { capacity, buffer ->
                multiCalls++
                if (capacity < multi.length.toUInt()) multi.length.toUInt()
                else {
                    multi.forEachIndexed { index, char -> buffer[index] = char.code.toUShort() }
                    (multi.length - 1).toUInt()
                }
            },
        )
        assertEquals(2, multiCalls)
        assertEquals(
            emptyList(),
            windowsStrings(null) { _, buffer ->
                buffer[0] = 0.toUShort()
                buffer[1] = 0.toUShort()
                1u
            },
        )
    }
}
