@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.io.fs.opfs

import com.huskerdev.stdlibx.io.*
import com.huskerdev.stdlibx.io.fs.*
import com.huskerdev.stdlibx.io.fs.unix.AsyncUnixFileSystem
import kotlinx.coroutines.*
import kotlin.time.Instant

internal fun isOpfsSupported(): Boolean =
    js("typeof navigator !== 'undefined' && " +
        "typeof navigator.storage !== 'undefined' && " +
        "typeof navigator.storage.getDirectory === 'function'")

private val opfsIdentity = Any()

/**
 * Origin-private, asynchronous browser filesystem. Paths are rooted at the origin's OPFS root,
 * not the host filesystem, and are normalized lexically before lookup. Requires a secure
 * context; mutations also require Web Locks.
 *
 * Exclusive creation is serialized between clients of this library using an origin-wide lock.
 * Raw OPFS users bypass that protocol: the browser has no atomic create-if-absent primitive.
 * Writers use createWritable's exclusive mode; changes become visible only on close, not flush.
 * APPEND chooses the end at open time, rather than providing POSIX atomic append semantics.
 * Streams/views must not be used concurrently. No synchronous view is exposed.
 *
 * Symlinks, executable permissions, attribute changes and atomic moves are not supported.
 * Copy is shallow for directories; non-atomic move copies the tree before deleting its source.
 * Storage statistics describe the origin's estimated storage budget, shared with other APIs.
 */
class OpfsFileSystem: AsyncUnixFileSystem, AsyncUserFileSystem {

    override val identity: Any = opfsIdentity

    private var rootHandle: OpfsHandle? = null

    override val separator: String = "/"

    override fun workingDir(): AsyncPath = path("/")

    override suspend fun homeDir(): AsyncPath? = null
    override suspend fun tmpDir(): AsyncPath = createDirectories(path("/tmp"), null)
    override suspend fun documentsDir(): AsyncPath? = null
    override suspend fun downloadsDir(): AsyncPath? = null
    override suspend fun picturesDir(): AsyncPath? = null
    override suspend fun videosDir(): AsyncPath? = null
    override suspend fun musicDir(): AsyncPath? = null
    override suspend fun desktopDir(): AsyncPath? = null
    override suspend fun rootDirectories(): Iterable<AsyncPath> = listOf(path("/"))

    private fun normalized(path: Path): AsyncPath = absolute(path).normalize()
    private fun path(path: Path): AsyncPath = this.path(path.pathString)

    private suspend fun root(): OpfsHandle {
        currentCoroutineContext().ensureActive()
        if (!isOpfsSupported()) throw UnsupportedOperationException("OPFS is not available in this browser/context")

        return rootHandle ?: opfsCall(path("/")) {
            opfsRoot().awaitOpfs()
        }.also {
            rootHandle = it
        }
    }

    private suspend fun directory(path: Path): OpfsHandle {
        val addressed = normalized(path)
        var handle = root()
        for (name in addressed.names) {
            handle = opfsCall(path) {
                try {
                    handle.getDirectoryHandle(name, opfsCreateOptions(false)).awaitOpfs()
                } catch (error: JsException) {
                    if (opfsError(error.thrownValue)?.name == "TypeMismatchError")
                        throw FileSystemException(path, "Not a directory", error)
                    throw error
                }
            }
        }
        return handle
    }

    private suspend fun parent(path: Path): OpfsHandle =
        directory(normalized(path).parent
        ?: throw FileSystemException(path, "Operation is not permitted on the OPFS root"))

    private data class EntryLocation(
        val path: AsyncPath,
        val parent: OpfsHandle?,
        val handle: OpfsHandle?
    )

    private suspend fun locate(path: Path): EntryLocation {
        val target = normalized(path)
        if (target.names.isEmpty())
            return EntryLocation(target, null, root())
        val parent = parent(target)
        val handle = try {
            lookup(parent, target)
        } catch (_: NoSuchFileException) { null }
        return EntryLocation(target, parent, handle)
    }

