package com.huskerdev.stdlibx.io.fs

import com.huskerdev.stdlibx.io.AsyncInputStream
import com.huskerdev.stdlibx.io.AsyncOutputStream
import com.huskerdev.stdlibx.io.AsyncStream
import com.huskerdev.stdlibx.io.SyncStream
import com.huskerdev.stdlibx.io.SyncInputStream
import com.huskerdev.stdlibx.io.SyncOutputStream
import com.huskerdev.stdlibx.io.asyncDispatcher
import com.huskerdev.stdlibx.io.SecurityException
import kotlinx.coroutines.withContext

internal expect val currentUserFileSystem: UserFileSystem?

/**
 * A source of paths and file operations. I/O failures use [FileSystemException], with specific
 * types for missing entries, existing targets, non-empty directories, links, cycles and
 * denied access. Platform exceptions are retained as causes where available.
 * Unsupported features throw [UnsupportedOperationException]; incompatible paths throw
 * [IllegalArgumentException]. A security-policy denial throws [SecurityException], including
 * from probes. Existence probes can both return false when status is unknown.
 * Exceptions from caller-provided visitors and iterables are propagated unchanged.
 */
interface FileSystem {
    companion object {
        /** The platform's default user file system, or null when none is available. */
        val default: UserFileSystem? = currentUserFileSystem
    }
    /** Identifies compatible paths across synchronous and asynchronous views. */
    val identity: Any

    /** The separator used when constructing paths. */
    val separator: String

    /** Creates a path from [path] components without checking whether it exists. */
    fun path(vararg path: String): Path

    /** Resolves [path] against this file system's working directory when needed. */
    fun absolute(path: Path): Path

    /** Throws [IllegalArgumentException] if [path] belongs to an incompatible file system. */
    fun assertFS(path: Path) =
        require(path.fileSystem.identity === identity) { "Path belongs to a different file system" }

    /** Returns a synchronous view, or null if blocking access is unavailable. */
    fun asSync(): SyncFileSystem?

    /** Returns an asynchronous view of the same file system. */
    fun asAsync(): AsyncFileSystem
}

/** A file system with blocking operations. Paths passed to it must belong to a compatible file system. */
interface SyncFileSystem: FileSystem {

    override fun path(vararg path: String): SyncPath
    override fun absolute(path: Path): SyncPath

    /** Returns the available root directories. */
    fun rootDirectories(): Iterable<SyncPath>

    /** Returns the storage volumes exposed by this file system. */
    fun fileStores(): Iterable<FileStore>

    /** Returns the absolute path to an existing entry, resolving links when [followLinks] is true. */
    fun realPath(path: Path, followLinks: Boolean): SyncPath

    /** Checks whether the entry can be executed by the current user. */
    fun isExecutable(path: Path): Boolean

    /** Checks whether the entry can be read by the current user. */
    fun isReadable(path: Path): Boolean

    /** Checks whether the entry can be written by the current user. */
    fun isWritable(path: Path): Boolean

    /** Returns the MIME type inferred for [path]. */
    fun mimeType(path: Path): String

    /** Reads metadata for [path], optionally following symbolic links. */
    fun readAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot

    /** Applies the non-null values in [attributes]; supported fields depend on the implementation. */
    fun writeAttributes(path: Path, attributes: FileAttributesChanges, followLinks: Boolean)

    /** Opens directory entries; a non-directory throws [FileSystemException]. The caller must close the stream. */
    fun streamDirectory(directory: Path): SyncStream<SyncPath>

