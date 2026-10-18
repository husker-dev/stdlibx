@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import kotlin.test.*
import kotlin.time.Instant
import kotlinx.cinterop.*
import platform.posix.*

class KnUnixFileSystemTest {
    private val fs = SyncKnUnixFileSystem()

    private fun withDirectory(block: (SyncPath) -> Unit) {
        val template =
            "${fs.tmpDir().pathString}/os-utils-io-XXXXXX".encodeToByteArray() + byteArrayOf(0)
        val name =
            template.usePinned { mkdtemp(it.addressOf(0))?.toKString() }
                ?: error("Cannot create test directory")
        val directory = fs.path(name)
        try {
            block(directory)
        } finally {
            cleanup(directory)
        }
    }

    private fun cleanup(path: SyncPath) {
        if (path.exists(false) && path.isDirectory(false)!!) path.list().forEach(::cleanup)
        path.deleteIfExists()
    }

    private fun SyncPath.write(bytes: ByteArray) = newOutputStream().use { it.write(bytes) }

    private fun SyncPath.read() = newInputStream().use { it.readAllBytes() }

    @Test
    fun defaultFileSystemAndSystemDirectories() {
        assertIs<KnUnixFileSystem>(FileSystem.default)
        assertTrue(fs.workingDir().isAbsolute)
        assertTrue(fs.tmpDir().isDirectory()!!)
        assertTrue(fs.homeDir()?.isAbsolute != false)
        assertEquals(listOf("/"), fs.rootDirectories().map { it.pathString })
        assertTrue(fs.absolute(fs.path("test")).isAbsolute)
    }

    @Test
    fun exclusiveCreationAndParentDirectories() = withDirectory { dir ->
        val file = dir.resolve("parents/nested/file")
        val permissions =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        file.parent!!.createDirectories(permissions)
        file.parent!!.createDirectories(permissions)
        assertEquals(permissions, file.parent!!.readAttributes().posixPermissions)
        file.createFile()
        assertFailsWith<FileAlreadyExistsException> { file.createFile() }
        assertFailsWith<FileAlreadyExistsException> { file.createDirectories() }
        assertFailsWith<NoSuchFileException> { dir.resolve("absent/file").createFile() }
    }

    @Test
    fun streamsPreserveLookaheadOffsetsAndEof() = withDirectory { dir ->
        val file = dir.resolve("bytes")
        val bytes = ByteArray(40000) { it.toByte() }
        file.write(bytes)
        file.newInputStream().use { input ->
            assertEquals(bytes.size.toLong(), input.availableBytes())
            assertTrue(input.hasNext())
            assertTrue(input.hasNext())
            assertEquals(bytes.size.toLong(), input.availableBytes())
            assertEquals(bytes[0], input.read())
            val buffer = ByteArray(12) { 99 }
            assertEquals(8, input.read(buffer, 2, 8))
            assertContentEquals(bytes.copyOfRange(1, 9), buffer.copyOfRange(2, 10))
            assertEquals(99.toByte(), buffer[0])
            input.skip(100)
            assertContentEquals(bytes.copyOfRange(109, bytes.size), input.readAllBytes())
            assertFalse(input.hasNext())
            assertEquals(0, input.read(buffer))
            assertEquals(0, input.read(byteArrayOf()))
            assertFailsWith<NoSuchElementException> { input.read() }
            assertFailsWith<IndexOutOfBoundsException> { input.read(buffer, 0, Int.MAX_VALUE) }
        }
    }

    @Test
    fun outputTruncatesAndValidatesBounds() = withDirectory { dir ->
        val file = dir.resolve("output")
        file.write(ByteArray(100))
        file.newOutputStream().use { output ->
            output.write(byteArrayOf(1, 2, 3, 4), 1, 2)
            output.write(5)
            output.write(byteArrayOf())
            output.flush()
            assertFailsWith<IndexOutOfBoundsException> { output.write(byteArrayOf(1), -1, 1) }
        }
        assertContentEquals(byteArrayOf(2, 3, 5), file.read())
    }

