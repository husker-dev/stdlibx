package com.huskerdev.stdlibx.io.fs

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path as NioPath
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import kotlin.test.*
import kotlin.time.toKotlinInstant

class NioFileSystemTest {
    private class Fixture(val nio: NioPath) {
        val fs = FileSystem.default!!.asSync()!!
        val root = fs.path(nio.toString())

        fun path(name: String) = root.resolve(name)

        fun write(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): SyncPath {
            Files.write(nio.resolve(name), bytes)
            return path(name)
        }
    }

    private fun fixture(block: Fixture.() -> Unit) {
        val root = Files.createTempDirectory("osutils-io-test-")
        try {
            Fixture(root).block()
        } finally {
            // Cleanup must not depend on the filesystem code under test or follow links.
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<NioPath>() {
                    override fun visitFile(
                        file: NioPath,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: NioPath,
                        exc: java.io.IOException?,
                    ): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }

    @Test
    fun outputCreatesFileAndTruncatesExistingContent() = fixture {
        val file = path("file")
        file.newOutputStream().use { it.write(byteArrayOf(1, 2, 3)) }
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(nio.resolve("file")))
        file.newOutputStream().use { it.write(9.toByte()) }
        assertContentEquals(byteArrayOf(9), Files.readAllBytes(nio.resolve("file")))
    }

    @Test
    fun inputReadAllBytesPreservesFileContent() = fixture {
        val data = ByteArray(16385) { it.toByte() }
        val file = write("file", data)
        assertContentEquals(data, file.newInputStream().use { it.readAllBytes() })
    }

    @Test
    fun inputBulkReadRespectsSliceAndEof() = fixture {
        val file = write("file")
        file.newInputStream().use {
            val buffer = ByteArray(5) { 9 }
            assertEquals(3, it.read(buffer, 1, 4))
            assertContentEquals(byteArrayOf(9, 1, 2, 3, 9), buffer)
            assertEquals(0, it.read(buffer))
        }
    }

    @Test
    fun missingInputDoesNotCreateFile() = fixture {
        assertFailsWith<NoSuchFileException> { path("missing").newInputStream() }
        assertFalse(Files.exists(nio.resolve("missing")))
    }

    @Test
    fun createDirectoryReturnsOwnedPathAndRejectsExisting() = fixture {
        val directory = path("dir")
        val created = directory.createDirectory()
        assertEquals(directory, created)
        assertSame(fs, created.fileSystem)
        assertTrue(Files.isDirectory(nio.resolve("dir")))
        assertFailsWith<FileAlreadyExistsException> { directory.createDirectory() }
    }

    @Test
    fun createDirectoriesCreatesParentsAndIsIdempotent() = fixture {
        val directory = path("a/b/c")
        assertEquals(directory, directory.createDirectories())
        assertEquals(directory, directory.createDirectories())
        assertTrue(Files.isDirectory(nio.resolve("a/b/c")))
    }

    @Test
    fun createDirectoriesRejectsFileAsParent() = fixture {
        write("file")
        assertFailsWith<java.io.IOException> { path("file/child").createDirectories() }
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(nio.resolve("file")))
    }

    @Test
    fun createParentDirectoriesDoesNotCreateFile() = fixture {
        val file = path("a/b/file")
        assertSame(file, file.createParentDirectories())
        assertTrue(Files.isDirectory(nio.resolve("a/b")))
        assertFalse(Files.exists(nio.resolve("a/b/file")))
    }