    /** Copies [source] to [target], optionally replacing it and copying supported attributes. */
    fun copyTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        copyAttributes: Boolean,
        followLinks: Boolean
    ): SyncPath

    /**
     * Moves [source] to [target]. When [atomicMove] is true, an unavailable atomic move throws
     * [AtomicMoveNotSupportedException] instead of falling back to a non-atomic move.
     */
    fun moveTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        atomicMove: Boolean
    ): SyncPath

    /** Checks whether [path] exists, optionally following symbolic links. */
    fun exists(path: Path, followLinks: Boolean): Boolean

    /** Checks whether [path] is absent; this need not be the inverse of [exists] if its status is unknown. */
    fun notExists(path: Path, followLinks: Boolean): Boolean

    /** Checks whether both paths refer to the same file, rather than comparing their spelling. */
    fun isSameFiles(path: Path, other: Path): Boolean

    /** Reads a link's target without resolving it; a non-link throws [NotLinkException]. */
    fun readSymbolicLink(path: Path): SyncPath

    /** Creates a symbolic link at [path] pointing to [target]. */
    fun createSymbolicLink(path: Path, target: Path): SyncPath

    /** Deletes an existing file or empty directory; fails if it is missing. */
    fun deleteExisting(path: Path)

    /** Deletes a file or empty directory, returning false if it was missing. */
    fun deleteIfExists(path: Path): Boolean

    /** Creates one directory, using [permissions] where supported; its parent must exist. */
    fun createDirectory(path: Path, permissions: Set<PosixFilePermission>?): SyncPath

    /** Creates the directory and any missing parents, using [permissions] where supported. */
    fun createDirectories(path: Path, permissions: Set<PosixFilePermission>?): SyncPath

    /** Creates a new empty file and fails if an entry already exists at [path]. */
    fun createFile(path: Path, permissions: Set<PosixFilePermission>?): SyncPath

    /** Returns the storage volume containing [path]. */
    fun fileStore(path: Path): FileStore

    /** Opens a file for reading. The caller must close the returned stream. */
    fun newInputStream(path: Path): SyncInputStream

    /** Opens a file using [mode] and [creation]. The caller must close the returned stream. */
    fun newOutputStream(
        path: Path,
        mode: FileWriteMode,
        creation: FileCreation
    ): SyncOutputStream

    /** Reads the entire file into memory; files exceeding the array size limit throw [FileSystemException]. */
    fun readBytes(path: Path): ByteArray

    /** Writes [bytes] using [mode] and [creation], and returns the target path. */
    fun writeBytes(
        path: Path,
        bytes: ByteArray,
        mode: FileWriteMode,
        creation: FileCreation
    ): SyncPath

    /** Reads the entire file as UTF-8; invalid byte sequences throw [FileSystemException]. */
    fun readString(path: Path): String =
        decodeFileText(path, readBytes(path))

    /** Reads UTF-8 lines without their line separators. */
    fun readLines(path: Path): List<String> =
        splitFileLines(readString(path))

    /**
     * Opens UTF-8 lines for lazy reading, splitting on LF, CR or CRLF without retaining separators.
     * Invalid UTF-8 throws [FileSystemException] during iteration. Close the stream even after
     * exhaustion or partial iteration. An empty file has no lines; a final separator adds no extra line.
     */
    fun lines(path: Path): SyncStream<String> =
        LinesSyncStream(this, path)

    /**
     * Writes UTF-8 [text] using [mode] and [creation].
     * @throws IllegalArgumentException if [text] contains unpaired surrogates; no bytes are written.
     */
    fun writeString(
        path: Path,
        text: String,
        mode: FileWriteMode,
        creation: FileCreation
    ): SyncPath = writeBytes(path, encodeFileText(text), mode, creation)

    /** Writes UTF-8 [lines] using this file system's line separator, including after the last line. */
    fun writeLines(
        path: Path,
        lines: Iterable<String>,
        mode: FileWriteMode,
        creation: FileCreation
    ): SyncPath = writeString(path, joinFileLines(lines, if (separator == "\\") "\r\n" else "\n"), mode, creation)
}

/** A file system with suspending operations. Supported features match the underlying implementation. */
interface AsyncFileSystem: FileSystem {

    override fun path(vararg path: String): AsyncPath
    override fun absolute(path: Path): AsyncPath

    /** Returns the available root directories. */
    suspend fun rootDirectories(): Iterable<AsyncPath>

    /** Returns the storage volumes exposed by this file system. */
    suspend fun fileStores(): Iterable<FileStore>

    /** Returns the absolute path to an existing entry, resolving links when [followLinks] is true. */
    suspend fun realPath(path: Path, followLinks: Boolean): AsyncPath

    /** Checks whether the entry can be executed by the current user. */
    suspend fun isExecutable(path: Path): Boolean

