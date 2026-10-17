@file:Suppress("unused")

package com.huskerdev.stdlibx.io.fs

import com.huskerdev.stdlibx.io.*

/**
 * A path within a [fileSystem]. Constructing or transforming it does not require the entry to exist.
 * File operations use the portable exception types documented by [FileSystem].
 */
interface Path: WritableResource {
    companion object {
        /**
         * Creates a path in [FileSystem.default].
         * @throws IllegalStateException if no default file system is available.
         */
        operator fun invoke(vararg path: String): Path =
            FileSystem.default?.path(*path)
                ?: throw IllegalStateException("No default filesystem is available")

        /** Creates a default-file-system path, or returns null if synchronous access is unavailable. */
        fun syncOf(vararg path: String) = Path(*path).asSync()
        /** Creates an asynchronous path in the default file system. */
        fun asyncOf(vararg path: String) = Path(*path).asAsync()
    }

    /** The file system that owns this path. */
    val fileSystem: FileSystem

    /** The path's string representation using its file system's syntax. */
    val pathString: String

    /** The name components, excluding the root. */
    val names: List<String>

    /** Whether the path is fully qualified rather than relative to a working directory. */
    val isAbsolute: Boolean

    /** The path represented with forward slashes as separators. */
    val invariantSeparatorsPath: String

    /** The final name component, or an empty string if there is none. */
    val name: String

    /** The portion of [name] after its last dot, or an empty string if there is no dot. */
    val extension: String

    /** The final name with its last extension removed. */
    val nameWithoutExtension: String

    /** The parent path, or null if this path has no parent. */
    val parent: Path?

    /** The root component, or null if this path has no root. */
    val root: Path?

    /** Whether this path has an empty string representation. */
    val isEmpty: Boolean

    /** Removes redundant `.` and `..` components where possible, without accessing the file system. */
    fun normalize(): Path

    /** Checks a prefix by path components rather than by string characters. */
    fun startsWith(other: Path): Boolean

    /** Parses [other] in this file system and checks it as a path prefix. */
    fun startsWith(other: String): Boolean = startsWith(fileSystem.path(other))

    /** Checks a suffix by path components rather than by string characters. */
    fun endsWith(other: Path): Boolean

    /** Parses [other] in this file system and checks it as a path suffix. */
    fun endsWith(other: String): Boolean = endsWith(fileSystem.path(other))

    /** Resolves [other] against this path, using this file system's path rules. */
    fun resolve(other: Path): Path

    /** Parses [other] in this file system and resolves it against this path. */
    fun resolve(other: String): Path = resolve(fileSystem.path(other))

    /** Returns the relative path from [other] to this path; fails if the paths are incompatible. */
    fun relativeTo(other: Path): Path

    /** Parses [other] in this file system and returns the relative path from it to this path. */
    fun relativeTo(other: String): Path = relativeTo(fileSystem.path(other))

    /** Like [relativeTo], but returns null when a relative path cannot be formed. */
    fun relativeToOrNull(other: Path): Path? = try {
        relativeTo(other)
    } catch (_: IllegalArgumentException) { null }

    /** Like [relativeTo], but returns null when a relative path cannot be formed. */
    fun relativeToOrNull(other: String): Path? = relativeToOrNull(fileSystem.path(other))

    /** Like [relativeTo], but returns this path when a relative path cannot be formed. */
    fun relativeToOrSelf(other: Path): Path = relativeToOrNull(other) ?: this

    /** Like [relativeTo], but returns this path when a relative path cannot be formed. */
    fun relativeToOrSelf(other: String): Path = relativeToOrNull(other) ?: this

    /** Resolves [other] against this path's parent, or returns [other] if there is no parent. */
    fun resolveSibling(other: Path): Path {
        fileSystem.assertFS(other)
        return parent?.resolve(other) ?: fileSystem.path(other.pathString)
    }

    /** Parses [other] in this file system and resolves it against this path's parent. */
    fun resolveSibling(other: String): Path = resolveSibling(fileSystem.path(other))

    /** Returns an absolute path using the file system's working directory when needed. */
    fun absolute(): Path = fileSystem.absolute(this)

    /** Returns the string representation of [absolute]. */
    fun absoluteString(): String = absolute().pathString

