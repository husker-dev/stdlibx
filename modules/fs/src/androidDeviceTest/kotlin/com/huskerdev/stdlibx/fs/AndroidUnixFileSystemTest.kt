package com.huskerdev.stdlibx.fs

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huskerdev.stdlibx.ContextProvider
import com.huskerdev.stdlibx.fs.legacy.SyncAndroidLegacyFileSystem
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidUnixFileSystemTest {
    private val fs = SyncAndroidLegacyFileSystem()
    private lateinit var root: SyncPath
    private var externalRoot: SyncPath? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ContextProvider.applicationContext = context
        root = fs.path(File(context.cacheDir, "io-fs-${System.nanoTime()}").path)
        root.createDirectory()
    }

    @After
    fun tearDown() {
        fun delete(path: SyncPath) {
            if (OsConstants.S_ISDIR(Os.lstat(path.pathString).st_mode))
                path.list().forEach { delete(it) }
            path.deleteExisting()
        }
        if (::root.isInitialized && root.exists(false)) delete(root)
        externalRoot?.let { if (it.exists(false)) delete(it) }
    }

    private fun file(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): SyncPath =
        root.resolve(name).also { it.newOutputStream().use { stream -> stream.write(bytes) } }

    private fun SyncPath.bytes(): ByteArray = newInputStream().use { it.readAllBytes() }

    @Test
    fun wholeFileModesPreserveCreationSemantics() {
        val path = root.resolve("modes")
        assertFailsWith<NoSuchFileException> {
            path.writeBytes(byteArrayOf(1), creation = FileCreation.OPEN_EXISTING)
        }
        path.writeBytes(byteArrayOf(1, 2, 3), creation = FileCreation.CREATE_NEW)
        assertFailsWith<FileAlreadyExistsException> {
            path.writeBytes(byteArrayOf(9), creation = FileCreation.CREATE_NEW)
        }
        path.writeBytes(byteArrayOf(4), mode = FileWriteMode.FROM_START)
        assertContentEquals(byteArrayOf(4, 2, 3), path.readBytes())
        path.writeBytes(byteArrayOf(5), mode = FileWriteMode.APPEND)
        assertContentEquals(byteArrayOf(4, 2, 3, 5), path.readBytes())
        path.writeString("Привет 世界")
        assertEquals("Привет 世界", path.readString())
    }

    @Test
    fun asyncFilesystemViewsShareIdentityAndAttributes(): Unit = runBlocking {
        val sync = file("async")
        val path = sync.asAsync()
        assertTrue(path.fileSystem.identity === fs.identity)
        assertEquals<Path>(sync, path)
        path.writeBytes(byteArrayOf(1, 2))
        path.writeBytes(byteArrayOf(3), mode = FileWriteMode.APPEND)
        assertContentEquals(byteArrayOf(1, 2, 3), sync.readBytes())
        val snapshot = path.readAttributes()
        val modified = Instant.fromEpochSeconds(1_600_000_000)
        path.writeAttributes(FileAttributesChanges(lastModifiedTime = modified))
        assertEquals(modified, path.readAttributes().lastModifiedTime)
        assertTrue(snapshot.lastModifiedTime != modified)
        assertTrue(path.isReadable())
    }

    private fun externalDirectory(): SyncPath {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val cache = context.externalCacheDir
        assumeTrue(
            "Requires external storage on a different filesystem",
            cache != null && Os.stat(cache.path).st_dev != Os.stat(root.pathString).st_dev,
        )
        return fs.path(File(cache, "io-fs-${System.nanoTime()}").path).createDirectory().also {
            externalRoot = it
        }
    }

    @Test
    fun streamsPreserveLookaheadAndBulkReads() {
        val path = file("data", byteArrayOf(0, 127, -128, -1))
        path.newInputStream().use { input ->
            assertTrue(input.hasNext())
            assertTrue(input.hasNext())
            assertEquals(4L, input.availableBytes())
            val buffer = ByteArray(6)
            assertEquals(0, input.read(buffer, 0, 0))
            assertEquals(4, input.read(buffer, 1, 5))
            assertContentEquals(byteArrayOf(0, 127, -128, -1), buffer.copyOfRange(1, 5))
            assertFalse(input.hasNext())
            assertEquals(0L, input.availableBytes())
            assertEquals(0, input.read(buffer))
            assertFailsWith<NoSuchElementException> { input.read() }
        }
    }

    @Test
    fun closedInputRejectsReads() {
        val input = file("data").newInputStream()
        input.close()
        input.close()
        assertFailsWith<IllegalStateException> { input.hasNext() }
        assertFailsWith<IllegalStateException> { input.read(ByteArray(1)) }
    }

    @Test
    fun outputTruncatesExistingFiles() {
        val path = file("data")
        path.newOutputStream().use { it.write((-1).toByte()) }
        assertContentEquals(byteArrayOf(-1), path.bytes())
    }

    @Test
    fun symbolicLinksExposeTargetAndNoFollowAttributes() {
        val target = file("data")
        val link = root.resolve("link").createSymbolicLink(fs.path("data"))
        assertEquals("data", link.readSymbolicLink().pathString)
        assertTrue(link.readAttributes(false).isSymbolicLink!!)
        assertTrue(link.readAttributes().isRegularFile!!)
        assertTrue(link.isSameFileAs(target))
        assertContentEquals(target.bytes(), link.bytes())
        link.deleteExisting()
        assertTrue(target.exists())
    }

    @Test
    fun danglingLinkExistsAndCanBeDeleted() {
        val link = root.resolve("link").createSymbolicLink(fs.path("missing"))
        assertFalse(link.exists())
        assertTrue(link.exists(false))
        assertTrue(link.deleteIfExists())
        assertFalse(link.deleteIfExists())
    }

    @Test
    fun directoryLinkDeletionDoesNotDeleteItsContents() {
        val directory = root.resolve("dir").createDirectory()
        val child = directory.resolve("child").createFile()
        val link = root.resolve("link").createSymbolicLink(directory)
        assertTrue(link.isDirectory()!!)
        link.deleteExisting()
        assertTrue(child.exists())
    }

    @Test
    fun copyFollowsLinksOnlyWhenRequested() {
        file("data")
        val link = root.resolve("link").createSymbolicLink(fs.path("data"))
        val followed = link.copyTo(root.resolve("followed"))
        val preserved = link.copyTo(root.resolve("preserved"), followLinks = false)
        assertFalse(followed.isSymbolicLink()!!)
        assertContentEquals(link.bytes(), followed.bytes())
        assertTrue(preserved.isSymbolicLink()!!)
        assertEquals("data", preserved.readSymbolicLink().pathString)
    }

    @Test
    fun copyingDanglingLinkOntoItselfDoesNotDeleteIt() {
        val link = root.resolve("link").createSymbolicLink(fs.path("missing"))
        link.copyTo(link, overwrite = true, followLinks = false)
        assertTrue(link.exists(false))
        assertEquals("missing", link.readSymbolicLink().pathString)
    }

    @Test
    fun distinctLinksToSameTargetAreNotIdenticalForCopyOrMove() {
        file("data")
        val first = root.resolve("first").createSymbolicLink(fs.path("data"))
        val second = root.resolve("second").createSymbolicLink(fs.path("data"))
        assertFailsWith<FileAlreadyExistsException> { first.copyTo(second, followLinks = false) }
        assertFailsWith<FileAlreadyExistsException> { first.moveTo(second) }
        first.moveTo(second, overwrite = true)
        assertFalse(first.exists(false))
        assertTrue(second.isSymbolicLink()!!)
    }

    @Test
    fun copyReplacesTargetLinkRatherThanItsReferent() {
        val source = file("source")
        val link = root.resolve("link").createSymbolicLink(source)
        source.copyTo(link, overwrite = true)
        assertFalse(link.isSymbolicLink()!!)
        assertContentEquals(source.bytes(), link.bytes())
    }

    @Test
    fun copyDoesNotOverwriteUnlessRequested() {
        val source = file("source")
        val target = file("target", byteArrayOf(4))
        assertFailsWith<FileAlreadyExistsException> { source.copyTo(target) }
        assertContentEquals(byteArrayOf(4), target.bytes())
        source.copyTo(target, overwrite = true)
        assertContentEquals(source.bytes(), target.bytes())
    }

    @Test
    fun unreadableSourceDoesNotDeleteExistingCopyTarget() {
        val source = file("source")
        val target = file("target", byteArrayOf(4, 5, 6))
        val permissions = source.readAttributes().posixPermissions
        source.writeAttributes(FileAttributesChanges(posixPermissions = emptySet()))
        try {
            assertFailsWith<java.io.IOException> { source.copyTo(target, overwrite = true) }
            assertContentEquals(byteArrayOf(4, 5, 6), target.bytes())
        } finally {
            source.writeAttributes(FileAttributesChanges(posixPermissions = permissions))
        }
    }

    @Test
    fun copyingFollowedLinkOntoItselfPreservesContent() {
        val source = file("source")
        val link = root.resolve("link").createSymbolicLink(source)
        link.copyTo(link, overwrite = true)
        assertFalse(link.isSymbolicLink()!!)
        assertContentEquals(byteArrayOf(1, 2, 3), link.bytes())
        assertContentEquals(byteArrayOf(1, 2, 3), source.bytes())
    }

    @Test
    fun copyHandlesEmptyAndMultiBufferFiles() {
        for (size in listOf(0, 65_537)) {
            val bytes = ByteArray(size) { it.toByte() }
            val source = file("source-$size", bytes)
            val target = source.copyTo(root.resolve("target-$size"))
            assertContentEquals(bytes, target.bytes())
        }
    }

    @Test
    fun directoryCopyIsShallow() {
        val source = root.resolve("source").createDirectory()
        source.resolve("child").createFile()
        val target = source.copyTo(root.resolve("target"))
        assertTrue(target.isDirectory()!!)
        assertTrue(target.list().isEmpty())
    }

    @Test
    fun movePreservesSymlinkAndSupportsAtomicRename() {
        val link = root.resolve("link").createSymbolicLink(fs.path("missing"))
        val moved = link.moveTo(root.resolve("moved"), atomicMove = true)
        assertFalse(link.exists(false))
        assertEquals("missing", moved.readSymbolicLink().pathString)
    }

    @Test
    fun overwriteMoveCanReplaceAnEmptyDirectoryWithAFile() {
        val source = file("source")
        val target = root.resolve("target").createDirectory()
        source.moveTo(target, overwrite = true)
        assertTrue(target.isRegularFile()!!)
        assertFalse(source.exists())
    }

    @Test
    fun overwriteMoveCanReplaceAFileWithADirectory() {
        val source = root.resolve("source").createDirectory()
        source.resolve("child").createFile()
        val target = file("target")
        source.moveTo(target, overwrite = true)
        assertTrue(target.resolve("child").exists())
        assertFalse(source.exists())
    }

    @Test
    fun crossFilesystemDirectoryMoveDoesNotDeleteExistingTarget() {
        val source = root.resolve("source").createDirectory()
        val child = source.resolve("child").createFile()
        val target = externalDirectory().resolve("target")
        target.newOutputStream().use { it.write(byteArrayOf(4, 5, 6)) }
        assertFailsWith<FileSystemException> { source.moveTo(target, overwrite = true) }
        assertTrue(child.exists())
        assertContentEquals(byteArrayOf(4, 5, 6), target.bytes())
    }

    @Test
    fun atomicCrossFilesystemMoveDoesNotReplaceTarget() {
        val source = file("source")
        val target = externalDirectory().resolve("target")
        target.newOutputStream().use { it.write(byteArrayOf(4)) }
        assertFailsWith<FileSystemException> {
            source.moveTo(target, overwrite = true, atomicMove = true)
        }
        assertContentEquals(byteArrayOf(1, 2, 3), source.bytes())
        assertContentEquals(byteArrayOf(4), target.bytes())
    }

    @Test
    fun atomicMoveDoesNotDeleteTargetOfDifferentType() {
        val source = root.resolve("source").createDirectory()
        source.resolve("child").createFile()
        val target = file("target", byteArrayOf(4))
        assertFailsWith<FileSystemException> {
            source.moveTo(target, overwrite = true, atomicMove = true)
        }
        assertTrue(source.resolve("child").exists())
        assertContentEquals(byteArrayOf(4), target.bytes())
    }

    @Test
    fun overwriteMoveDoesNotDeleteNonEmptyTargetDirectory() {
        val source = file("source")
        val target = root.resolve("target").createDirectory()
        target.resolve("child").createFile()
        assertFailsWith<DirectoryNotEmptyException> { source.moveTo(target, overwrite = true) }
        assertContentEquals(byteArrayOf(1, 2, 3), source.bytes())
        assertTrue(target.resolve("child").exists())
    }

    @Test
    fun nonEmptyDirectoryCannotBeDeletedOrReplaced() {
        val directory = root.resolve("dir").createDirectory()
        directory.resolve("child").createFile()
        assertFailsWith<DirectoryNotEmptyException> { directory.deleteExisting() }
        assertFailsWith<DirectoryNotEmptyException> {
            file("source").copyTo(directory, overwrite = true)
        }
        assertTrue(directory.resolve("child").exists())
    }

    @Test
    fun directoriesAndExclusiveCreationWork() {
        val directory = root.resolve("a/b/c").createDirectories()
        directory.createDirectories()
        val child = directory.resolve("child").createFile()
        assertFailsWith<FileAlreadyExistsException> { child.createFile() }
        assertFailsWith<FileSystemException> { child.resolve("nested").createDirectories() }
        assertFalse(root.resolve("missing").deleteIfExists())
    }

    @Test
    fun createDirectoriesAppliesPermissionsToNewParents() {
        val permissions =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        val directory = root.resolve("a/b/c").createDirectories(permissions)
        for (path in listOf(root.resolve("a"), root.resolve("a/b"), directory)) {
            assertEquals(permissions, path.readAttributes().posixPermissions)
            path.list()
        }
        directory.resolve("child").createFile()
        assertEquals(listOf("child"), directory.list().map { it.name })
    }

    @Test
    fun permissionsAndCopyAttributesWork() {
        val source = file("source")
        val permissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        source.writeAttributes(FileAttributesChanges(posixPermissions = permissions))
        source.writeAttributes(
            FileAttributesChanges(lastModifiedTime = Instant.fromEpochSeconds(1_700_000_000))
        )
        val target = source.copyTo(root.resolve("target"), copyAttributes = true)
        assertEquals(permissions, target.readAttributes().posixPermissions)
        assertEquals(
            source.readAttributes().lastModifiedTime,
            target.readAttributes().lastModifiedTime,
        )
        source.writeAttributes(
            FileAttributesChanges(
                posixPermissions = permissions + PosixFilePermission.OWNER_EXECUTE
            )
        )
        assertTrue(source.isExecutable())
        assertNull(source.readAttributes().creationTime)
        assertFailsWith<UnsupportedOperationException> {
            source.writeAttributes(
                FileAttributesChanges(lastAccessTime = Instant.fromEpochSeconds(1_700_000_000))
            )
        }
    }

    @Test
    fun noFollowPermissionChangesRejectLinks() {
        val target = file("data")
        val permissions = target.readAttributes().posixPermissions
        val link = root.resolve("link").createSymbolicLink(target)
        assertFailsWith<UnsupportedOperationException> {
            link.writeAttributes(FileAttributesChanges(posixPermissions = emptySet()), false)
        }
        assertEquals(permissions, target.readAttributes().posixPermissions)
    }

    @Test
    fun realPathResolvesLinksOrPreservesTheirMeaning() {
        val target = root.resolve("a/b").createDirectories()
        val link = root.resolve("link").createSymbolicLink(target)
        assertEquals(target.realPath().pathString, link.realPath().pathString)
        assertEquals(link.pathString, link.realPath(false).pathString)
        val parents = link.resolve("../..")
        assertTrue(parents.isSameFileAs(parents.realPath(false)))
        assertFailsWith<NoSuchFileException> { root.resolve("missing").realPath() }
    }

    @Test
    fun fileIdentityIsNotBasedOnContents() {
        val source = file("source")
        val target = file("target")
        assertTrue(source.isSameFileAs(root.resolve("./source")))
        assertContentEquals(source.bytes(), target.bytes())
        assertFalse(source.isSameFileAs(target))
    }

    @Test
    fun directoryStreamHonorsLifecycle() {
        file("first")
        file("second")
        root.streamDirectory().use { stream ->
            assertEquals(setOf("first", "second"), stream.asSequence().map { it.name }.toSet())
            assertFalse(stream.hasNext())
        }
        val stream = root.streamDirectory()
        stream.close()
        assertFalse(stream.hasNext())
    }

    @Test
    fun storesMimeAndSystemDirectoriesAreAvailable() {
        val path = file("text.txt")
        assertEquals("text/plain", path.mimeType())
        assertTrue(path.fileStore().totalSpace!! > 0)
        assertTrue(fs.fileStores().any())
        assertTrue(fs.workingDir().isDirectory()!!)
        assertTrue(fs.tmpDir().isDirectory()!!)
        assertNull(fs.desktopDir())
    }

    @Test
    fun foreignFilesystemPathsAreRejected() {
        val foreign = SyncAndroidLegacyFileSystem().path(root.pathString)
        assertFailsWith<IllegalArgumentException> { fs.newInputStream(foreign) }
        assertFailsWith<IllegalArgumentException> {
            fs.newOutputStream(foreign, FileWriteMode.TRUNCATE, FileCreation.CREATE_IF_MISSING)
        }
        assertFailsWith<IllegalArgumentException> { fs.exists(foreign, true) }
        assertFailsWith<IllegalArgumentException> { fs.exists(foreign, false) }
        assertFailsWith<IllegalArgumentException> { fs.readAttributes(foreign, true) }
        assertFailsWith<IllegalArgumentException> { fs.realPath(foreign, false) }
        assertFailsWith<IllegalArgumentException> { fs.streamDirectory(foreign) }
        assertFailsWith<IllegalArgumentException> { fs.isSameFiles(root, foreign) }
        assertFailsWith<IllegalArgumentException> { fs.copyTo(root, foreign, false, false, true) }
        assertFailsWith<IllegalArgumentException> { fs.copyTo(foreign, root, false, false, true) }
        assertFailsWith<IllegalArgumentException> { fs.moveTo(root, foreign, false, false) }
        assertFailsWith<IllegalArgumentException> { fs.moveTo(foreign, root, false, false) }
        assertFailsWith<IllegalArgumentException> { fs.readSymbolicLink(foreign) }
        assertFailsWith<IllegalArgumentException> { fs.createSymbolicLink(foreign, root) }
        assertFailsWith<IllegalArgumentException> { fs.deleteExisting(foreign) }
        assertFailsWith<IllegalArgumentException> { fs.deleteIfExists(foreign) }
        assertFailsWith<IllegalArgumentException> { fs.createFile(foreign, null) }
        assertFailsWith<IllegalArgumentException> { fs.createDirectory(foreign, null) }
        assertFailsWith<IllegalArgumentException> { fs.createDirectories(foreign, null) }
        assertFailsWith<IllegalArgumentException> { fs.fileStore(foreign) }
        val foreignMissing = foreign.resolve("missing")
        val missing = root.resolve("missing")
        assertFailsWith<IllegalArgumentException> { fs.moveTo(foreign, missing, false, false) }
        assertFailsWith<IllegalArgumentException> { fs.moveTo(root, foreignMissing, false, false) }
        assertFailsWith<IllegalArgumentException> { fs.createSymbolicLink(foreignMissing, root) }
        assertFailsWith<IllegalArgumentException> { fs.createSymbolicLink(missing, foreign) }
        assertFalse(missing.exists(false))
        assertTrue(root.exists())
    }
}