    /** Checks whether the entry can be read by the current user. */
    suspend fun isReadable(path: Path): Boolean

    /** Checks whether the entry can be written by the current user. */
    suspend fun isWritable(path: Path): Boolean

    /** Returns the MIME type inferred for [path]. */
    suspend fun mimeType(path: Path): String

    /** Reads metadata for [path], optionally following symbolic links. */
    suspend fun readAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot

    /** Applies the non-null values in [attributes]; supported fields depend on the implementation. */
    suspend fun writeAttributes(path: Path, attributes: FileAttributesChanges, followLinks: Boolean)

    /** Opens directory entries; a non-directory throws [FileSystemException]. The caller must close the stream. */
    suspend fun streamDirectory(directory: Path): AsyncStream<AsyncPath>

    /** Copies [source] to [target], optionally replacing it and copying supported attributes. */
    suspend fun copyTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        copyAttributes: Boolean,
        followLinks: Boolean
    ): AsyncPath

    /**
     * Moves [source] to [target]. When [atomicMove] is true, an unavailable atomic move throws
     * [AtomicMoveNotSupportedException] instead of falling back to a non-atomic move.
     */
    suspend fun moveTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        atomicMove: Boolean
    ): AsyncPath

    /** Checks whether [path] exists, optionally following symbolic links. */
    suspend fun exists(path: Path, followLinks: Boolean): Boolean

    /** Checks whether [path] is absent; this need not be the inverse of [exists] if its status is unknown. */
    suspend fun notExists(path: Path, followLinks: Boolean): Boolean

    /** Checks whether both paths refer to the same file, rather than comparing their spelling. */
    suspend fun isSameFiles(path: Path, other: Path): Boolean

    /** Reads a link's target without resolving it; a non-link throws [NotLinkException]. */
    suspend fun readSymbolicLink(path: Path): AsyncPath

    /** Creates a symbolic link at [path] pointing to [target]. */
    suspend fun createSymbolicLink(path: Path, target: Path): AsyncPath

    /** Deletes an existing file or empty directory; fails if it is missing. */
    suspend fun deleteExisting(path: Path)

    /** Deletes a file or empty directory, returning false if it was missing. */
    suspend fun deleteIfExists(path: Path): Boolean

    /** Creates one directory, using [permissions] where supported; its parent must exist. */
    suspend fun createDirectory(path: Path, permissions: Set<PosixFilePermission>?): AsyncPath

    /** Creates the directory and any missing parents, using [permissions] where supported. */
    suspend fun createDirectories(path: Path, permissions: Set<PosixFilePermission>?): AsyncPath

    /** Creates a new empty file and fails if an entry already exists at [path]. */
    suspend fun createFile(path: Path, permissions: Set<PosixFilePermission>?): AsyncPath

    /** Returns the storage volume containing [path]. */
    suspend fun fileStore(path: Path): FileStore

    /** Opens a file for reading. The caller must close the returned stream. */
    suspend fun newInputStream(path: Path): AsyncInputStream

    /** Opens a file using [mode] and [creation]. The caller must close the returned stream. */
    suspend fun newOutputStream(
        path: Path,
        mode: FileWriteMode,
        creation: FileCreation
    ): AsyncOutputStream

    /** Reads the entire file into memory; files exceeding the array size limit throw [FileSystemException]. */
    suspend fun readBytes(path: Path): ByteArray

    /** Writes [bytes] using [mode] and [creation], and returns the target path. */
    suspend fun writeBytes(
        path: Path,
        bytes: ByteArray,
        mode: FileWriteMode,
        creation: FileCreation
    ): AsyncPath

    /** Reads the entire file as UTF-8; invalid byte sequences throw [FileSystemException]. */
    suspend fun readString(path: Path): String =
        decodeFileText(path, readBytes(path))

    /** Reads UTF-8 lines without their line separators. */
    suspend fun readLines(path: Path): List<String> =
        splitFileLines(readString(path))

    /**
     * Opens UTF-8 lines for lazy suspending reads, splitting on LF, CR or CRLF without separators.
     * Invalid UTF-8 throws [FileSystemException] during iteration. Close the stream even after
     * exhaustion or partial iteration. An empty file has no lines; a final separator adds no extra line.
     */
    suspend fun lines(path: Path): AsyncStream<String> =
        LinesAsyncStream(this, path, newInputStream(path))

    /**
     * Writes UTF-8 [text] using [mode] and [creation].
     * @throws IllegalArgumentException if [text] contains unpaired surrogates; no bytes are written.
     */
    suspend fun writeString(
        path: Path,
        text: String,
        mode: FileWriteMode,
        creation: FileCreation
    ): AsyncPath = writeBytes(path, encodeFileText(text), mode, creation)

    /** Writes UTF-8 [lines] using this file system's line separator, including after the last line. */
    suspend fun writeLines(
        path: Path,
        lines: Iterable<String>,
        mode: FileWriteMode,
        creation: FileCreation
    ): AsyncPath = writeString(path, joinFileLines(lines, if (separator == "\\") "\r\n" else "\n"), mode, creation)
}