    @Test
    fun copyPreservesContentAndSource() = fixture {
        val source = write("source")
        val target = source.copyTo(path("target"))
        assertSame(fs, target.fileSystem)
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(nio.resolve("target")))
        assertTrue(source.exists())
    }

    @Test
    fun copyDoesNotOverwriteByDefault() = fixture {
        val source = write("source")
        val target = write("target", byteArrayOf(9))
        assertFailsWith<FileAlreadyExistsException> { source.copyTo(target) }
        assertContentEquals(byteArrayOf(9), Files.readAllBytes(nio.resolve("target")))
        source.copyTo(target, overwrite = true)
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(nio.resolve("target")))
    }

    @Test
    fun directoryCopyIsNotRecursive() = fixture {
        Files.createDirectory(nio.resolve("source"))
        Files.write(nio.resolve("source/child"), byteArrayOf(1))
        path("source").copyTo(path("target"))
        assertTrue(Files.isDirectory(nio.resolve("target")))
        assertFalse(Files.exists(nio.resolve("target/child")))
    }

    @Test
    fun moveRemovesSourceAndPreservesContent() = fixture {
        val source = write("source")
        val target = source.moveTo(path("target"))
        assertSame(fs, target.fileSystem)
        assertFalse(source.exists())
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(nio.resolve("target")))
    }

    @Test
    fun nonAtomicMoveDoesNotOverwriteByDefault() = fixture {
        val source = write("source")
        val target = write("target", byteArrayOf(9))
        assertFailsWith<FileAlreadyExistsException> { source.moveTo(target) }
        assertTrue(source.exists())
        assertContentEquals(byteArrayOf(9), Files.readAllBytes(nio.resolve("target")))
        source.moveTo(target, overwrite = true)
        assertFalse(source.exists())
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(nio.resolve("target")))
    }

    @Test
    fun deleteIfExistsDistinguishesMissingPath() = fixture {
        val file = write("file")
        assertTrue(file.deleteIfExists())
        assertFalse(file.deleteIfExists())
        assertFailsWith<NoSuchFileException> { file.deleteExisting() }
    }

    @Test
    fun directoryStreamReturnsOwnedChildren() = fixture {
        write("one")
        write("two")
        root.streamDirectory().use { stream ->
            val children = stream.asSequence().toList()
            assertEquals(setOf("one", "two"), children.map { it.name }.toSet())
            children.forEach { assertSame(fs, it.fileSystem) }
        }
    }

    @Test
    fun realPathAndSameFileAgreeWithNio() = fixture {
        val file = write("file")
        assertEquals(nio.resolve("file").toRealPath().toString(), file.realPath().pathString)
        assertSame(fs, file.realPath().fileSystem)
        assertTrue(file.isSameFileAs(root.resolve("./file")))
        assertEquals(3L, fs.readAttributes(file, true).size)
        assertEquals(3L, file.size())
    }

    @Test
    fun foreignOwnerIsRejectedBeforeIo() = fixture {
        val file = write("file")
        val foreign = foreignUnixFileSystem().path(file.pathString)
        assertFailsWith<IllegalArgumentException> { fs.exists(foreign, true) }
        assertFailsWith<IllegalArgumentException> { fs.deleteExisting(foreign) }
        assertFailsWith<IllegalArgumentException> { file.copyTo(foreign, overwrite = true) }
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(nio.resolve("file")))
    }

    @Test
    fun homeDirectoryBelongsToReceiver() {
        if (java.io.File.separatorChar != '/') return
        val fs = SyncNioUserUnixFileSystem()
        val home = assertNotNull(fs.homeDir())
        assertSame(fs, home.fileSystem)
        assertEquals(fs.path(System.getProperty("user.home")), home)
    }

    @Test
    fun liveSymlinkDistinguishesLinkFromTargetAndDeleteKeepsTarget() = fixture {
        if (java.io.File.separatorChar != '/') return@fixture
        write("target")
        Files.createSymbolicLink(nio.resolve("link"), java.nio.file.Paths.get("target"))
        val link = path("link")
        assertTrue(link.isSymbolicLink()!!)
        assertFalse(link.readAttributes().isSymbolicLink!!)
        assertTrue(link.readAttributes(false).isSymbolicLink!!)
        assertTrue(link.isRegularFile()!!)
        assertFalse(link.isRegularFile(false)!!)
        assertTrue(link.isSameFileAs(path("target")))
        link.deleteExisting()
        assertTrue(Files.exists(nio.resolve("target")))
    }

    @Test
    fun danglingSymlinkExistsWithoutFollowingAndCanBeDeleted() = fixture {
        if (java.io.File.separatorChar != '/') return@fixture
        Files.createSymbolicLink(nio.resolve("link"), java.nio.file.Paths.get("missing"))
        val link = path("link")
        assertFalse(link.exists())
        assertTrue(link.exists(false))
        assertTrue(link.isSymbolicLink()!!)
        assertTrue(link.deleteIfExists())
        assertFalse(link.exists(false))
    }

    @Test
    fun copyingSymlinkCanFollowOrPreserveLink() = fixture {
        if (java.io.File.separatorChar != '/') return@fixture
        write("target")
        Files.createSymbolicLink(nio.resolve("link"), java.nio.file.Paths.get("target"))
        path("link").copyTo(path("followed"))
        path("link").copyTo(path("preserved"), followLinks = false)
        assertFalse(Files.isSymbolicLink(nio.resolve("followed")))
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(nio.resolve("followed")))
        assertTrue(Files.isSymbolicLink(nio.resolve("preserved")))
        assertEquals(
            java.nio.file.Paths.get("target"),
            Files.readSymbolicLink(nio.resolve("preserved")),
        )
    }

    @Test
    fun settingModifiedTimePreservesAccessTime() = fixture {
        val file = write("file")
        val raw = nio.resolve("file")
        val before = Files.readAttributes(raw, BasicFileAttributes::class.java)
        val modified = FileTime.fromMillis(1_600_000_000_000)
        file.writeAttributes(
            FileAttributesChanges(lastModifiedTime = modified.toInstant().toKotlinInstant())
        )
        val after = Files.readAttributes(raw, BasicFileAttributes::class.java)
        assertEquals(modified, after.lastModifiedTime())
        assertEquals(before.lastAccessTime(), after.lastAccessTime())
    }

    @Test
    fun copyAttributesPreservesModifiedTime() = fixture {
        val source = write("source")
        Files.setLastModifiedTime(nio.resolve("source"), FileTime.fromMillis(1_600_000_000_000))
        source.copyTo(path("target"), copyAttributes = true)
        assertEquals(
            Files.getLastModifiedTime(nio.resolve("source")),
            Files.getLastModifiedTime(nio.resolve("target")),
        )
    }

    @Test
    fun permissionSetterChangesOnlyRequestedOwnerBit() = fixture {
        val file = write("file")
        if (
            Files.getFileAttributeView(nio.resolve("file"), PosixFileAttributeView::class.java) ==
                null
        )
            return@fixture
        val permissions =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
            )
        file.writeAttributes(FileAttributesChanges(posixPermissions = permissions))
        val snapshot = file.readAttributes()
        file.writeAttributes(
            FileAttributesChanges(posixPermissions = permissions - PosixFilePermission.OWNER_WRITE)
        )
        assertEquals(
            permissions - PosixFilePermission.OWNER_WRITE,
            file.readAttributes().posixPermissions,
        )
        assertEquals(permissions, snapshot.posixPermissions)
        file.writeAttributes(FileAttributesChanges(posixPermissions = permissions))
        assertEquals(permissions, file.readAttributes().posixPermissions)
    }
}