    private suspend fun lookup(parent: OpfsHandle, path: AsyncPath): OpfsHandle =
        try {
            // TypeMismatchError means the existing entry is a directory, not an absent file.
            parent.getFileHandle(path.name, opfsCreateOptions(false)).awaitOpfs()
        } catch (error: JsException) {
            if (opfsError(error.thrownValue)?.name != "TypeMismatchError")
                opfsCall(path) { throw error }
            opfsCall(path) {
                parent.getDirectoryHandle(path.name, opfsCreateOptions(false)).awaitOpfs()
            }
        }

    private suspend fun entry(path: Path): OpfsHandle =
        locate(path).handle ?: throw NoSuchFileException(path)

    private suspend fun file(path: Path): OpfsFile {
        val entry = entry(path)
        if (entry.kind != "file")
            throw FileSystemException(path, "Not a regular file")
        return opfsCall(path) { entry.getFile().awaitOpfs() }
    }

    override suspend fun realPath(path: Path, followLinks: Boolean): AsyncPath {
        entry(path)
        return normalized(path)
    }

    override suspend fun exists(path: Path, followLinks: Boolean): Boolean = try {
        assertFS(path)
        entry(path)
        true
    } catch (_: NoSuchFileException) { false } catch (_: FileSystemException) { false }

    override suspend fun notExists(path: Path, followLinks: Boolean): Boolean = try {
        assertFS(path)
        entry(path)
        false
    } catch (_: NoSuchFileException) { true } catch (_: FileSystemException) { false }

    override suspend fun isReadable(path: Path): Boolean = exists(path, true)
    override suspend fun isWritable(path: Path): Boolean = exists(path, true)

    override suspend fun isExecutable(path: Path): Boolean {
        assertFS(path)
        return false
    }

    override suspend fun mimeType(path: Path): String {
        val entry = entry(path)
        return if (entry.kind == "file") {
            opfsCall(path) {
                entry.getFile().awaitOpfs().type
            }.ifBlank { fallbackMimeType(path.extension) }
        } else ""
    }

    override suspend fun readAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot {
        val entry = entry(path)
        val file = if (entry.kind == "file") opfsCall(path) {
            entry.getFile().awaitOpfs()
        } else null

        return FileAttributesSnapshot(
            isRegularFile = file != null,
            isDirectory = entry.kind == "directory",
            isSymbolicLink = false,
            size = file?.size?.opfsSize(),
            isHidden = entry.name.startsWith('.'),
            lastModifiedTime = file?.let { Instant.fromEpochMilliseconds(it.lastModified.toLong()) }
        )
    }

    override suspend fun writeAttributes(path: Path, attributes: FileAttributesChanges, followLinks: Boolean) {
        entry(path)
        if (attributes != FileAttributesChanges())
            throw UnsupportedOperationException("OPFS does not expose writable file attributes")
    }

    override suspend fun streamDirectory(directory: Path): AsyncStream<AsyncPath> {
        val entries = OpfsDirectoryEntries(directory, this.directory(directory))
        val path = path(directory)
        return OpfsDirectoryStream(entries, path)
    }

    override suspend fun isSameFiles(path: Path, other: Path): Boolean {
        assertFS(path)
        assertFS(other)
        val first = entry(path)
        val second = entry(other)
        return opfsCall(path) {
            first.isSameEntry(second).awaitOpfs().toBoolean()
        }
    }

    override suspend fun readSymbolicLink(path: Path): AsyncPath {
        assertFS(path)
        throw UnsupportedOperationException("OPFS has no symbolic links")
    }

    override suspend fun createSymbolicLink(path: Path, target: Path): AsyncPath {
        assertFS(path)
        assertFS(target)
        throw UnsupportedOperationException("OPFS has no symbolic links")
    }

    private suspend fun remove(path: Path) {
        val target = normalized(path)
        opfsCall(path) {
            parent(target).removeEntry(target.name).awaitOpfs()
        }
    }

    private suspend fun remove(location: EntryLocation, recursive: Boolean = false) {
        val parent = location.parent
            ?: throw FileSystemException(location.path, "Cannot remove the OPFS root")
        opfsCall(location.path) {
            parent.removeEntry(location.path.name, opfsRemoveOptions(recursive)).awaitOpfs()
        }
    }

    override suspend fun deleteExisting(path: Path) {
        val target = normalized(path)
        withOpfsMutation { remove(target) }
    }

