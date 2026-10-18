package com.huskerdev.stdlibx.fs.legacy

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import com.huskerdev.stdlibx.io.*
import com.huskerdev.stdlibx.fs.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.time.Instant

private const val OPEN_CLOEXEC = 0x80000


interface AndroidLegacyFileSystem: AndroidFileSystem {
    override fun asSync(): SyncAndroidLegacyFileSystem
    override fun asAsync(): AsyncAndroidLegacyFileSystem
}

class SyncAndroidLegacyFileSystem: AndroidLegacyFileSystem, SyncAndroidFileSystem {
    override val identity: Any = Any()
    private val async by lazy { AsyncAndroidLegacyFileSystem(this) }

    override val separator: String
        get() = "/"

    override fun rootDirectories(): Iterable<SyncPath> = listOf(path("/"))

    override fun realPath(path: Path, followLinks: Boolean): SyncPath {
        stat(path, followLinks)
        val file = file(path)
        if (followLinks) return syscall(path) { path(file.canonicalPath) }

        // Removing '..' after a symlink can change the file addressed by the path.
        var result: SyncPath = path("/")
        for (name in absolute(path).names) {
            when (name) {
                "." -> Unit
                ".." -> {
                    result = if (result.names.lastOrNull() != ".." && result.isSymbolicLink() != true)
                        result.parent ?: result
                    else result.resolve(name)
                }
                else -> result = result.resolve(name)
            }
        }
        return result
    }

    override fun readAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot {
        return readLegacyAttributes(path, followLinks)
    }