/** Adapts a synchronous file system to suspending operations. Cancellation may not interrupt ongoing I/O. */
interface WrappedAsyncFileSystem: AsyncFileSystem {
    /** The synchronous file system used by this adapter. */
    val wrapped: SyncFileSystem

    override val identity: Any
        get() = wrapped.identity
    
    override val separator: String
        get() = wrapped.separator

    override fun absolute(path: Path): AsyncPath =
        wrapped.absolute(path).asAsync()

    override suspend fun rootDirectories(): Iterable<AsyncPath> = withContext(asyncDispatcher) {
        wrapped.rootDirectories().map { it.asAsync() }
    }

    override suspend fun fileStores(): Iterable<FileStore> = withContext(asyncDispatcher) {
        wrapped.fileStores().toList()
    }

    override suspend fun realPath(path: Path, followLinks: Boolean): AsyncPath = withContext(asyncDispatcher) {
        wrapped.realPath(path, followLinks).asAsync()
    }

    override suspend fun isExecutable(path: Path): Boolean = withContext(asyncDispatcher) {
        wrapped.isExecutable(path)
    }

    override suspend fun isReadable(path: Path): Boolean = withContext(asyncDispatcher) {
        wrapped.isReadable(path)
    }

    override suspend fun isWritable(path: Path): Boolean = withContext(asyncDispatcher) {
        wrapped.isWritable(path)
    }

    override suspend fun readAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot = withContext(asyncDispatcher) {
        wrapped.readAttributes(path, followLinks)
    }

    override suspend fun writeAttributes(path: Path, attributes: FileAttributesChanges, followLinks: Boolean) = withContext(asyncDispatcher) {
        wrapped.writeAttributes(path, attributes, followLinks)
    }

    override suspend fun streamDirectory(directory: Path): AsyncStream<AsyncPath> = withContext(asyncDispatcher) {
        object : AsyncStream<AsyncPath> {
            val stream = wrapped.streamDirectory(directory)

            override suspend fun next(): AsyncPath = withContext(asyncDispatcher) {
                stream.next().asAsync()
            }

            override suspend fun hasNext(): Boolean = withContext(asyncDispatcher) {
                stream.hasNext()
            }

            override suspend fun close() = withContext(asyncDispatcher) {
                stream.close()
            }
        }
    }

