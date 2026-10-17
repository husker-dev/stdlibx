@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.huskerdev.stdlibx.io.fs

import com.huskerdev.stdlibx.io.SyncInputStream
import com.huskerdev.stdlibx.io.SyncOutputStream
import com.huskerdev.stdlibx.io.SyncStream
import com.huskerdev.stdlibx.io.copyTo
import com.huskerdev.stdlibx.io.fs.unix.*
import kotlinx.cinterop.*
import platform.posix.*

internal actual val currentUserFileSystem: UserFileSystem? = SyncKnUnixFileSystem()

interface KnUnixFileSystem: UnixFileSystem, UserFileSystem {
    override fun asSync(): SyncKnUnixFileSystem
    override fun asAsync(): AsyncKnUnixFileSystem
}

class SyncKnUnixFileSystem: KnUnixFileSystem, SyncUnixFileSystem, SyncUserFileSystem {
    private val async by lazy { AsyncKnUnixFileSystem(this) }

    override val identity: Any = Any()

    override val separator: String = "/"

    override fun rootDirectories(): Iterable<SyncPath> = listOf(path("/"))

    override fun workingDir(): SyncPath = path(com.huskerdev.stdlibx.workingDir)

    override fun homeDir(): SyncPath? = nativeHomeDirectory()?.let(::path)
    
    override fun tmpDir(): SyncPath = path(nativeTemporaryDirectory())

    override fun documentsDir(): SyncPath? = nativeUserDirectory("DOCUMENTS")?.let(::path)

    override fun downloadsDir(): SyncPath? = nativeUserDirectory("DOWNLOAD")?.let(::path)

    override fun picturesDir(): SyncPath? = nativeUserDirectory("PICTURES")?.let(::path)

    override fun videosDir(): SyncPath? = nativeUserDirectory("VIDEOS")?.let(::path)

    override fun musicDir(): SyncPath? = nativeUserDirectory("MUSIC")?.let(::path)

    override fun desktopDir(): SyncPath? = nativeUserDirectory("DESKTOP")?.let(::path)

    override fun realPath(path: Path, followLinks: Boolean): SyncPath {
        assertFS(path)
        stat(path, followLinks)
        if (followLinks) {
            val resolved = realpath(absolute(path).pathString, null) ?: throwUnixError(path)
            try { return path(resolved.toKString()) } finally { free(resolved) }
        }
        // '..' following a symlink must not be removed lexically.
        var result: SyncPath = path("/")
        for (name in absolute(path).names) {
            result = when (name) {
                "." -> result
                ".." -> if (result.names.lastOrNull() != ".." && result.isSymbolicLink() != true)
                    result.parent ?: result else result.resolve(name)
                else -> result.resolve(name)
            }
        }
        return result
    }

    override fun readAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot {
        assertFS(path)
        return readUnixAttributes(path, followLinks)
    }

    override fun writeAttributes(path: Path, attributes: FileAttributesChanges, followLinks: Boolean) {
        assertFS(path)
        writeUnixAttributes(path, attributes, followLinks)
    }

    override fun isReadable(path: Path): Boolean = access(absolute(path).pathString, R_OK) == 0
    override fun isWritable(path: Path): Boolean = access(absolute(path).pathString, W_OK) == 0
    override fun isExecutable(path: Path): Boolean = access(absolute(path).pathString, X_OK) == 0

    override fun mimeType(path: Path): String {
        assertFS(path)
        return nativeMimeType(this, path)?.takeIf { it.isNotBlank() }
            ?: unixMimeTypes[path.extension.lowercase()]
            ?: fallbackMimeType(path.extension)
    }

    override fun streamDirectory(directory: Path): SyncStream<SyncPath> =
        KnUnixDirectoryStream(path(directory.pathString).also { assertFS(directory) })