    /** Returns name components from [beginIndex] inclusive to [endIndex] exclusive, without a root. */
    fun subpath(beginIndex: Int, endIndex: Int): Path {
        val names = names
        if(beginIndex < 0 ||
            beginIndex >= names.size ||
            endIndex > names.size ||
            beginIndex >= endIndex
        ) throw IllegalArgumentException()
        return fileSystem.path(
            names.subList(beginIndex, endIndex).joinToString(separator = fileSystem.separator)
        )
    }

    override fun asSync(): SyncPath?
    override fun asAsync(): AsyncPath
}

/** A path with blocking file operations. Unsupported metadata is returned as null where applicable. */
interface SyncPath: Path, SyncWritableResource {
    override val fileSystem: SyncFileSystem

    override fun mimeType(): String = fileSystem.mimeType(this)

    /** Reads whether the entry is a regular file. */
    fun isRegularFile(followLinks: Boolean = true) = readAttributes(followLinks).isRegularFile

    /** Reads whether the entry is a directory. */
    fun isDirectory(followLinks: Boolean = true) = readAttributes(followLinks).isDirectory

    /** Reads whether the entry itself is a symbolic link. */
    fun isSymbolicLink() = readAttributes(false).isSymbolicLink

    /** Reads whether the entry is hidden, without following links by default. */
    fun isHidden(followLinks: Boolean = false) = readAttributes(followLinks).isHidden

    /** Returns the reported size in bytes, or null if unavailable. */
    fun size(followLinks: Boolean = true) = readAttributes(followLinks).size

    /** Reads the entry's metadata, optionally following symbolic links. */
    fun readAttributes(followLinks: Boolean = true) = fileSystem.readAttributes(this, followLinks)

    /** Applies the non-null changes in [attributes], where supported. */
    fun writeAttributes(attributes: FileAttributesChanges, followLinks: Boolean = true) = fileSystem.writeAttributes(this, attributes, followLinks)

    /** Checks whether the current user can execute the entry. */
    fun isExecutable() = fileSystem.isExecutable(this)

    /** Checks whether the current user can read the entry. */
    fun isReadable() = fileSystem.isReadable(this)

    /** Checks whether the current user can write the entry. */
    fun isWritable() = fileSystem.isWritable(this)

    /** Returns the absolute path to this existing entry, resolving links by default. */
    fun realPath(followLinks: Boolean = true) = fileSystem.realPath(this, followLinks)

    /** Checks whether this entry exists, following symbolic links by default. */
    fun exists(followLinks: Boolean = true): Boolean = fileSystem.exists(this, followLinks)

    /** Checks whether this entry is absent; an unknown status need not be the inverse of [exists]. */
    fun notExist(followLinks: Boolean = true) = fileSystem.notExists(this, followLinks)

    /** Checks whether [other] refers to the same file, regardless of path spelling. */
    fun isSameFileAs(other: Path): Boolean = fileSystem.isSameFiles(this, other)

    /** Reads this link's target without making it absolute; a non-link throws [NotLinkException]. */
    fun readSymbolicLink(): SyncPath = fileSystem.readSymbolicLink(this)

    /** Creates a symbolic link at this path pointing to [target]. */
    fun createSymbolicLink(target: Path): SyncPath = fileSystem.createSymbolicLink(this, target)

    /** Deletes this file or empty directory; fails if it is missing. */
    fun deleteExisting() = fileSystem.deleteExisting(this)

    /** Deletes this file or empty directory, returning false if it was missing. */
    fun deleteIfExists(): Boolean = fileSystem.deleteIfExists(this)

    /** Creates this directory; its parent must exist. */
    fun createDirectory(permissions: Set<PosixFilePermission>? = null): SyncPath = fileSystem.createDirectory(this, permissions)

    /** Creates this directory and any missing parents. */
    fun createDirectories(permissions: Set<PosixFilePermission>? = null): SyncPath = fileSystem.createDirectories(this, permissions)

    /** Creates a new empty file and fails if the path already exists. */
    fun createFile(permissions: Set<PosixFilePermission>? = null): SyncPath = fileSystem.createFile(this, permissions)

