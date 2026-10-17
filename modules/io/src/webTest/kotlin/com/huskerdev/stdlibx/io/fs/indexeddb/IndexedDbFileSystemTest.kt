@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.io.fs.indexeddb

import com.huskerdev.stdlibx.io.*
import com.huskerdev.stdlibx.io.fs.*
import kotlin.coroutines.*
import kotlin.js.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*

class IndexedDbFileSystemTest {
    @Test
    fun contractCreation(): Promise<JsAny?> = fixture { fs ->
        AsyncFileSystemContract(fs.path("/")).creationAndWriteModes()
    }

    @Test
    fun contractText(): Promise<JsAny?> = fixture { fs ->
        AsyncFileSystemContract(fs.path("/")).textAndWholeFiles()
    }

    @Test
    fun contractStreams(): Promise<JsAny?> = fixture { fs ->
        AsyncFileSystemContract(fs.path("/")).streamsAndBounds()
    }

    @Test
    fun contractDirectories(): Promise<JsAny?> = fixture { fs ->
        AsyncFileSystemContract(fs.path("/")).directoriesCopyMoveAndDelete()
    }

    @Test
    fun contractSnapshots(): Promise<JsAny?> = fixture { fs ->
        AsyncFileSystemContract(fs.path("/")).snapshotsAndViews()
    }

    @Test
    fun contractWalking(): Promise<JsAny?> = fixture { fs ->
        AsyncFileSystemContract(fs.path("/")).walkingDepthAndControl()
    }