    override fun copyTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        copyAttributes: Boolean,
        followLinks: Boolean
    ): SyncPath {
        assertFS(source)
        assertFS(target)
        val targetName = absolute(target).pathString
        val info = stat(source, followLinks)
        if (!info.isRegularFile && !info.isDirectory && !info.isSymbolicLink)
            throw UnsupportedOperationException("Copying special files is not supported")
        if (isSameFileOrCheckOverwrite(info, target, overwrite)) return path(target.pathString)
        when {
            info.isSymbolicLink -> {
                val link = readSymbolicLink(source)
                if (overwrite) deleteIfExists(target)
                createSymbolicLink(target, link)
            }
            info.isDirectory -> {
                if (overwrite) deleteIfExists(target)
                createDirectory(target, null)
            }
            else -> newInputStream(source).use { input ->
                if (overwrite) deleteIfExists(target)
                val fd = open(
                    target,
                    targetName,
                    O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC,
                    0x1B6
                )
                try {
                    UnixOutputStream(target, fd).use { output ->
                        input.copyTo(output, bufferSize = 16384)
                    }
                } catch (error: Throwable) {
                    try {
                        deleteIfExists(target)
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                    throw error
                }
            }
        }
        if (copyAttributes) {
            if (!info.isSymbolicLink)
                checkUnixError(target, chmod(targetName, (info.mode and 0xFFF).convert()))
            setUnixTimes(target, targetName, !info.isSymbolicLink, info.accessTime, info.modifiedTime)
        }
        return path(target.pathString)
    }

    override fun moveTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        atomicMove: Boolean
    ): SyncPath {
        val sourceInfo = stat(source, false)
        val sourceName = absolute(source).pathString
        val targetName = absolute(target).pathString

        if (isSameFileOrCheckOverwrite(sourceInfo, target, overwrite || atomicMove)) return path(target.pathString)
        if (rename(sourceName, targetName) == 0) return path(target.pathString)

        if (atomicMove) {
            val code = errno
            if (code == EXDEV) throw AtomicMoveNotSupportedException(source, target)
            throwUnixError(source, code)
        }
        when (val code = errno) {
            EXDEV -> {
                if (sourceInfo.isDirectory && streamDirectory(source).use { it.hasNext() })
                    throw FileSystemException(source, target, "Cannot move a non-empty directory across filesystems")
                copyTo(source, target, overwrite, copyAttributes = true, followLinks = false)
                deleteExisting(source)
            }
            EISDIR, ENOTDIR -> {
                if (!overwrite || sourceInfo.isDirectory == stat(target, false).isDirectory)
                    throwUnixError(source, code)
                deleteExisting(target)
                checkUnixError(source, rename(sourceName, targetName))
            }
            else -> throwUnixError(source, code)
        }
        return path(target.pathString)
    }

    override fun exists(path: Path, followLinks: Boolean): Boolean {
        assertFS(path)
        return try { stat(path, followLinks); true } catch (_: FileSystemException) { false }
    }

    override fun notExists(path: Path, followLinks: Boolean): Boolean = try {
        assertFS(path)
        stat(path, followLinks)
        false
    } catch (_: NoSuchFileException) { true } catch (_: FileSystemException) { false }

    override fun isSameFiles(path: Path, other: Path): Boolean {
        assertFS(path)
        assertFS(other)
        return stat(path).sameFile(stat(other))
    }

    override fun readSymbolicLink(path: Path): SyncPath {
        assertFS(path)
        var size = 256
        while (true) {
            val bytes = ByteArray(size)
            val count = readLink(path, bytes)
            if (count < size) return path(bytes.decodeToString(endIndex = count))
            if (size > Int.MAX_VALUE / 2) throw FileSystemException(path, "Symbolic link target is too long")
            size *= 2
        }
    }

    override fun createSymbolicLink(path: Path, target: Path): SyncPath {
        assertFS(target)
        checkUnixError(path, symlink(target.pathString, absolute(path).pathString))
        return path(path.pathString)
    }

    override fun deleteExisting(path: Path) =
        checkUnixError(path, remove(absolute(path).pathString))

    override fun deleteIfExists(path: Path): Boolean = try {
        deleteExisting(path)
        true
    } catch (_: NoSuchFileException) { false }

    override fun createDirectory(path: Path, permissions: Set<PosixFilePermission>?): SyncPath {
        checkUnixError(path, mkdir(absolute(path).pathString, convertPermissions(permissions, 0x1FF).convert()))
        return path(path.pathString)
    }

    override fun createDirectories(path: Path, permissions: Set<PosixFilePermission>?): SyncPath {
        assertFS(path)
        try {
            createDirectoryIfMissing(path, permissions)
        } catch (error: NoSuchFileException) {
            val parent = absolute(path).parent ?: throw error
            createDirectories(parent, permissions)
            createDirectoryIfMissing(path, permissions)
        }
        return path(path.pathString)
    }

    private fun createDirectoryIfMissing(path: Path, permissions: Set<PosixFilePermission>?) {
        try {
            createDirectory(path, permissions)
        } catch (error: FileAlreadyExistsException) {
            if (!stat(path).isDirectory) throw error
        }
    }

    override fun createFile(path: Path, permissions: Set<PosixFilePermission>?): SyncPath {
        checkUnixError(path, close(open(
            path,
            absolute(path).pathString,
            O_WRONLY or O_CREAT or O_EXCL or O_CLOEXEC,
            convertPermissions(permissions, 0x1B6)
        )))
        return path(path.pathString)
    }

    override fun fileStores(): Iterable<FileStore> = nativeMounts()

    override fun fileStore(path: Path): FileStore {
        val real = realPath(path, true)
        nativeFileMount(path, real.pathString)?.let { return it }

        val device = stat(real).device
        val mounts = nativeMounts().filter {
            try {
                stat(path(it.mountPoint)).device == device
            } catch (_: FileSystemException) { false }
        }
        return mounts
            .map { it to path(it.mountPoint)  }
            .filter { real.startsWith(it.second) }
            .maxByOrNull { it.second.names.size }
            ?.first
            ?: mounts.firstOrNull()
            ?: throw FileSystemException(path, "Cannot find filesystem mount")
    }

    override fun newInputStream(path: Path): SyncInputStream =
        UnixInputStream(
            path,
            open(path, absolute(path).pathString, O_RDONLY or O_CLOEXEC)
        )

    override fun readBytes(path: Path): ByteArray {
        assertFS(path)
        return withUnixDescriptor(path, open(path, absolute(path).pathString, O_RDONLY or O_CLOEXEC)) { fd ->
            val size = memScoped {
                val info = alloc<stat>()
                checkUnixError(path, fstat(fd, info.ptr))
                info.st_size
            }
            readWholeFileBytes(path, size) { bytes, offset, length ->
                bytes.usePinned { pinned ->
                    retryUnix(path) { read(fd, pinned.addressOf(offset), length.convert()) }.toInt()
                }
            }
        }
    }

    override fun writeBytes(path: Path, bytes: ByteArray, mode: FileWriteMode, creation: FileCreation): SyncPath {
        assertFS(path)
        val fd = open(path, absolute(path).pathString, unixWriteFlags(mode, creation), 0x1B6)
        withUnixDescriptor(path, fd) {
            writeUnixFully(path, fd, bytes)
        }
        return path(path.pathString)
    }

    override fun newOutputStream(path: Path, mode: FileWriteMode, creation: FileCreation): SyncOutputStream =
        UnixOutputStream(
            path,
            open(path, absolute(path).pathString, unixWriteFlags(mode, creation), 0x1B6)
        )

    override fun absolute(path: Path): SyncPath = super<SyncUserFileSystem>.absolute(path)

    override fun asSync(): SyncKnUnixFileSystem = this
    override fun asAsync(): AsyncKnUnixFileSystem = async
}

class AsyncKnUnixFileSystem(
    override val wrapped: SyncKnUnixFileSystem
):
    KnUnixFileSystem,
    AsyncUnixFileSystem,
    AsyncUserFileSystem,
    WrappedAsyncFileSystem,
    WrappedAsyncUserFileSystem
{
    override fun absolute(path: Path): AsyncPath = super<WrappedAsyncFileSystem>.absolute(path)

    override fun asSync(): SyncKnUnixFileSystem = wrapped
    override fun asAsync(): AsyncKnUnixFileSystem = this
}