    @Test
    fun closedStreamsRejectOperationsAndCloseIsIdempotent() = withDirectory { dir ->
        val file = dir.resolve("closed")
        file.write(byteArrayOf(1))
        val input = file.newInputStream()
        input.hasNext()
        input.close()
        input.close()
        assertFailsWith<IllegalStateException> { input.hasNext() }
        assertFailsWith<IllegalStateException> { input.read(byteArrayOf()) }
        assertFailsWith<IllegalStateException> { input.availableBytes() }
        val output = file.newOutputStream()
        output.close()
        output.close()
        assertFailsWith<IllegalStateException> { output.write(1) }
        assertFailsWith<IllegalStateException> { output.flush() }
    }

    @Test
    fun directoryStreamHonorsLifecycle() = withDirectory { dir ->
        dir.resolve("child").createFile()
        val stream = dir.streamDirectory()
        val iterator = stream
        assertTrue(iterator.hasNext())
        assertEquals("child", iterator.next().name)
        assertFalse(iterator.hasNext())
        stream.close()
        stream.close()
        assertFalse(iterator.hasNext())
        assertFailsWith<IllegalStateException> { iterator.next() }
        assertFailsWith<FileSystemException> { dir.resolve("child").streamDirectory() }
    }

    @Test
    fun symbolicLinksAndDanglingLinks() = withDirectory { dir ->
        val file = dir.resolve("file").createFile()
        val link = dir.resolve("link").createSymbolicLink(fs.path("file"))
        assertEquals("file", link.readSymbolicLink().pathString)
        assertTrue(link.isSymbolicLink()!!)
        assertTrue(link.isRegularFile()!!)
        assertTrue(link.isSameFileAs(file))
        assertEquals(file.realPath(), link.realPath())
        assertEquals(link.absolute(), link.realPath(false))
        file.deleteExisting()
        assertFalse(link.exists())
        assertTrue(link.exists(false))
        assertTrue(link.deleteIfExists())
        assertFalse(link.deleteIfExists())
    }

    @Test
    fun noFollowRealPathPreservesSymlinkParentMeaning() = withDirectory { dir ->
        dir.resolve("target/nested").createDirectories()
        dir.resolve("target/file").write(byteArrayOf(42))
        dir.resolve("link").createSymbolicLink(fs.path("target/nested"))
        val original = dir.resolve("link/../file")
        val real = original.realPath(false)
        assertTrue(real.pathString.endsWith("link/../file"))
        assertTrue(real.isSameFileAs(original))
        assertContentEquals(byteArrayOf(42), real.read())
    }

    @Test
    fun copyHandlesEmptyAndLargeFiles() = withDirectory { dir ->
        for (size in listOf(0, 1, 80000)) {
            val bytes = ByteArray(size) { (it * 13).toByte() }
            val source = dir.resolve("source$size")
            val target = dir.resolve("target$size")
            source.write(bytes)
            source.copyTo(target)
            assertContentEquals(bytes, target.read())
            assertFalse(source.isSameFileAs(target))
            assertFailsWith<FileAlreadyExistsException> { source.copyTo(target) }
            source.copyTo(target, overwrite = true)
            assertContentEquals(bytes, target.read())
        }
    }

    @Test
    fun copyLinksOnlyFollowsWhenRequested() = withDirectory { dir ->
        val file = dir.resolve("file")
        file.write(byteArrayOf(9))
        val link = dir.resolve("link").createSymbolicLink(fs.path("file"))
        val followed = dir.resolve("followed")
        link.copyTo(followed)
        assertFalse(followed.isSymbolicLink()!!)
        assertContentEquals(file.read(), followed.read())
        val copied = dir.resolve("copied")
        link.copyTo(copied, followLinks = false, copyAttributes = true)
        assertEquals(link.readSymbolicLink(), copied.readSymbolicLink())
        link.copyTo(link, overwrite = true, followLinks = false)
        file.deleteExisting()
        link.copyTo(link, overwrite = true, followLinks = false)
        assertTrue(link.exists(false))
    }