    /** Creates missing parent directories and returns this path without creating the file itself. */
    fun createParentDirectories(permission: Set<PosixFilePermission>? = null): SyncPath {
        val parent = parent
        if(parent != null && !parent.exists())
            parent.createDirectories(permission)
        return this
    }

    /** Returns the storage volume containing this entry. */
    fun fileStore(): FileStore = fileSystem.fileStore(this)

    /** Opens this directory's immediate entries. The caller must close the returned stream. */
    fun streamDirectory(): SyncStream<SyncPath> = fileSystem.streamDirectory(this)

    /** Collects this directory's immediate entries into a list and closes the directory stream. */
    fun list(): List<SyncPath> = fileSystem.streamDirectory(this).use { it.asSequence().toList() }

    /** Copies this entry to [target], optionally replacing it and copying supported attributes. */
    fun copyTo(
        target: Path,
        overwrite: Boolean = false,
        copyAttributes: Boolean = false,
        followLinks: Boolean = true
    ) = fileSystem.copyTo(this, target, overwrite, copyAttributes, followLinks)

    /** Moves this entry to [target]; an unavailable requested atomic move throws [AtomicMoveNotSupportedException]. */
    fun moveTo(
        target: Path,
        overwrite: Boolean = false,
        atomicMove: Boolean = false
    ) = fileSystem.moveTo(this, target, overwrite, atomicMove)

    /** Reads the entire file into memory; files exceeding the array size limit throw [FileSystemException]. */
    fun readBytes(): ByteArray = fileSystem.readBytes(this)

    /** Reads the entire file as UTF-8; invalid byte sequences throw [FileSystemException]. */
    fun readString(): String = fileSystem.readString(this)

    /** Reads UTF-8 lines without their line separators. */
    fun readLines(): List<String> = fileSystem.readLines(this)

    /** Opens lazy UTF-8 lines without separators. The caller must close the stream, including after partial iteration. */
    fun lines(): SyncStream<String> = fileSystem.lines(this)

    /** Writes [bytes]; by default creates a missing file or replaces existing contents. */
    fun writeBytes(
        bytes: ByteArray,
        mode: FileWriteMode = FileWriteMode.TRUNCATE,
        creation: FileCreation = FileCreation.CREATE_IF_MISSING
    ): SyncPath = fileSystem.writeBytes(this, bytes, mode, creation)

    /**
     * Writes UTF-8 [text], creating a missing file or replacing contents by default.
     * @throws IllegalArgumentException if [text] contains unpaired surrogates; the file is unchanged.
     */
    fun writeString(
        text: String,
        mode: FileWriteMode = FileWriteMode.TRUNCATE,
        creation: FileCreation = FileCreation.CREATE_IF_MISSING
    ): SyncPath = fileSystem.writeString(this, text, mode, creation)

    /** Writes UTF-8 [lines], adding the file system's line separator after each line. */
    fun writeLines(
        lines: Iterable<String>,
        mode: FileWriteMode = FileWriteMode.TRUNCATE,
        creation: FileCreation = FileCreation.CREATE_IF_MISSING
    ): SyncPath = fileSystem.writeLines(this, lines, mode, creation)

    override fun newInputStream(): SyncInputStream = fileSystem.newInputStream(this)

    /** Opens a stream that creates a missing file or discards existing contents. The caller must close it. */
    override fun newOutputStream(): SyncOutputStream =
        newOutputStream(FileWriteMode.TRUNCATE, FileCreation.CREATE_IF_MISSING)

    /** Opens an output stream using [mode] and [creation]. The caller must close it. */
    fun newOutputStream(
        mode: FileWriteMode = FileWriteMode.TRUNCATE,
        creation: FileCreation = FileCreation.CREATE_IF_MISSING
    ): SyncOutputStream = fileSystem.newOutputStream(this, mode, creation)

    /** Visits this path and its descendants up to [maxDepth], without following links by default. */
    fun walkTree(
        maxDepth: Int = Int.MAX_VALUE,
        followLinks: Boolean = false,
        visitor: SyncFileTreeVisitor
    ): Unit = FileTreeWalker(maxDepth, followLinks).walk(this, visitor)