    override suspend fun copyTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        copyAttributes: Boolean,
        followLinks: Boolean
    ): AsyncPath = withContext(asyncDispatcher) {
        wrapped.copyTo(source, target, overwrite, copyAttributes, followLinks).asAsync()
    }

    override suspend fun moveTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        atomicMove: Boolean
    ): AsyncPath = withContext(asyncDispatcher) {
        wrapped.moveTo(source, target, overwrite, atomicMove).asAsync()
    }

    override suspend fun exists(path: Path, followLinks: Boolean): Boolean = withContext(asyncDispatcher) {
        wrapped.exists(path, followLinks)
    }

    override suspend fun notExists(path: Path, followLinks: Boolean): Boolean = withContext(asyncDispatcher) {
        wrapped.notExists(path, followLinks)
    }

    override suspend fun isSameFiles(path: Path, other: Path): Boolean = withContext(asyncDispatcher) {
        wrapped.isSameFiles(path, other)
    }

    override suspend fun readSymbolicLink(path: Path): AsyncPath = withContext(asyncDispatcher) {
        wrapped.readSymbolicLink(path).asAsync()
    }

    override suspend fun createSymbolicLink(path: Path, target: Path): AsyncPath = withContext(asyncDispatcher) {
        wrapped.createSymbolicLink(path, target).asAsync()
    }

    override suspend fun deleteExisting(path: Path) = withContext(asyncDispatcher) {
        wrapped.deleteExisting(path)
    }

    override suspend fun deleteIfExists(path: Path): Boolean = withContext(asyncDispatcher) {
        wrapped.deleteIfExists(path)
    }

    override suspend fun createDirectory(path: Path, permissions: Set<PosixFilePermission>?): AsyncPath = withContext(asyncDispatcher) {
        wrapped.createDirectory(path, permissions).asAsync()
    }

    override suspend fun createDirectories(path: Path, permissions: Set<PosixFilePermission>?): AsyncPath = withContext(asyncDispatcher) {
        wrapped.createDirectories(path, permissions).asAsync()
    }

    override suspend fun createFile(path: Path, permissions: Set<PosixFilePermission>?): AsyncPath = withContext(asyncDispatcher) {
        wrapped.createFile(path, permissions).asAsync()
    }

    override suspend fun fileStore(path: Path): FileStore = withContext(asyncDispatcher) {
        wrapped.fileStore(path)
    }

    override suspend fun newInputStream(path: Path): AsyncInputStream = withContext(asyncDispatcher) {
        wrapped.newInputStream(path).asAsync()
    }

    override suspend fun newOutputStream(path: Path, mode: FileWriteMode, creation: FileCreation): AsyncOutputStream = withContext(asyncDispatcher) {
        wrapped.newOutputStream(path, mode, creation).asAsync()
    }

    override suspend fun readBytes(path: Path): ByteArray = withContext(asyncDispatcher) {
        wrapped.readBytes(path)
    }

    override suspend fun writeBytes(
        path: Path,
        bytes: ByteArray,
        mode: FileWriteMode,
        creation: FileCreation
    ): AsyncPath = withContext(asyncDispatcher) {
        wrapped.writeBytes(path, bytes, mode, creation).asAsync()
    }

    override suspend fun mimeType(path: Path): String = withContext(asyncDispatcher) {
        wrapped.mimeType(path)
        }

    override fun asSync(): SyncFileSystem? = wrapped
    override fun asAsync(): AsyncFileSystem = this
}

/** Provides suspending access to a synchronous user file system's standard directories. */
interface WrappedAsyncUserFileSystem: AsyncUserFileSystem {
    /** The synchronous user file system used by this adapter. */
    val wrapped: SyncUserFileSystem

    override fun workingDir(): AsyncPath =
        wrapped.workingDir().asAsync()

    override suspend fun homeDir(): AsyncPath? = withContext(asyncDispatcher) {
        wrapped.homeDir()?.asAsync()
    }

    override suspend fun tmpDir(): AsyncPath = withContext(asyncDispatcher) {
        wrapped.tmpDir().asAsync()
    }

    override suspend fun documentsDir(): AsyncPath? = withContext(asyncDispatcher) {
        wrapped.documentsDir()?.asAsync()
    }

    override suspend fun downloadsDir(): AsyncPath? = withContext(asyncDispatcher) {
        wrapped.downloadsDir()?.asAsync()
    }

    override suspend fun picturesDir(): AsyncPath? = withContext(asyncDispatcher) {
        wrapped.picturesDir()?.asAsync()
    }

    override suspend fun videosDir(): AsyncPath? = withContext(asyncDispatcher) {
        wrapped.videosDir()?.asAsync()
    }

    override suspend fun musicDir(): AsyncPath? = withContext(asyncDispatcher) {
        wrapped.musicDir()?.asAsync()
    }

    override suspend fun desktopDir(): AsyncPath? = withContext(asyncDispatcher) {
        wrapped.desktopDir()?.asAsync()
    }
}