    override suspend fun deleteIfExists(path: Path): Boolean = try {
        deleteExisting(path)
        true
    } catch (_: NoSuchFileException) { false }

    private fun requirePermissions(permissions: Set<PosixFilePermission>?) {
        if (permissions != null)
            throw UnsupportedOperationException("OPFS has no POSIX permissions")
    }

    override suspend fun createDirectory(path: Path, permissions: Set<PosixFilePermission>?): AsyncPath {
        val target = normalized(path)
        requirePermissions(permissions)
        return withOpfsMutation {
            val location = locate(target)
            if (location.handle != null) throw FileAlreadyExistsException(path)
            opfsCall(path) {
                checkNotNull(location.parent)
                    .getDirectoryHandle(target.name, opfsCreateOptions(true))
                    .awaitOpfs()
            }
            path(path)
        }
    }

    override suspend fun createDirectories(path: Path, permissions: Set<PosixFilePermission>?): AsyncPath {
        val target = normalized(path)
        requirePermissions(permissions)
        return withOpfsMutation {
            var handle = root()
            for (name in target.names) {
                handle = opfsCall(path) {
                    try {
                        handle.getDirectoryHandle(name, opfsCreateOptions(true)).awaitOpfs()
                    } catch (error: JsException) {
                        if (opfsError(error.thrownValue)?.name == "TypeMismatchError")
                            throw FileAlreadyExistsException(path)
                        throw error
                    }
                }
            }
            path(path)
        }
    }

    private suspend fun openFile(path: Path, creation: FileCreation): OpfsHandle {
        val location = locate(path)
        val existing = location.handle
        if (existing != null) {
            if (creation == FileCreation.CREATE_NEW)
                throw FileAlreadyExistsException(path)
            if (existing.kind != "file")
                throw FileSystemException(path, "Not a regular file")
            return existing
        }
        if (creation == FileCreation.OPEN_EXISTING)
            throw NoSuchFileException(path)
        return opfsCall(path) {
            checkNotNull(location.parent)
                .getFileHandle(location.path.name, opfsCreateOptions(true))
                .awaitOpfs()
        }
    }

    override suspend fun createFile(path: Path, permissions: Set<PosixFilePermission>?): AsyncPath {
        val target = normalized(path)
        requirePermissions(permissions)
        return withOpfsMutation {
            openFile(target, FileCreation.CREATE_NEW)
            path(path)
        }
    }

    private suspend fun openOutput(path: Path, mode: FileWriteMode, creation: FileCreation): OpfsOutputStream =
        openWriter(path, openFile(path, creation), mode)