    override fun mimeType(path: Path): String {
        assertFS(path)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.extension.lowercase())
            ?.takeIf { it.isNotBlank() } ?: fallbackMimeType(path.extension)
    }

    override fun streamDirectory(directory: Path): SyncStream<SyncPath> {
        if (!OsConstants.S_ISDIR(stat(directory, true).st_mode))
            throw FileSystemException(directory, "Not a directory")
        val children = file(directory).list()
            ?.map { path(directory.pathString).resolve(it) }
            ?: throw FileSystemException(directory, "Cannot list directory")

        return SyncStream.OfList(children)
    }

    override fun copyTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        copyAttributes: Boolean,
        followLinks: Boolean
    ): SyncPath {
        assertFS(source)
        assertFS(target)

        val info = stat(source, followLinks)
        if (!OsConstants.S_ISREG(info.st_mode) &&
            !OsConstants.S_ISDIR(info.st_mode) &&
            !OsConstants.S_ISLNK(info.st_mode)
        ) throw UnsupportedOperationException("Copying special files is not supported")

        if (exists(target, false)) {
            val targetInfo = stat(target, false)
            if (info.st_dev == targetInfo.st_dev && info.st_ino == targetInfo.st_ino) return path(target.pathString)
            if (!overwrite) throw FileAlreadyExistsException(target)
        }

        if (copyAttributes && !OsConstants.S_ISLNK(info.st_mode) && info.st_mtime < 0)
            throw UnsupportedOperationException("Modification times is less than 1970")

        when {
            OsConstants.S_ISLNK(info.st_mode) -> {
                val linkTarget = readSymbolicLink(source)
                if (overwrite) deleteIfExists(target)
                createSymbolicLink(target, linkTarget)
            }
            OsConstants.S_ISDIR(info.st_mode) -> {
                if (overwrite) deleteIfExists(target)
                createDirectory(target, null)
            }
            OsConstants.S_ISREG(info.st_mode) -> {
                syscall(source) { FileInputStream(file(source)).use { input ->
                    if (overwrite) deleteIfExists(target)
                    val descriptor = syscall(target) {
                        Os.open(
                            absolute(target).pathString,
                            OsConstants.O_WRONLY or OsConstants.O_CREAT or
                                    OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OPEN_CLOEXEC,
                            0x1B6
                        )
                    }
                    try {
                        try {
                            FileOutputStream(descriptor).use { output ->
                                input.copyTo(output)
                            }
                        } finally {
                            // Older Android streams borrow the descriptor instead of closing it.
                            if (descriptor.valid()) syscall(target) { Os.close(descriptor) }
                        }
                    } catch (error: Throwable) {
                        try { deleteIfExists(target) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                        throw error
                    }
                } }
            }
            else -> throw UnsupportedOperationException("Copying special files is not supported")
        }
        if (copyAttributes && !OsConstants.S_ISLNK(info.st_mode)) {
            syscall(target) { Os.chmod(absolute(target).pathString, info.st_mode and 0xFFF) }
            writeAttributes(target, FileAttributesChanges(lastModifiedTime = Instant.fromEpochSeconds(info.st_mtime)), true)
        }
        return path(target.pathString)
    }

    override fun moveTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        atomicMove: Boolean
    ): SyncPath {
        assertFS(source)
        assertFS(target)
        if (exists(target, false)) {
            val sourceInfo = stat(source, false)
            val targetInfo = stat(target, false)
            if (sourceInfo.st_dev == targetInfo.st_dev && sourceInfo.st_ino == targetInfo.st_ino) return path(target.pathString)
            if (!overwrite && !atomicMove) throw FileAlreadyExistsException(target)
        }
        syscall(source) {
            try {
                Os.rename(absolute(source).pathString, absolute(target).pathString)
            } catch (error: ErrnoException) {
                if (atomicMove && error.errno == OsConstants.EXDEV)
                    throw AtomicMoveNotSupportedException(source, target, error.message, error)
                if (atomicMove) throw error
                when (error.errno) {
                    OsConstants.EXDEV -> {
                        if (OsConstants.S_ISDIR(stat(source, false).st_mode) && streamDirectory(source).use { it.hasNext() })
                            throw FileSystemException(source, target, "Cannot move a non-empty directory across filesystems")
                        copyTo(source, target, overwrite, copyAttributes = true, followLinks = false)
                        deleteExisting(source)
                    }

                    OsConstants.EISDIR, OsConstants.ENOTDIR -> {
                        if (!overwrite || OsConstants.S_ISDIR(stat(source, false).st_mode) == OsConstants.S_ISDIR(stat(target, false).st_mode)) throw error
                        deleteExisting(target)
                        Os.rename(absolute(source).pathString, absolute(target).pathString)
                    }

                    else -> throw error
                }
            }
        }
        return path(target.pathString)
    }

    override fun exists(path: Path, followLinks: Boolean): Boolean = try {
        stat(path, followLinks)
        true
    } catch (_: NoSuchFileException) { false } catch (_: FileSystemException) { false }

    override fun notExists(path: Path, followLinks: Boolean): Boolean = try {
        stat(path, followLinks)
        false
    } catch (_: NoSuchFileException) { true } catch (_: FileSystemException) { false }

    override fun isSameFiles(path: Path, other: Path): Boolean {
        val first = stat(path, true)
        val second = stat(other, true)
        return first.st_dev == second.st_dev && first.st_ino == second.st_ino
    }

    override fun readSymbolicLink(path: Path): SyncPath =
        path(syscall(path) {
            try { Os.readlink(file(path).path) }
            catch (error: ErrnoException) {
                if (error.errno == OsConstants.EINVAL) throw NotLinkException(path, error)
                throw error
            }
        })

    override fun createSymbolicLink(
        path: Path,
        target: Path
    ): SyncPath {
        assertFS(path)
        assertFS(target)
        syscall(path) { Os.symlink(target.pathString, absolute(path).pathString) }
        return path(path.pathString)
    }

    override fun deleteExisting(path: Path) =
        syscall(path) { Os.remove(file(path).path) }

    override fun deleteIfExists(path: Path): Boolean = try {
        deleteExisting(path)
        true
    } catch (_: NoSuchFileException) { false }

    override fun createDirectory(
        path: Path,
        permissions: Set<PosixFilePermission>?
    ): SyncPath = syscall(path) {
        Os.mkdir(file(path).path, convertPermissions(permissions, 0x1FF))
        path(path.pathString)
    }

    override fun createDirectories(
        path: Path,
        permissions: Set<PosixFilePermission>?
    ): SyncPath {
        assertFS(path)
        try {
            createDirectory(path, permissions)
        } catch (error: FileAlreadyExistsException) {
            if (!OsConstants.S_ISDIR(stat(path, true).st_mode)) throw error
        } catch (error: NoSuchFileException) {
            val parent = absolute(path).parent ?: throw error
            createDirectories(parent, permissions)
            try {
                createDirectory(path, permissions)
            } catch (exists: FileAlreadyExistsException) {
                if (!OsConstants.S_ISDIR(stat(path, true).st_mode)) throw exists
            }
        }
        return path(path.pathString)
    }

    override fun createFile(path: Path, permissions: Set<PosixFilePermission>?): SyncPath {
        assertFS(path)
        syscall(path) {
            val descriptor = Os.open(
                absolute(path).pathString,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OPEN_CLOEXEC,
                convertPermissions(permissions, 0x1B6)
            )
            Os.close(descriptor)
        }
        return path(path.pathString)
    }

    override fun fileStore(path: Path): FileStore {
        val real = realPath(path, true)
        return fileStores()
            .map { it to path(it.mountPoint) }
            .filter { real.startsWith(it.second) }
            .maxByOrNull { it.second.names.size }
            ?.first
            ?: throw FileSystemException(path, "Cannot find filesystem mount")
    }

    override fun newInputStream(path: Path): SyncInputStream =
        syscall(path) { FileInputStream(file(path)).wrap(path) }

    override fun newOutputStream(path: Path, mode: FileWriteMode, creation: FileCreation): SyncOutputStream {
        val flags = OsConstants.O_WRONLY or OPEN_CLOEXEC or when (mode) {
            FileWriteMode.FROM_START -> 0
            FileWriteMode.TRUNCATE -> OsConstants.O_TRUNC
            FileWriteMode.APPEND -> OsConstants.O_APPEND
        } or when (creation) {
            FileCreation.OPEN_EXISTING -> 0
            FileCreation.CREATE_IF_MISSING -> OsConstants.O_CREAT
            FileCreation.CREATE_NEW -> OsConstants.O_CREAT or OsConstants.O_EXCL
        }
        val descriptor = syscall(path) { Os.open(file(path).path, flags, 0x1B6) }
        try {
            return AndroidLegacyFileOutputStream(path, descriptor)
        } catch (error: Throwable) {
            try {
                syscall(path) { Os.close(descriptor) }
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    override fun readBytes(path: Path): ByteArray =
        syscall(path) {
            FileInputStream(file(path)).use { input ->
                readWholeFileBytes(path, input.channel.size()) { bytes, offset, length ->
                    input.read(bytes, offset, length).coerceAtLeast(0)
                }
            }
        }

    override fun writeBytes(path: Path, bytes: ByteArray, mode: FileWriteMode, creation: FileCreation): SyncPath {
        newOutputStream(path, mode, creation).use { it.write(bytes) }
        return path(path.pathString)
    }

    override fun fileStores(): Iterable<AndroidFileStore> =
        syscall(path("/proc/mounts")) { File("/proc/mounts").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val fields = line.split(' ')
                if (fields.size < 3) return@mapNotNull null
                AndroidFileStore(fields)
            }.toList()
        } }

    override fun writeAttributes(path: Path, attributes: FileAttributesChanges, followLinks: Boolean) =
        writeLegacyAttributes(path, attributes, followLinks)

    private fun accessible(path: Path, mode: Int): Boolean = try {
        syscall(path) { Os.access(file(path).path, mode) }
    } catch (_: FileSystemException) { false }

    override fun isReadable(path: Path): Boolean = accessible(path, OsConstants.R_OK)
    override fun isWritable(path: Path): Boolean = accessible(path, OsConstants.W_OK)
    override fun isExecutable(path: Path): Boolean = accessible(path, OsConstants.X_OK)

    override fun asSync(): SyncAndroidLegacyFileSystem = this
    override fun asAsync(): AsyncAndroidLegacyFileSystem = async
}

class AsyncAndroidLegacyFileSystem(
    override val wrapped: SyncAndroidLegacyFileSystem
):
    AndroidLegacyFileSystem,
    AsyncAndroidFileSystem,
    WrappedAsyncFileSystem,
    WrappedAsyncUserFileSystem
{
    override fun absolute(path: Path): AsyncPath = super<WrappedAsyncFileSystem>.absolute(path)

    override fun asSync(): SyncAndroidLegacyFileSystem = wrapped
    override fun asAsync(): AsyncAndroidLegacyFileSystem = this
}