    @Test
    fun copyingOntoSourceAndReplacingTargetSymlink() = withDirectory { dir ->
        val source = dir.resolve("source")
        source.write(byteArrayOf(1, 2))
        source.copyTo(source, overwrite = true)
        assertContentEquals(byteArrayOf(1, 2), source.read())
        val referent = dir.resolve("referent")
        referent.write(byteArrayOf(3))
        val link = dir.resolve("link").createSymbolicLink(referent)
        source.copyTo(link, overwrite = true)
        assertFalse(link.isSymbolicLink()!!)
        assertContentEquals(source.read(), link.read())
        assertContentEquals(byteArrayOf(3), referent.read())
    }

    @Test
    fun directoryCopyIsShallowAndNonEmptyTargetsArePreserved() = withDirectory { dir ->
        val source = dir.resolve("source").createDirectory()
        source.resolve("child").createFile()
        val target = dir.resolve("target")
        source.copyTo(target)
        assertTrue(target.isDirectory()!!)
        assertTrue(target.list().isEmpty())
        target.resolve("keep").createFile()
        assertFailsWith<DirectoryNotEmptyException> { source.copyTo(target, overwrite = true) }
        assertTrue(target.resolve("keep").exists())
        assertFailsWith<DirectoryNotEmptyException> { target.deleteExisting() }
    }

    @Test
    fun permissionsAndNanosecondTimestampsAreCopied() = withDirectory { dir ->
        val source = dir.resolve("source").createFile()
        val permissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        source.writeAttributes(FileAttributesChanges(posixPermissions = permissions))
        val snapshot = source.readAttributes()
        assertEquals(permissions, snapshot.posixPermissions)
        source.writeAttributes(
            FileAttributesChanges(
                posixPermissions = permissions + PosixFilePermission.OWNER_EXECUTE
            )
        )
        assertTrue(source.isExecutable())
        assertEquals(permissions, snapshot.posixPermissions)
        source.writeAttributes(FileAttributesChanges(posixPermissions = permissions))
        val modified = Instant.fromEpochSeconds(-1, 123456789)
        val access = Instant.fromEpochSeconds(123456789, 987654321)
        source.writeAttributes(
            FileAttributesChanges(lastModifiedTime = modified, lastAccessTime = access)
        )
        assertEquals(modified, source.readAttributes().lastModifiedTime)
        assertEquals(access, source.readAttributes().lastAccessTime)
        val target = dir.resolve("target")
        source.copyTo(target, copyAttributes = true)
        assertEquals(permissions, target.readAttributes().posixPermissions)
        assertEquals(modified, target.readAttributes().lastModifiedTime)
        assertEquals(access, target.readAttributes().lastAccessTime)
        assertFailsWith<UnsupportedOperationException> {
            source.writeAttributes(FileAttributesChanges(isHidden = true))
        }
        assertFailsWith<UnsupportedOperationException> {
            source.writeAttributes(FileAttributesChanges(creationTime = modified))
        }
    }

    @Test
    fun noFollowPermissionsRejectSymlinkButTimesCanBeChanged() = withDirectory { dir ->
        val file = dir.resolve("file").createFile()
        val link = dir.resolve("link").createSymbolicLink(file)
        assertFailsWith<UnsupportedOperationException> {
            link.writeAttributes(FileAttributesChanges(posixPermissions = emptySet()), false)
        }
        val original = file.readAttributes().lastModifiedTime
        val modified = Instant.fromEpochSeconds(123456789)
        link.writeAttributes(FileAttributesChanges(lastModifiedTime = modified), false)
        assertEquals(modified, link.readAttributes(false).lastModifiedTime)
        assertEquals(original, file.readAttributes().lastModifiedTime)
    }

    @Test
    fun movePreservesLinksAndSupportsAtomicRename() = withDirectory { dir ->
        val file = dir.resolve("file").createFile()
        val link = dir.resolve("link").createSymbolicLink(file)
        val target = dir.resolve("target")
        link.moveTo(target, atomicMove = true)
        assertFalse(link.exists(false))
        assertTrue(target.isSymbolicLink()!!)
        assertEquals(file, target.readSymbolicLink())
        val existing = dir.resolve("existing").createFile()
        assertFailsWith<FileAlreadyExistsException> { target.moveTo(existing) }
        target.moveTo(existing, overwrite = true)
        assertTrue(existing.isSymbolicLink()!!)
    }