    private suspend fun openWriter(path: Path, handle: OpfsHandle, mode: FileWriteMode): OpfsOutputStream {
        var writer: OpfsWriter? = null
        try {
            // createWritable cannot be aborted. Finish acquisition before observing cancellation.
            val acquired = withContext(NonCancellable) {
                opfsCall(path) {
                    handle.createWritable(opfsWriteOptions(mode != FileWriteMode.TRUNCATE))
                        .awaitOpfs()
                }.also {
                    writer = it
                }
            }
            currentCoroutineContext().ensureActive()
            if (mode == FileWriteMode.APPEND) opfsCall(path) {
                acquired.seek(handle.getFile().awaitOpfs().size)
                    .awaitOpfs()
            }
            return OpfsOutputStream(path, acquired)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try {
                    writer?.abort()?.awaitOpfs()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
    }

    override suspend fun newInputStream(path: Path): AsyncInputStream =
        OpfsInputStream(path, file(path))

    override suspend fun newOutputStream(path: Path, mode: FileWriteMode, creation: FileCreation): AsyncOutputStream =
        withOpfsMutation { openOutput(normalized(path), mode, creation) }

    override suspend fun readBytes(path: Path): ByteArray {
        val file = file(path)
        if (file.size.opfsSize() > Int.MAX_VALUE)
            throw FileSystemException(path, "File is too large for a ByteArray")
        return opfsCall(path) {
            opfsBytes(file.arrayBuffer().awaitOpfs()).toByteArray()
        }
    }

    override suspend fun writeBytes(
        path: Path,
        bytes: ByteArray,
        mode: FileWriteMode,
        creation: FileCreation
    ): AsyncPath {
        val target = normalized(path)
        val output = withOpfsMutation { openOutput(target, mode, creation) }
        try {
            output.write(bytes)
            output.close()
        } catch (error: Throwable) {
            try {
                output.abort()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
        return path(path)
    }

    private suspend fun sameEntry(source: OpfsHandle, target: EntryLocation): Boolean =
        target.handle?.let {
            opfsCall(target.path) {
                source.isSameEntry(it).awaitOpfs().toBoolean()
            }
        } ?: false

    private suspend fun prepareTarget(target: EntryLocation, overwrite: Boolean) {
        if (target.handle == null) return
        if (!overwrite) throw FileAlreadyExistsException(target.path)
        remove(target) // A non-empty directory is never removed by overwrite.
    }

    /** Caller guarantees an absent destination under the mutation lock. */
    private suspend fun copyFile(file: OpfsFile, parent: OpfsHandle, target: AsyncPath) {
        val handle = opfsCall(target) {
            parent.getFileHandle(target.name, opfsCreateOptions(true))
                .awaitOpfs()
        }
        val output = openWriter(target, handle, FileWriteMode.TRUNCATE)
        try {
            output.writeData(file)
            output.close()
        } catch (error: Throwable) {
            try {
                output.abort()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    override suspend fun copyTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        copyAttributes: Boolean,
        followLinks: Boolean
    ): AsyncPath {
        val from = normalized(source)
        val to = normalized(target)

        return withOpfsMutation {
            val handle = entry(from)
            val destination = locate(to)
            if (sameEntry(handle, destination)) return@withOpfsMutation path(target)
            if (copyAttributes) throw UnsupportedOperationException("OPFS cannot preserve timestamps/attributes")

            val data = if (handle.kind == "file") opfsCall(from) {
                handle.getFile().awaitOpfs()
            } else null

            prepareTarget(destination, overwrite)
            val parent = destination.parent
                ?: throw FileSystemException(to, "Cannot replace the OPFS root")

            if (data != null)
                copyFile(data, parent, to)
            else opfsCall(to) {
                parent.getDirectoryHandle(to.name, opfsCreateOptions(true)).awaitOpfs()
            }
            path(target)
        }
    }

    private suspend fun copyTree(
        source: OpfsHandle,
        sourcePath: AsyncPath,
        parent: OpfsHandle,
        target: AsyncPath
    ) {
        if (source.kind == "file") {
            copyFile(opfsCall(sourcePath) { source.getFile().awaitOpfs() }, parent, target)
            return
        }
        val directory = opfsCall(target) {
            parent.getDirectoryHandle(target.name, opfsCreateOptions(true))
                .awaitOpfs()
        }
        OpfsDirectoryEntries(sourcePath, source).use { entries ->
            while (true) {
                val child = entries.next() ?: break
                copyTree(child, sourcePath.resolve(child.name), directory, target.resolve(child.name))
            }
        }
    }

    override suspend fun moveTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        atomicMove: Boolean
    ): AsyncPath {
        val from = normalized(source)
        val to = normalized(target)
        if (atomicMove) throw AtomicMoveNotSupportedException(source, target, "Portable atomic OPFS moves are not available")

        return withOpfsMutation {
            val origin = locate(from)
            val handle = origin.handle ?: throw NoSuchFileException(source)
            val destination = locate(to)

            if (sameEntry(handle, destination))
                return@withOpfsMutation path(target)
            require(from.parent != null && to.parent != null) { "Cannot move or replace the OPFS root" }
            require(!to.startsWith(from)) { "Cannot move a directory into its own subtree" }

            prepareTarget(destination, overwrite)
            copyTree(handle, from, checkNotNull(destination.parent), to)
            remove(origin, recursive = handle.kind == "directory")
            path(target)
        }
    }

    override suspend fun fileStore(path: Path): FileStore {
        entry(path)
        return OpfsFileStore(opfsCall(path) {
            opfsEstimate().awaitOpfs()
        })
    }

    override suspend fun fileStores(): Iterable<FileStore> =
        listOf(fileStore(path("/")))

    override fun asSync(): SyncUserFileSystem? = null
    override fun asAsync(): OpfsFileSystem = this
}
