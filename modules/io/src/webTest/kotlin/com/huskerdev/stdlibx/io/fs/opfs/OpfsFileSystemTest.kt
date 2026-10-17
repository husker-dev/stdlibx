@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.io.fs.opfs

import com.huskerdev.stdlibx.io.*
import com.huskerdev.stdlibx.io.fs.*
import kotlin.coroutines.*
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*

class OpfsFileSystemTest {
    @Test
    fun contractCreation(): Promise<JsAny?> = fixture { _, root ->
        AsyncFileSystemContract(root).creationAndWriteModes()
    }

    @Test
    fun contractText(): Promise<JsAny?> = fixture { _, root ->
        AsyncFileSystemContract(root).textAndWholeFiles()
    }

    @Test
    fun contractStreams(): Promise<JsAny?> = fixture { _, root ->
        AsyncFileSystemContract(root).streamsAndBounds()
    }

    @Test
    fun contractDirectories(): Promise<JsAny?> = fixture { _, root ->
        AsyncFileSystemContract(root).directoriesCopyMoveAndDelete()
    }

    @Test
    fun contractSnapshots(): Promise<JsAny?> = fixture { _, root ->
        AsyncFileSystemContract(root).snapshotsAndViews()
    }

    @Test
    fun contractWalking(): Promise<JsAny?> = fixture { _, root ->
        AsyncFileSystemContract(root).walkingDepthAndControl()
    }