    @Test
    fun overwriteMoveCanReplaceDifferentTypesButNotNonEmptyDirectories() = withDirectory { dir ->
        val file = dir.resolve("file").createFile()
        val target = dir.resolve("target").createDirectory()
        file.moveTo(target, overwrite = true)
        assertTrue(target.isRegularFile()!!)
        val directory = dir.resolve("directory").createDirectory()
        directory.moveTo(target, overwrite = true)
        assertTrue(target.isDirectory()!!)
        target.resolve("keep").createFile()
        val another = dir.resolve("another").createFile()
        assertFailsWith<DirectoryNotEmptyException> { another.moveTo(target, overwrite = true) }
        assertTrue(another.exists())
        assertTrue(target.resolve("keep").exists())
    }

    @Test
    fun hardLinkIdentityUsesDeviceAndInode() = withDirectory { dir ->
        val source = dir.resolve("source")
        source.write(byteArrayOf(1))
        val target = dir.resolve("target")
        assertEquals(0, link(source.pathString, target.pathString))
        assertTrue(source.isSameFileAs(target))
        source.copyTo(target)
        source.moveTo(target)
        assertTrue(source.exists())
    }

    @Test
    fun danglingTargetStillRequiresOverwrite() = withDirectory { dir ->
        val source = dir.resolve("source")
        source.write(byteArrayOf(42))
        val target = dir.resolve("target").createSymbolicLink(fs.path("missing"))
        assertFailsWith<FileAlreadyExistsException> { source.copyTo(target) }
        assertFailsWith<FileAlreadyExistsException> { source.moveTo(target) }
        assertTrue(source.exists())
        assertTrue(target.isSymbolicLink()!!)
        assertEquals("missing", target.readSymbolicLink().pathString)
    }

    @Test
    fun atomicMoveCanReplaceTargetWithoutOverwriteFlag() = withDirectory { dir ->
        val source = dir.resolve("source")
        source.write(byteArrayOf(42))
        val target = dir.resolve("target")
        target.write(byteArrayOf(1))
        source.moveTo(target, atomicMove = true)
        assertFalse(source.exists())
        assertContentEquals(byteArrayOf(42), target.read())
    }

    @Test
    fun missingSourceDoesNotRemoveExistingTarget() = withDirectory { dir ->
        val target = dir.resolve("target")
        target.write(byteArrayOf(42))
        assertFailsWith<NoSuchFileException> {
            dir.resolve("missing").copyTo(target, overwrite = true)
        }
        assertFailsWith<NoSuchFileException> {
            dir.resolve("missing").moveTo(target, overwrite = true)
        }
        assertContentEquals(byteArrayOf(42), target.read())
    }

    @Test
    fun fileStoresAndMimeTypes() = withDirectory { dir ->
        assertTrue(fs.fileStores().any())
        val store = dir.fileStore()
        assertTrue(store.name!!.isNotEmpty())
        assertTrue(store.type!!.isNotEmpty())
        assertTrue(store.totalSpace!! > 0)
        assertTrue(store.usableSpace!! >= 0)
        assertTrue(store.unallocatedSpace!! >= store.usableSpace!!)
        assertFalse(store.isReadonly!!, "${dir.realPath()} maps to ${store.name!!}")
        assertEquals("text/plain", dir.resolve("FILE.TXT").mimeType())
        assertEquals("", dir.resolve("unknown.no-such-mime-extension").mimeType())
    }

    @Test
    fun foreignPathsAreRejected() = withDirectory { dir ->
        val foreign = SyncKnUnixFileSystem().path(dir.pathString)
        assertFailsWith<IllegalArgumentException> { fs.absolute(foreign) }
        assertFailsWith<IllegalArgumentException> { fs.mimeType(foreign) }
        assertFailsWith<IllegalArgumentException> { fs.realPath(foreign, true) }
        assertFailsWith<IllegalArgumentException> { fs.readSymbolicLink(foreign) }
        assertFailsWith<IllegalArgumentException> { fs.copyTo(dir, foreign, false, false, true) }
        assertFailsWith<IllegalArgumentException> {
            fs.createSymbolicLink(dir.resolve("link"), foreign)
        }
    }
}