    /** Calls [visitor] once for each visited path, including this starting path. */
    fun walkTree(
        maxDepth: Int = Int.MAX_VALUE,
        followLinks: Boolean = false,
        visitor: (SyncPath, FileAttributesSnapshot) -> FileTreeVisitResult
    ): Unit = FileTreeWalker(maxDepth, followLinks).walk(this, visitor)

    /** Deletes this entry and its descendants without following symbolic links. */
    suspend fun deleteRecursively() {
        FileTreeWalker(followLinks = false).walk(this, object : SyncFileTreeVisitor {
            private fun deleteAndContinue(path: SyncPath): FileTreeVisitResult {
                path.deleteExisting()
                return FileTreeVisitResult.CONTINUE
            }
            override fun visit(path: SyncPath, attributes: FileAttributesSnapshot) = deleteAndContinue(path)
            override fun postVisitDirectory(path: SyncPath, attributes: FileAttributesSnapshot) = deleteAndContinue(path)
        })
    }

    override fun asSync(): SyncPath = this

    // =====

    override val parent: SyncPath?
    override val root: SyncPath?

    override fun normalize(): SyncPath

    override fun resolve(other: Path): SyncPath
    override fun resolve(other: String): SyncPath = super.resolve(other) as SyncPath

    override fun relativeTo(other: Path): SyncPath
    override fun relativeTo(other: String): SyncPath = super.relativeTo(other) as SyncPath

    override fun relativeToOrNull(other: String): SyncPath? = super.relativeToOrNull(other) as SyncPath?
    override fun relativeToOrNull(other: Path): SyncPath? = super.relativeToOrNull(other) as SyncPath?
    override fun relativeToOrSelf(other: Path): SyncPath = super.relativeToOrSelf(other) as SyncPath
    override fun relativeToOrSelf(other: String): SyncPath = super.relativeToOrSelf(other) as SyncPath

    override fun resolveSibling(other: Path): SyncPath = super.resolveSibling(other) as SyncPath
    override fun resolveSibling(other: String): SyncPath = super.resolveSibling(other) as SyncPath

    override fun absolute(): SyncPath = super.absolute() as SyncPath

    override fun subpath(beginIndex: Int, endIndex: Int): SyncPath = super.subpath(beginIndex, endIndex) as SyncPath
}

/** A path with suspending file operations. Unsupported metadata is returned as null where applicable. */
interface AsyncPath: Path, AsyncWritableResource {
    override val fileSystem: AsyncFileSystem

    override suspend fun mimeType(): String = fileSystem.mimeType(this)

    /** Reads whether the entry is a regular file. */
    suspend fun isRegularFile(followLinks: Boolean = true) = readAttributes(followLinks).isRegularFile

    /** Reads whether the entry is a directory. */
    suspend fun isDirectory(followLinks: Boolean = true) = readAttributes(followLinks).isDirectory

    /** Reads whether the entry itself is a symbolic link. */
    suspend fun isSymbolicLink() = readAttributes(false).isSymbolicLink

    /** Reads whether the entry is hidden, without following links by default. */
    suspend fun isHidden(followLinks: Boolean = false) = readAttributes(followLinks).isHidden

    /** Returns the reported size in bytes, or null if unavailable. */
    suspend fun size(followLinks: Boolean = true) = readAttributes(followLinks).size

    /** Reads the entry's metadata, optionally following symbolic links. */
    suspend fun readAttributes(followLinks: Boolean = true) = fileSystem.readAttributes(this, followLinks)

    /** Applies the non-null changes in [attributes], where supported. */
    suspend fun writeAttributes(attributes: FileAttributesChanges, followLinks: Boolean = true) = fileSystem.writeAttributes(this, attributes, followLinks)

    /** Checks whether the current user can execute the entry. */
    suspend fun isExecutable() = fileSystem.isExecutable(this)

    /** Checks whether the current user can read the entry. */
    suspend fun isReadable() = fileSystem.isReadable(this)

    /** Checks whether the current user can write the entry. */
    suspend fun isWritable() = fileSystem.isWritable(this)

    /** Returns the absolute path to this existing entry, resolving links by default. */
    suspend fun realPath(followLinks: Boolean = true) = fileSystem.realPath(this, followLinks)

    /** Checks whether this entry exists, following symbolic links by default. */
    suspend fun exists(followLinks: Boolean = true): Boolean = fileSystem.exists(this, followLinks)