    private fun fixture(block: suspend (OpfsFileSystem, AsyncPath) -> Unit): Promise<JsAny?> =
        opfsTestPromise { complete ->
            val action: suspend () -> Unit = {
                check(isOpfsSupported()) {
                    "OPFS browser tests require a secure context with OPFS support"
                }
                val fs = OpfsFileSystem()
                val directory =
                    fs.path("/os-utils-test-${Random.nextLong().toULong().toString(16)}")
                        .createDirectory()
                try {
                    block(fs, directory)
                } finally {
                    withContext(NonCancellable) { directory.deleteRecursively() }
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

    @Test
    fun sandboxPathsAndViews(): Promise<JsAny?> = fixture { fs, directory ->
        assertNull(fs.asSync())
        assertNull(directory.asSync())
        assertSame(fs, fs.asAsync())
        assertEquals("/", fs.workingDir().pathString)
        assertNull(fs.homeDir())
        assertNull(fs.documentsDir())
        assertTrue(fs.tmpDir().isDirectory()!!)
        assertEquals(listOf("/"), fs.rootDirectories().map { it.pathString })
        assertEquals(directory, directory.resolve("./child/..").realPath())
        assertTrue(directory.isSameFileAs(OpfsFileSystem().path(directory.pathString)))
        assertTrue(directory.isReadable())
        assertTrue(directory.isWritable())
        assertFalse(directory.isExecutable())
        assertFalse(directory.resolve("missing").exists())
        assertTrue(directory.resolve("missing").notExist())
        val relative = fs.path(directory.pathString.removePrefix("/")).resolve("relative")
        assertEquals(relative, relative.createFile())
        assertEquals(relative, relative.writeBytes(byteArrayOf(1)))
        assertTrue(fs.path(directory.pathString.removePrefix("/")).list().contains(relative))
        assertEquals(directory.resolve("relative"), relative.realPath())
        val foreignFs =
            object : com.huskerdev.stdlibx.io.fs.unix.AsyncUnixFileSystem, AsyncFileSystem by fs {
                override val identity: Any = Any()

                override fun path(vararg path: String) =
                    com.huskerdev.stdlibx.io.fs.unix.AsyncUnixPath(path.joinToString("/"), this)
            }
        val foreign = foreignFs.path(relative.pathString)
        assertFailsWith<IllegalArgumentException> { fs.exists(foreign, false) }
        assertFailsWith<IllegalArgumentException> { fs.readBytes(foreign) }
        assertFailsWith<IllegalArgumentException> { fs.deleteExisting(foreign) }
        assertTrue(relative.exists())
    }

    @Test
    fun wholeFilesAndOpenOptions(): Promise<JsAny?> = fixture { _, directory ->
        val file = directory.resolve("данные-文件-😀")
        assertFailsWith<NoSuchFileException> {
            file.writeBytes(byteArrayOf(1), creation = FileCreation.OPEN_EXISTING)
        }
        val bytes = ByteArray(50000) { it.toByte() }
        file.writeBytes(bytes, creation = FileCreation.CREATE_NEW)
        assertContentEquals(bytes, file.readBytes())
        assertFailsWith<FileAlreadyExistsException> {
            file.writeBytes(ByteArray(0), creation = FileCreation.CREATE_NEW)
        }
        assertContentEquals(bytes, file.readBytes())
        file.writeBytes(byteArrayOf(1, 2, 3))
        file.writeBytes(byteArrayOf(4), mode = FileWriteMode.FROM_START)
        assertContentEquals(byteArrayOf(4, 2, 3), file.readBytes())
        file.writeBytes(byteArrayOf(5), mode = FileWriteMode.APPEND)
        assertContentEquals(byteArrayOf(4, 2, 3, 5), file.readBytes())
        file.writeString("Привет 世界 😀\r\ntext\rnext\n")
        assertEquals(listOf("Привет 世界 😀", "text", "next"), file.readLines())
        file.writeLines(listOf("a", "", "b"))
        assertEquals("a\n\nb\n", file.readString())
        file.writeBytes(ByteArray(0))
        assertEquals(0L, file.size())
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertFails { file.readString() }
        assertFails { file.writeString(CharArray(1) { 0xD800.toChar() }.concatToString()) }
    }

    @Test
    fun streamsUseSnapshotsAndPublishOnClose(): Promise<JsAny?> = fixture { _, directory ->
        val file = directory.resolve("file")
        val data = ByteArray(25000) { it.toByte() }
        file.writeBytes(data)
        file.newInputStream().use { input ->
            assertNull(input.asSync())
            assertEquals(data.size.toLong(), input.availableBytes())
            assertTrue(input.hasNext())
            assertTrue(input.hasNext())
            assertEquals(data[0], input.read())
            val buffer = ByteArray(12) { 99 }
            assertEquals(8, input.read(buffer, 2, 8))
            assertContentEquals(data.copyOfRange(1, 9), buffer.copyOfRange(2, 10))
            assertEquals(100L, input.skip(100))
            assertContentEquals(data.copyOfRange(109, data.size), input.readAllBytes())
            assertFalse(input.hasNext())
            assertEquals(0, input.read(ByteArray(0)))
            assertFailsWith<NoSuchElementException> { input.read() }
            assertFailsWith<IndexOutOfBoundsException> { input.read(buffer, -1, 1) }
        }
        val output = file.newOutputStream()
        assertNull(output.asSync())
        output.write(byteArrayOf(9))
        assertFailsWith<FileSystemException> { file.newOutputStream(mode = FileWriteMode.APPEND) }
        output.flush()
        assertContentEquals(data, file.readBytes())
        output.close()
        output.close()
        assertContentEquals(byteArrayOf(9), file.readBytes())
        assertFailsWith<IllegalStateException> { output.flush() }
        val input = file.newInputStream()
        input.close()
        input.close()
        assertFailsWith<IllegalStateException> { input.hasNext() }
    }

    @Test
    fun directoryLifecycleAndExclusiveCreation(): Promise<JsAny?> = fixture { _, directory ->
        val nested = directory.resolve("a/b").createDirectories()
        nested.createDirectories()
        assertFailsWith<FileAlreadyExistsException> { nested.createDirectory() }
        val file = nested.resolve("child").createFile()
        assertFailsWith<FileAlreadyExistsException> { file.createFile() }
        assertFailsWith<FileAlreadyExistsException> { file.createDirectories() }
        assertFalse(file.resolve("missing").exists())
        val stream = nested.streamDirectory()
        assertTrue(stream.hasNext())
        assertTrue(stream.hasNext())
        assertEquals(file, stream.next())
        assertFalse(stream.hasNext())
        stream.close()
        stream.close()
        assertFalse(stream.hasNext())
        assertFailsWith<IllegalStateException> { stream.next() }
        assertFailsWith<DirectoryNotEmptyException> { nested.deleteExisting() }
        file.deleteExisting()
        assertFalse(file.deleteIfExists())
        nested.deleteExisting()
    }

    @Test
    fun skippingBufferedBytesDoesNotReadAnotherSlice(): Promise<JsAny?> = fixture { _, directory ->
        val counted = countingFile()
        OpfsInputStream(directory.resolve("synthetic"), counted.file).use { input ->
            assertTrue(input.hasNext())
            assertEquals(1, counted.reads())
            assertEquals(0L, input.skip(0))
            assertTrue(input.hasNext())
            assertEquals(1, counted.reads())
            assertEquals(10L, input.skip(10))
            assertEquals(10.toByte(), input.read())
            assertEquals(1, counted.reads())
            assertEquals(8192L, input.skip(8192))
            assertEquals(8203.toByte(), input.read())
            assertEquals(2, counted.reads())
        }
    }

    @Test
    fun copiesAndMovesAreSafeAndExplicit(): Promise<JsAny?> = fixture { _, directory ->
        val source = directory.resolve("source").createDirectory()
        source.resolve("nested").createDirectory().resolve("data").writeString("payload")
        val shallow = directory.resolve("shallow")
        source.copyTo(shallow)
        assertTrue(shallow.list().isEmpty())
        assertFailsWith<UnsupportedOperationException> {
            source.copyTo(directory.resolve("attrs"), copyAttributes = true)
        }
        assertFalse(directory.resolve("attrs").exists())
        val file = source.resolve("nested/data")
        val copy = directory.resolve("copy")
        file.copyTo(copy)
        assertEquals("payload", copy.readString())
        assertFailsWith<FileAlreadyExistsException> { file.copyTo(copy) }
        file.copyTo(copy, overwrite = true)
        assertFalse(file.isSameFileAs(copy))
        file.copyTo(file)
        assertFailsWith<NoSuchFileException> {
            directory.resolve("missing").copyTo(copy, overwrite = true)
        }
        assertEquals("payload", copy.readString())
        assertFailsWith<AtomicMoveNotSupportedException> {
            source.moveTo(directory.resolve("atomic"), atomicMove = true)
        }
        assertFailsWith<IllegalArgumentException> { source.moveTo(source.resolve("child")) }
        val moved = source.moveTo(directory.resolve("moved"))
        assertFalse(source.exists())
        assertEquals("payload", moved.resolve("nested/data").readString())
        assertFailsWith<DirectoryNotEmptyException> { copy.copyTo(moved, overwrite = true) }
        assertEquals("payload", moved.resolve("nested/data").readString())
    }

    @Test
    fun attributesAndOriginStorageBudget(): Promise<JsAny?> = fixture { fs, directory ->
        val file = directory.resolve(".file.txt")
        file.writeString("abc")
        val attributes = file.readAttributes()
        assertEquals(3L, attributes.size)
        assertTrue(attributes.isRegularFile!!)
        assertTrue(attributes.isHidden!!)
        assertFalse(attributes.isSymbolicLink!!)
        assertNotNull(attributes.lastModifiedTime)
        assertNull(attributes.lastAccessTime)
        assertNull(attributes.creationTime)
        assertNull(attributes.posixPermissions)
        assertNull(attributes.dosIsReadOnly)
        file.writeAttributes(FileAttributesChanges())
        assertFailsWith<UnsupportedOperationException> {
            file.writeAttributes(
                FileAttributesChanges(lastModifiedTime = Instant.fromEpochSeconds(1))
            )
        }
        assertFailsWith<UnsupportedOperationException> { file.createSymbolicLink(directory) }
        assertFailsWith<UnsupportedOperationException> { file.readSymbolicLink() }
        assertFailsWith<UnsupportedOperationException> {
            directory.resolve("permissions").createFile(emptySet())
        }
        assertEquals("text/plain", file.mimeType())
        val store = file.fileStore()
        assertEquals("opfs", store.type)
        assertFalse(store.isReadonly!!)
        assertTrue(store.totalSpace!! > 0)
        assertTrue(store.usableSpace!! >= 0)
        assertNull(store.unallocatedSpace)
        assertEquals(1, fs.fileStores().count())
    }

    @Test
    fun cooperatingClientsCreateOnlyOneFile(): Promise<JsAny?> = fixture { _, directory ->
        val path = directory.resolve("exclusive")
        val other = OpfsFileSystem().path(path.pathString)
        val outcomes = coroutineScope {
            listOf(
                    async { runCatching { path.createFile() } },
                    async { runCatching { other.createFile() } },
                )
                .awaitAll()
        }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertIs<FileAlreadyExistsException>(outcomes.single { it.isFailure }.exceptionOrNull())
    }

    @Test
    fun cancelledWriterAbortsAndReleasesBrowserLock(): Promise<JsAny?> = fixture { _, directory ->
        val file = directory.resolve("cancelled")
        file.writeString("original")
        coroutineScope {
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    val output = file.newOutputStream()
                    try {
                        output.write(ByteArray(1024 * 1024))
                        currentCoroutineContext().cancel()
                        output.write(byteArrayOf(1))
                    } finally {
                        output.close()
                    }
                }
            job.join()
        }
        assertEquals("original", file.readString())
        file.writeString("reopened")
        assertEquals("reopened", file.readString())
    }

    @Test
    fun cancelledMutationWaiterReleasesItsReservation(): Promise<JsAny?> = fixture { _, directory ->
        val file = directory.resolve("waiter")
        coroutineScope {
            val ready = CompletableDeferred<Unit>()
            val holder = launch {
                withOpfsMutation {
                    ready.complete(Unit)
                    awaitCancellation()
                }
            }
            ready.await()
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { file.createFile() }
            waiter.cancelAndJoin()
            holder.cancelAndJoin()
        }
        assertFalse(file.exists())
        file.createFile()
        assertTrue(file.exists())
    }
}

private fun opfsTestPromise(action: ((String?) -> Unit) -> Unit): Promise<JsAny?> =
    js(
        "new Promise((resolve, reject) => action(error => error === null ? resolve(null) : reject(new Error(error))))"
    )

private external interface CountingFile : JsAny {
    val file: OpfsFile

    fun reads(): Int
}

private fun countingFile(): CountingFile =
    js(
        """
    (() => {
        let reads = 0;
        const data = Uint8Array.from({length: 20000}, (_, i) => i & 255);
        const file = {size: data.length, slice(start, end) {
            reads++;
            return new Blob([data.subarray(start, end)]);
        }};
        return {file, reads: () => reads};
    })()
"""
    )