    private fun fixture(block: suspend (IndexedDbFileSystem) -> Unit): Promise<JsAny?> =
        testPromise { complete ->
            val action: suspend () -> Unit = {
                check(isIndexedDbSupported()) { "IndexedDB browser tests require IndexedDB" }
                val name = "os-utils-test-idb-${Random.nextLong().toULong().toString(16)}"
                val fs = IndexedDbFileSystem(name)
                try {
                    block(fs)
                } finally {
                    withContext(NonCancellable) {
                        fs.close()
                        deleteTestDatabase(name).awaitDb()
                    }
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
    fun pathsDirectoriesAndIdentity(): Promise<JsAny?> = fixture { fs ->
        assertNull(fs.asSync())
        assertSame(fs, fs.asAsync())
        assertEquals("/", fs.workingDir().pathString)
        assertNull(fs.homeDir())
        assertTrue(fs.tmpDir().isDirectory()!!)
        val directory = fs.path("a/b").createDirectories()
        assertEquals("a/b", directory.pathString)
        directory.createDirectories()
        assertEquals("/a/b", directory.resolve("./child/..").realPath().pathString)
        assertFailsWith<FileAlreadyExistsException> { directory.createDirectory() }
        val file = directory.resolve("file").createFile()
        assertFailsWith<FileAlreadyExistsException> { file.createDirectories() }
        assertFalse(file.resolve("missing").exists())
        assertFalse(file.resolve("missing").exists())
        val stream = directory.streamDirectory()
        assertTrue(stream.hasNext())
        assertTrue(stream.hasNext())
        assertEquals(file, stream.next())
        assertFalse(stream.hasNext())
        stream.close()
        stream.close()
        assertFailsWith<IllegalStateException> { stream.next() }
        assertFailsWith<DirectoryNotEmptyException> { directory.deleteExisting() }
        val other = IndexedDbFileSystem(fs.databaseName)
        try {
            assertTrue(file.isSameFileAs(other.path(file.pathString)))
        } finally {
            other.close()
        }
        val foreign = IndexedDbFileSystem("${fs.databaseName}-other")
        try {
            assertFailsWith<IllegalArgumentException> { fs.readBytes(foreign.path("file")) }
            assertFailsWith<IllegalArgumentException> { file.copyTo(foreign.path("file")) }
        } finally {
            foreign.close()
        }
        file.deleteExisting()
        assertFalse(file.deleteIfExists())
        assertFailsWith<NoSuchFileException> { file.readBytes() }
    }

    @Test
    fun wholeFilesAndCreationModes(): Promise<JsAny?> = fixture { fs ->
        val file = fs.path("/данные-文件-😀.txt")
        assertFailsWith<NoSuchFileException> {
            file.writeBytes(byteArrayOf(1), creation = FileCreation.OPEN_EXISTING)
        }
        val bytes = ByteArray(50000) { it.toByte() }
        assertEquals(file, file.writeBytes(bytes, creation = FileCreation.CREATE_NEW))
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
        file.writeBytes(ByteArray(0), mode = FileWriteMode.FROM_START)
        assertContentEquals(byteArrayOf(4, 2, 3, 5), file.readBytes())
        file.writeString("Привет 世界 😀\r\nnext\rfinal\n")
        assertEquals(listOf("Привет 世界 😀", "next", "final"), file.readLines())
        file.writeLines(listOf("a", "", "b"))
        assertEquals("a\n\nb\n", file.readString())
        assertEquals("text/plain", file.mimeType())
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertFails { file.readString() }
        assertFails { file.writeString(CharArray(1) { 0xD800.toChar() }.concatToString()) }
        file.writeBytes(ByteArray(0))
        assertEquals(0L, file.size())
    }

    @Test
    fun virtualAttributesPreservePrecision(): Promise<JsAny?> = fixture { fs ->
        val file = fs.path("/file").createFile()
        val snapshot = file.readAttributes()
        val modified = Instant.fromEpochSeconds(-1, 123456789)
        val access = Instant.fromEpochSeconds(123456789, 987654321)
        val created = Instant.fromEpochSeconds(12345, 111111111)
        file.writeAttributes(
            FileAttributesChanges(
                lastModifiedTime = modified,
                lastAccessTime = access,
                creationTime = created,
                isHidden = true,
            )
        )
        val attributes = file.readAttributes()
        assertEquals(modified, attributes.lastModifiedTime)
        assertEquals(access, attributes.lastAccessTime)
        assertEquals(created, attributes.creationTime)
        assertTrue(attributes.isHidden!!)
        assertFalse(snapshot.isHidden!!)
        assertNotEquals(modified, snapshot.lastModifiedTime)
        assertNull(attributes.posixPermissions)
        assertNull(attributes.dosIsReadOnly)
        assertFailsWith<UnsupportedOperationException> {
            file.writeAttributes(
                FileAttributesChanges(lastModifiedTime = created, posixPermissions = emptySet())
            )
        }
        assertEquals(modified, file.readAttributes().lastModifiedTime)
        file.readBytes()
        assertNotEquals(access, file.readAttributes().lastAccessTime)
        assertEquals(modified, file.readAttributes().lastModifiedTime)
        assertTrue(file.isReadable())
        assertTrue(file.isWritable())
        assertFalse(file.isExecutable())
        assertFailsWith<UnsupportedOperationException> { file.readSymbolicLink() }
        assertFailsWith<UnsupportedOperationException> {
            file.createSymbolicLink(fs.path("/target"))
        }
    }

    @Test
    fun streamsPublishOnCloseAndKeepReadSnapshots(): Promise<JsAny?> = fixture { fs ->
        val file = fs.path("/stream")
        val bytes = ByteArray(25000) { it.toByte() }
        file.writeBytes(bytes)
        file.newInputStream().use { input ->
            assertNull(input.asSync())
            assertTrue(input.hasNext())
            assertTrue(input.hasNext())
            assertEquals(0L, input.skip(0))
            assertEquals(bytes[0], input.read())
            assertEquals(100L, input.skip(100))
            file.writeString("replacement")
            assertContentEquals(bytes.copyOfRange(101, bytes.size), input.readAllBytes())
            assertFalse(input.hasNext())
            assertEquals(0, input.read(ByteArray(0)))
            assertFailsWith<NoSuchElementException> { input.read() }
        }
        val output = file.newOutputStream()
        assertNull(output.asSync())
        output.write(byteArrayOf(9, 8))
        output.flush()
        assertEquals("replacement", file.readString())
        output.close()
        output.close()
        assertContentEquals(byteArrayOf(9, 8), file.readBytes())
        assertFailsWith<IllegalStateException> { output.flush() }
        file.newOutputStream(mode = FileWriteMode.APPEND).use { it.write(byteArrayOf(7)) }
        assertContentEquals(byteArrayOf(9, 8, 7), file.readBytes())
        file.newOutputStream(mode = FileWriteMode.FROM_START).use { it.write(byteArrayOf(1)) }
        assertContentEquals(byteArrayOf(1, 8, 7), file.readBytes())
    }

    @Test
    fun copyAndAtomicSubtreeMoves(): Promise<JsAny?> = fixture { fs ->
        val source = fs.path("/source").createDirectory()
        val file = source.resolve("nested").createDirectory().resolve("file")
        file.writeString("payload")
        val time = Instant.fromEpochSeconds(123456789, 987654321)
        file.writeAttributes(
            FileAttributesChanges(creationTime = time, lastModifiedTime = time, isHidden = true)
        )
        val copy = fs.path("/copy")
        file.copyTo(copy, copyAttributes = true)
        assertEquals(time, copy.readAttributes().creationTime)
        assertEquals(time, copy.readAttributes().lastModifiedTime)
        assertTrue(copy.isHidden()!!)
        assertEquals("payload", copy.readString())
        assertFalse(copy.isSameFileAs(file))
        assertFailsWith<FileAlreadyExistsException> { file.copyTo(copy) }
        assertFailsWith<NoSuchFileException> { fs.path("/missing").copyTo(copy, overwrite = true) }
        assertEquals("payload", copy.readString())
        file.copyTo(file)
        val shallow = source.copyTo(fs.path("/shallow"))
        assertTrue(shallow.list().isEmpty())
        assertFailsWith<IllegalArgumentException> {
            source.moveTo(source.resolve("nested/inside"), atomicMove = true)
        }
        val output = file.newOutputStream()
        output.write("after rename".encodeToByteArray())
        val moved = source.moveTo(fs.path("/moved"), atomicMove = true)
        output.close()
        assertFalse(source.exists())
        assertEquals("after rename", moved.resolve("nested/file").readString())
        assertFailsWith<DirectoryNotEmptyException> {
            copy.moveTo(moved, overwrite = true, atomicMove = true)
        }
        assertTrue(copy.exists())
        assertEquals("after rename", moved.resolve("nested/file").readString())
    }

    @Test
    fun concurrentCreatesAndAppendsAreTransactional(): Promise<JsAny?> = fixture { fs ->
        val other = IndexedDbFileSystem(fs.databaseName)
        try {
            val file = fs.path("/exclusive")
            val outcomes = coroutineScope {
                listOf(
                        async { runCatching { file.createFile() } },
                        async { runCatching { other.path("/exclusive").createFile() } },
                    )
                    .awaitAll()
            }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertIs<FileAlreadyExistsException>(outcomes.single { it.isFailure }.exceptionOrNull())
            coroutineScope {
                (0 until 12)
                    .map { value ->
                        async {
                            val owner = if (value % 2 == 0) fs else other
                            owner
                                .path("/exclusive")
                                .writeBytes(
                                    byteArrayOf(value.toByte()),
                                    mode = FileWriteMode.APPEND,
                                )
                        }
                    }
                    .awaitAll()
            }
            val bytes = file.readBytes()
            assertEquals(12, bytes.size)
            assertEquals((0 until 12).toSet(), bytes.map { it.toInt() }.toSet())
        } finally {
            other.close()
        }
    }

    @Test
    fun staleWritersCannotOverwriteOrResurrectFiles(): Promise<JsAny?> = fixture { fs ->
        val file = fs.path("/file")
        file.writeString("original")
        val first = file.newOutputStream()
        val stale = file.newOutputStream()
        first.write("first".encodeToByteArray())
        stale.write("stale".encodeToByteArray())
        first.close()
        assertFailsWith<FileSystemException> { stale.close() }
        stale.close()
        assertEquals("first", file.readString())
        val deleted = file.newOutputStream()
        deleted.write("deleted".encodeToByteArray())
        file.deleteExisting()
        file.writeString("recreated")
        assertFailsWith<NoSuchFileException> { deleted.close() }
        assertEquals("recreated", file.readString())
    }

    @Test
    fun cancellationAbortsBufferedWritesAndTransactions(): Promise<JsAny?> = fixture { fs ->
        val file = fs.path("/file")
        file.writeString("original")
        coroutineScope {
            launch {
                val output = file.newOutputStream()
                try {
                    output.write(byteArrayOf(1, 2, 3))
                    currentCoroutineContext().cancel()
                    output.write(byteArrayOf(4))
                } finally {
                    output.close()
                }
            }
                .join()
        }
        assertEquals("original", file.readString())
        val session = openIndexedDb(fs.databaseName).awaitDb()
        try {
            coroutineScope {
                val job =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        dbTransaction<Unit>(session, fs.path("/cancelled"), true) {
                            nodes.add(
                                dbNode(
                                    "cancelled-id",
                                    "root",
                                    "cancelled",
                                    "file",
                                    "2020-01-01T00:00:00Z",
                                )
                            )
                            content.put(dbEmptyBlob(), "cancelled-id")
                            finish(Unit)
                        }
                    }
                job.cancelAndJoin()
            }
            assertFalse(fs.path("/cancelled").exists())
        } finally {
            session.close()
        }
    }

    @Test
    fun contentIsOneBlobAndSurvivesReopen(): Promise<JsAny?> = fixture { fs ->
        val file = fs.path("/large")
        val data = ByteArray(256 * 1024) { it.toByte() }
        file.newOutputStream().use { output -> repeat(4) { output.write(data) } }
        assertEquals(1024L * 1024, file.size())
        val storage = checkTestStorage(fs.databaseName).awaitDb()
        assertEquals(1, storage.records)
        assertTrue(storage.blobsOnly)
        fs.close()
        assertFailsWith<IllegalStateException> { file.readBytes() }
        val reopened = IndexedDbFileSystem(fs.databaseName)
        try {
            val bytes = reopened.path("/large").readBytes()
            assertEquals(1024 * 1024, bytes.size)
            repeat(4) {
                assertContentEquals(data, bytes.copyOfRange(it * data.size, (it + 1) * data.size))
            }
            val store = reopened.path("/large").fileStore()
            assertEquals("indexeddb", store.type)
            assertEquals(fs.databaseName, store.name)
            assertTrue(store.totalSpace!! > 0)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun lateRequestFailureRollsBackTheEntireTransaction(): Promise<JsAny?> = fixture { fs ->
        val file = fs.path("/rollback")
        file.writeString("original")
        val before = file.readAttributes()
        val session = openIndexedDb(fs.databaseName).awaitDb()
        try {
            assertFailsWith<FileAlreadyExistsException> {
                dbTransaction<Unit>(session, file, true) {
                    resolve(path) { node ->
                        publish(node, "must roll back".encodeToByteArray().toDbBlob())
                        // This later request fails after successful put requests, aborting all of
                        // them.
                        nodes.add(dbNode("root", "", "", "directory", "2020-01-01T00:00:00Z"))
                        finish(Unit)
                    }
                }
            }
            assertEquals(before.lastModifiedTime, file.readAttributes().lastModifiedTime)
            assertEquals("original", file.readString())
        } finally {
            session.close()
        }
    }
}

private external interface TestStorage : JsAny {
    val records: Int
    val blobsOnly: Boolean
}

private fun checkTestStorage(name: String): Promise<TestStorage> =
    js(
        """
    new Promise((resolve, reject) => {
        const open = indexedDB.open(name, 1);
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
            const db = open.result;
            const tx = db.transaction('content', 'readonly');
            const request = tx.objectStore('content').getAll();
            tx.oncomplete = () => {
                db.close();
                resolve({records: request.result.length, blobsOnly: request.result.every(value => value instanceof Blob)});
            };
            tx.onabort = () => { db.close(); reject(tx.error); };
        };
    })
"""
    )

private fun deleteTestDatabase(name: String): Promise<JsAny?> =
    js(
        """
    new Promise((resolve, reject) => {
        const request = indexedDB.deleteDatabase(name);
        request.onsuccess = () => resolve(null);
        request.onerror = () => reject(request.error);
        request.onblocked = () => reject(new Error('Test database deletion blocked: ' + name));
    })
"""
    )

private fun testPromise(action: ((String?) -> Unit) -> Unit): Promise<JsAny?> =
    js(
        "new Promise((resolve, reject) => action(error => error === null ? resolve(null) : reject(new Error(error))))"
    )