    /** Checks whether this entry is absent; an unknown status need not be the inverse of [exists]. */
    suspend fun notExist(followLinks: Boolean = true): Boolean = fileSystem.notExists(this, followLinks)

    /** Checks whether [other] refers to the same file, regardless of path spelling. */
    suspend fun isSameFileAs(other: Path): Boolean = fileSystem.isSameFiles(this, other)

    /** Reads this link's target without making it absolute; a non-link throws [NotLinkException]. */
    suspend fun readSymbolicLink(): AsyncPath = fileSystem.readSymbolicLink(this)

    /** Creates a symbolic link at this path pointing to [target]. */
    suspend fun createSymbolicLink(target: Path): AsyncPath = fileSystem.createSymbolicLink(this, target)

    /** Deletes this file or empty directory; fails if it is missing. */
    suspend fun deleteExisting() = fileSystem.deleteExisting(this)

    /** Deletes this file or empty directory, returning false if it was missing. */
    suspend fun deleteIfExists(): Boolean = fileSystem.deleteIfExists(this)

    /** Creates this directory; its parent must exist. */
    suspend fun createDirectory(permissions: Set<PosixFilePermission>? = null): AsyncPath = fileSystem.createDirectory(this, permissions)

    /** Creates this directory and any missing parents. */
    suspend fun createDirectories(permissions: Set<PosixFilePermission>? = null): AsyncPath = fileSystem.createDirectories(this, permissions)

    /** Creates a new empty file and fails if the path already exists. */
    suspend fun createFile(permissions: Set<PosixFilePermission>? = null): AsyncPath = fileSystem.createFile(this, permissions)

    /** Creates missing parent directories and returns this path without creating the file itself. */
    suspend fun createParentDirectories(permission: Set<PosixFilePermission>? = null): AsyncPath {
        val parent = parent
        if(parent != null && !parent.exists())
            parent.createDirectories(permission)
        return this
    }

    /** Returns the storage volume containing this entry. */
    suspend fun fileStore(): FileStore = fileSystem.fileStore(this)

    /** Opens this directory's immediate entries. The caller must close the returned stream. */
    suspend fun streamDirectory(): AsyncStream<AsyncPath> = fileSystem.streamDirectory(this)

    /** Collects this directory's immediate entries into a list and closes the directory stream. */
    suspend fun list(): List<AsyncPath> = fileSystem.streamDirectory(this).use {
        buildList { while(it.hasNext()) add(it.next()) }
    }

    /** Copies this entry to [target], optionally replacing it and copying supported attributes. */
    suspend fun copyTo(
        target: Path,
        overwrite: Boolean = false,
        copyAttributes: Boolean = false,
        followLinks: Boolean = true
    ) = fileSystem.copyTo(this, target, overwrite, copyAttributes, followLinks)

    /** Moves this entry to [target]; an unavailable requested atomic move throws [AtomicMoveNotSupportedException]. */
    suspend fun moveTo(
        target: Path,
        overwrite: Boolean = false,
        atomicMove: Boolean = false
    ) = fileSystem.moveTo(this, target, overwrite, atomicMove)

    /** Reads the entire file into memory; files exceeding the array size limit throw [FileSystemException]. */
    suspend fun readBytes(): ByteArray = fileSystem.readBytes(this)

    /** Reads the entire file as UTF-8; invalid byte sequences throw [FileSystemException]. */
    suspend fun readString(): String = fileSystem.readString(this)

    /** Reads UTF-8 lines without their line separators. */
    suspend fun readLines(): List<String> = fileSystem.readLines(this)

    /** Opens lazy UTF-8 lines without separators. The caller must close the stream, including after partial iteration. */
    suspend fun lines(): AsyncStream<String> = fileSystem.lines(this)

    /** Writes [bytes]; by default creates a missing file or replaces existing contents. */
    suspend fun writeBytes(
        bytes: ByteArray,
        mode: FileWriteMode = FileWriteMode.TRUNCATE,
        creation: FileCreation = FileCreation.CREATE_IF_MISSING
    ): AsyncPath = fileSystem.writeBytes(this, bytes, mode, creation)

