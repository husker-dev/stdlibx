@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.fs.nodejs

import com.huskerdev.stdlibx.io.*
import com.huskerdev.stdlibx.fs.*
import kotlin.coroutines.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Instant

/** Run with jsNodeTest/wasmJsNodeTest: these are Node filesystem integration tests. */
class NodeJsFileSystemTest {
    private fun contract(
        block: suspend AsyncFileSystemContract.() -> Unit
    ): kotlin.js.Promise<JsAny?> = testPromise { complete ->
        val action: suspend () -> Unit = {
            AsyncFileSystemContract(fs.asAsync().tmpDir()).block()
        }
        action.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Unit>) {
                    complete(result.exceptionOrNull()?.stackTraceToString())
                }
            }
        )
    }

    @Test fun contractCreation(): kotlin.js.Promise<JsAny?> = contract { creationAndWriteModes() }

    @Test fun contractText(): kotlin.js.Promise<JsAny?> = contract { textAndWholeFiles() }

    @Test fun contractStreams(): kotlin.js.Promise<JsAny?> = contract { streamsAndBounds() }

    @Test
    fun contractDirectories(): kotlin.js.Promise<JsAny?> = contract {
        directoriesCopyMoveAndDelete()
    }

    @Test fun contractSnapshots(): kotlin.js.Promise<JsAny?> = contract { snapshotsAndViews() }

    @Test fun contractWalking(): kotlin.js.Promise<JsAny?> = contract { walkingDepthAndControl() }

    private val fs = checkNotNull(FileSystem.default) as SyncNodeJsFileSystem

    private fun withDirectory(block: (SyncPath) -> Unit) {
        val directory =
            fs.tmpDir()
                .resolve("os-utils-node-${Random.nextLong().toULong().toString(16)}")
                .createDirectory()
        try {
            block(directory)
        } finally {
            cleanup(directory)
        }
    }

    private fun cleanup(path: SyncPath) {
        if (!path.exists(false)) return
        if (path.readAttributes(false).isDirectory == true) path.list().forEach(::cleanup)
        path.deleteExisting()
    }

    private fun SyncPath.write(bytes: ByteArray) = newOutputStream().use { it.write(bytes) }

    private fun SyncPath.read() = newInputStream().use { it.readAllBytes() }

    @Test
    fun systemDirectoriesAndPathOwnership() {
        assertTrue(fs.workingDir().isAbsolute)
        assertTrue(fs.homeDir().isDirectory()!!)
        assertTrue(fs.tmpDir().isDirectory()!!)
        assertTrue(fs.rootDirectories().any())
        assertEquals(fs.workingDir().resolve("file"), fs.absolute(fs.path("file")))
        assertEquals(fs.workingDir(), fs.absolute(fs.path("")))
        assertSame(fs, Path(fs.tmpDir().pathString).fileSystem)
        val other =
            if (fs is SyncUnixNodeJsFileSystem) SyncUnixNodeJsFileSystem()
            else SyncWindowsNodeJsFileSystem()
        assertFailsWith<IllegalArgumentException> {
            fs.exists(other.path(fs.tmpDir().pathString), true)
        }
    }

    @Test
    fun createsFilesAndDirectoriesAndMapsErrors() = withDirectory { dir ->
        val file = dir.resolve("a/b/file")
        file.parent!!.createDirectories()
        file.parent!!.createDirectories()
        file.createFile()
        assertFailsWith<FileAlreadyExistsException> { file.createFile() }
        assertFailsWith<FileAlreadyExistsException> { file.createDirectories() }
        assertFailsWith<NoSuchFileException> { dir.resolve("missing/file").createFile() }
        assertFailsWith<DirectoryNotEmptyException> { file.parent!!.deleteExisting() }
        assertTrue(file.deleteIfExists())
        assertFalse(file.deleteIfExists())
        assertFailsWith<NoSuchFileException> { file.deleteExisting() }
    }

    @Test
    fun unicodeNamesAndBinaryStreams() = withDirectory { dir ->
        val file = dir.resolve("данные-文件-😀")
        val bytes = ByteArray(40000) { it.toByte() }
        file.write(bytes)
        assertEquals(bytes.size.toLong(), file.size())
        file.newInputStream().use { input ->
            assertTrue(input.hasNext())
            assertTrue(input.hasNext())
            assertEquals(bytes.size.toLong(), input.availableBytes())
            assertContentEquals(bytes.copyOfRange(0, 3), input.read(3))
            assertEquals(7L, input.skip(7))
            assertEquals(bytes.size - 10L, input.availableBytes())
            assertContentEquals(bytes.copyOfRange(10, bytes.size), input.readAllBytes())
            assertFalse(input.hasNext())
            assertEquals(0, input.read(ByteArray(8)))
            assertFailsWith<NoSuchElementException> { input.read() }
        }
        file.write(byteArrayOf(9, 8))
        assertContentEquals(byteArrayOf(9, 8), file.read())
        assertEquals(file, dir.list().single())
    }

    @Test
    fun streamBoundsAndClosedState() = withDirectory { dir ->
        val file = dir.resolve("file")
        file.write(byteArrayOf(1, 2, 3))
        val input = file.newInputStream()
        val buffer = ByteArray(5) { 99 }
        assertEquals(2, input.read(buffer, 1, 2))
        assertContentEquals(byteArrayOf(99, 1, 2, 99, 99), buffer)
        assertEquals(0, input.read(buffer, 5, 0))
        assertFailsWith<IndexOutOfBoundsException> { input.read(buffer, 1, Int.MAX_VALUE) }
        input.close()
        input.close()
        assertFailsWith<IllegalStateException> { input.read() }
        val output = file.newOutputStream()
        assertFailsWith<IndexOutOfBoundsException> { output.write(buffer, -1, 2) }
        output.write(buffer, 5, 0)
        output.close()
        output.close()
        assertFailsWith<IllegalStateException> { output.flush() }
        assertFailsWith<IllegalStateException> { output.write(1.toByte()) }
    }

    @Test
    fun directoryStreamIsSingleUse() = withDirectory { dir ->
        dir.resolve("child").createFile()
        val stream = dir.streamDirectory()
        val iterator = stream
        assertEquals(dir.resolve("child"), iterator.next())
        assertFalse(iterator.hasNext())
        stream.close()
        stream.close()
        assertFalse(iterator.hasNext())
        assertFailsWith<IllegalStateException> { iterator.next() }
    }

    @Test
    fun copyOverwriteAndShallowDirectoryCopy() = withDirectory { dir ->
        val source = dir.resolve("source")
        val data = ByteArray(20000) { it.toByte() }
        source.write(data)
        val target = dir.resolve("target")
        source.copyTo(target)
        assertContentEquals(data, target.read())
        assertFailsWith<FileAlreadyExistsException> { source.copyTo(target) }
        source.copyTo(source)
        source.copyTo(target, overwrite = true)
        assertContentEquals(data, source.read())
        val directory = dir.resolve("directory").createDirectory()
        directory.resolve("keep").createFile()
        directory.copyTo(dir.resolve("shallow"))
        assertTrue(dir.resolve("shallow").list().isEmpty())
        assertFailsWith<DirectoryNotEmptyException> { source.copyTo(directory, overwrite = true) }
        assertTrue(directory.resolve("keep").exists())
        assertFailsWith<NoSuchFileException> {
            dir.resolve("missing").copyTo(target, overwrite = true)
        }
        assertContentEquals(data, target.read())
    }

    @Test
    fun renameAndAtomicMove() = withDirectory { dir ->
        val source = dir.resolve("source")
        source.write(byteArrayOf(42))
        val target = dir.resolve("target").createFile()
        assertFailsWith<FileAlreadyExistsException> { source.moveTo(target) }
        source.moveTo(target, overwrite = true, atomicMove = true)
        assertFalse(source.exists())
        assertContentEquals(byteArrayOf(42), target.read())
        target.moveTo(target)
        assertTrue(target.exists())
    }

    @Test
    fun symlinksAndDestinationLinks() = withDirectory { dir ->
        // Windows requires Developer Mode or a symlink privilege.
        if (fs is SyncWindowsNodeJsFileSystem) return@withDirectory
        val source = dir.resolve("source")
        source.write(byteArrayOf(1))
        val link = dir.resolve("link").createSymbolicLink(fs.path("source"))
        assertTrue(link.readAttributes(false).isSymbolicLink!!)
        assertTrue(link.readAttributes(true).isRegularFile!!)
        assertTrue(link.isSameFileAs(source))
        assertEquals(source.realPath(), link.realPath())
        assertEquals("source", link.readSymbolicLink().pathString)
        val copied = dir.resolve("copied")
        link.copyTo(copied, followLinks = false)
        assertTrue(copied.isSymbolicLink()!!)
        assertEquals("source", copied.readSymbolicLink().pathString)
        link.copyTo(dir.resolve("followed"))
        assertContentEquals(byteArrayOf(1), dir.resolve("followed").read())
        val replacement = dir.resolve("replacement")
        replacement.write(byteArrayOf(2))
        replacement.copyTo(link, overwrite = true)
        assertFalse(link.isSymbolicLink()!!)
        assertContentEquals(byteArrayOf(1), source.read())
        source.deleteExisting()
        assertFalse(copied.exists())
        assertTrue(copied.exists(false))
        copied.deleteExisting()
        val directory = dir.resolve("directory").createDirectory()
        directory.resolve("keep").createFile()
        val directoryLink = dir.resolve("directory-link").createSymbolicLink(fs.path("directory"))
        directoryLink.deleteExisting()
        assertTrue(directory.resolve("keep").exists())
    }

    @Test
    fun permissionsAndTimestamps() = withDirectory { dir ->
        val file = dir.resolve(".hidden").createFile()
        if (fs is SyncUnixNodeJsFileSystem) {
            assertTrue(file.readAttributes().isHidden!!)
            val permissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            file.writeAttributes(FileAttributesChanges(posixPermissions = permissions))
            val snapshot = file.readAttributes()
            file.writeAttributes(
                FileAttributesChanges(
                    posixPermissions = permissions + PosixFilePermission.OWNER_EXECUTE
                )
            )
            assertTrue(file.isExecutable())
            assertEquals(permissions, snapshot.posixPermissions)
            file.writeAttributes(FileAttributesChanges(posixPermissions = permissions))
            assertEquals(permissions, file.readAttributes().posixPermissions)
        }
        val time = Instant.fromEpochSeconds(123456789)
        file.writeAttributes(FileAttributesChanges(lastModifiedTime = time, lastAccessTime = time))
        assertEquals(time, file.readAttributes().lastModifiedTime)
        assertEquals(time, file.readAttributes().lastAccessTime)
        assertFailsWith<UnsupportedOperationException> {
            file.writeAttributes(FileAttributesChanges(creationTime = time))
        }
        assertFailsWith<UnsupportedOperationException> {
            file.writeAttributes(FileAttributesChanges(isHidden = false))
        }
        val copy = dir.resolve("copy")
        file.copyTo(copy, copyAttributes = true)
        assertEquals(time, copy.readAttributes().lastModifiedTime)
        if (fs is SyncUnixNodeJsFileSystem)
            assertEquals(
                file.readAttributes().posixPermissions,
                copy.readAttributes().posixPermissions,
            )
    }

    @Test
    fun fileStoresAndMimeFallback() = withDirectory { dir ->
        val store = dir.fileStore()
        assertTrue(store.totalSpace!! > 0)
        assertTrue(store.usableSpace!! >= 0)
        assertTrue(store.unallocatedSpace!! >= store.usableSpace!!)
        assertTrue(store.type!!.isNotEmpty())
        assertFalse(assertNotNull(store.isReadonly))
        assertTrue(fs.fileStores().any())
        assertEquals("text/plain", dir.resolve("file.TXT").mimeType())
        assertEquals("", dir.resolve("file.unknown-os-utils-extension").mimeType())
    }

    @Test
    fun asyncViewsWorkWithoutDispatchersIO(): kotlin.js.Promise<JsAny?> = testPromise { complete ->
        val action: suspend () -> Unit = {
            val directory =
                fs.tmpDir()
                    .resolve("os-utils-async-${Random.nextLong().toULong().toString(16)}")
                    .createDirectory()
            val file = directory.resolve("async").asAsync()
            try {
                file.newOutputStream().use { output ->
                    output.write(byteArrayOf(1, 2, 3))
                    output.flush()
                }
                file.newInputStream().use { input ->
                    assertEquals(3L, input.availableBytes())
                    assertContentEquals(byteArrayOf(1, 2, 3), input.readAllBytes())
                }
            } finally {
                cleanup(directory)
            }
        }
        action.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Unit>) {
                    complete(result.exceptionOrNull()?.stackTraceToString())
                }
            }
        )
    }
}