    /**
     * Writes UTF-8 [text], creating a missing file or replacing contents by default.
     * @throws IllegalArgumentException if [text] contains unpaired surrogates; the file is unchanged.
     */
    suspend fun writeString(
        text: String,
        mode: FileWriteMode = FileWriteMode.TRUNCATE,
        creation: FileCreation = FileCreation.CREATE_IF_MISSING
    ): AsyncPath = fileSystem.writeString(this, text, mode, creation)

    /** Writes UTF-8 [lines], adding the file system's line separator after each line. */
    suspend fun writeLines(
        lines: Iterable<String>,
        mode: FileWriteMode = FileWriteMode.TRUNCATE,
        creation: FileCreation = FileCreation.CREATE_IF_MISSING
    ): AsyncPath = fileSystem.writeLines(this, lines, mode, creation)

    override suspend fun newInputStream(): AsyncInputStream = fileSystem.newInputStream(this)

    /** Opens a stream that creates a missing file or discards existing contents. The caller must close it. */
    override suspend fun newOutputStream(): AsyncOutputStream =
        newOutputStream(FileWriteMode.TRUNCATE, FileCreation.CREATE_IF_MISSING)

    /** Opens an output stream using [mode] and [creation]. The caller must close it. */
    suspend fun newOutputStream(
        mode: FileWriteMode = FileWriteMode.TRUNCATE,
        creation: FileCreation = FileCreation.CREATE_IF_MISSING
    ): AsyncOutputStream = fileSystem.newOutputStream(this, mode, creation)

    /** Visits this path and its descendants up to [maxDepth], without following links by default. */
    suspend fun walkTree(
        maxDepth: Int = Int.MAX_VALUE,
        followLinks: Boolean = false,
        visitor: AsyncFileTreeVisitor
    ): Unit = FileTreeWalker(maxDepth, followLinks).walk(this, visitor)

    /** Calls [visitor] once for each visited path, including this starting path. */
    suspend fun walkTree(
        maxDepth: Int = Int.MAX_VALUE,
        followLinks: Boolean = false,
        visitor: suspend (AsyncPath, FileAttributesSnapshot) -> FileTreeVisitResult
    ): Unit = FileTreeWalker(maxDepth, followLinks).walk(this, visitor)

    /** Deletes this entry and its descendants without following symbolic links. */
    suspend fun deleteRecursively() {
        FileTreeWalker(followLinks = false).walk(this, object : AsyncFileTreeVisitor {
            private suspend fun deleteAndContinue(path: AsyncPath): FileTreeVisitResult {
                path.deleteExisting()
                return FileTreeVisitResult.CONTINUE
            }
            override suspend fun visit(path: AsyncPath, attributes: FileAttributesSnapshot) = deleteAndContinue(path)
            override suspend fun postVisitDirectory(path: AsyncPath, attributes: FileAttributesSnapshot) =
                deleteAndContinue(path)
        })
    }

    override fun asAsync(): AsyncPath = this

    // =====

    override val parent: AsyncPath?
    override val root: AsyncPath?

    override fun normalize(): AsyncPath

    override fun resolve(other: Path): AsyncPath
    override fun resolve(other: String): AsyncPath = super.resolve(other) as AsyncPath

    override fun relativeTo(other: Path): AsyncPath
    override fun relativeTo(other: String): AsyncPath = super.relativeTo(other) as AsyncPath

    override fun relativeToOrNull(other: String): AsyncPath? = super.relativeToOrNull(other) as AsyncPath?
    override fun relativeToOrNull(other: Path): AsyncPath? = super.relativeToOrNull(other) as AsyncPath?
    override fun relativeToOrSelf(other: Path): AsyncPath = super.relativeToOrSelf(other) as AsyncPath
    override fun relativeToOrSelf(other: String): AsyncPath = super.relativeToOrSelf(other) as AsyncPath

    override fun resolveSibling(other: Path): AsyncPath = super.resolveSibling(other) as AsyncPath
    override fun resolveSibling(other: String): AsyncPath = super.resolveSibling(other) as AsyncPath

    override fun absolute(): AsyncPath = super.absolute() as AsyncPath

    override fun subpath(beginIndex: Int, endIndex: Int): AsyncPath = super.subpath(beginIndex, endIndex) as AsyncPath
}
