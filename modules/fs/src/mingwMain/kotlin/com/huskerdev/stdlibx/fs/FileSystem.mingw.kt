@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.io.*
import com.huskerdev.stdlibx.fs.windows.*
import kotlinx.cinterop.*
import platform.windows.*

internal actual val currentUserFileSystem: UserFileSystem? = KnWindowsFileSystem()

/**
 * Win32 Unicode filesystem shared by all MinGW targets. Streams use synchronous handles with
 * coroutine adapters. DOS flags are supported; ACL editing and POSIX permissions are not.
 * Symbolic-link creation requires Developer Mode or the Windows symbolic-link privilege.
 */
class KnWindowsFileSystem: SyncWindowsFileSystem, SyncUserFileSystem {
    private val async by lazy { AsyncKnWindowsFileSystem(this) }

    override val identity: Any = Any()

    override val separator: String = "\\"

    override fun workingDir(): SyncPath =
        path(com.huskerdev.stdlibx.workingDir)

    override fun homeDir(): SyncPath? =
        knownFolder(FOLDERID_Profile.ptr)

    override fun documentsDir(): SyncPath? =
        knownFolder(FOLDERID_Documents.ptr)

    override fun downloadsDir(): SyncPath? =
        knownFolder(FOLDERID_Downloads.ptr)

    override fun picturesDir(): SyncPath? =
        knownFolder(FOLDERID_Pictures.ptr)

    override fun videosDir(): SyncPath? =
        knownFolder(FOLDERID_Videos.ptr)

    override fun musicDir(): SyncPath? =
        knownFolder(FOLDERID_Music.ptr)

    override fun desktopDir(): SyncPath? =
        knownFolder(FOLDERID_Desktop.ptr)

    override fun tmpDir(): SyncPath = path(windowsString(null) { size, buffer ->
        GetTempPathW(size, buffer)
    })

    override fun rootDirectories(): Iterable<SyncPath> = windowsStrings(null) { size, buffer ->
        GetLogicalDriveStringsW(size, buffer)
    }.map { path(it) }

    override fun fileStores(): Iterable<FileStore> =
        rootDirectories().mapNotNull { root ->
            try {
                KnWindowsFileStore(root.pathString)
            } catch (_: FileSystemException) { null } // Empty removable drives have no mounted filesystem.
        }

    override fun absolute(path: Path): SyncPath {
        assertFS(path)
        if (path.isAbsolute) return path(path.pathString)
        val pathString = path.pathString.ifEmpty { "." }
        return path(windowsString(path) { size, buffer ->
            GetFullPathNameW(pathString, size, buffer, null)
        })
    }

    override fun realPath(path: Path, followLinks: Boolean): SyncPath {
        assertFS(path)
        if (!followLinks) {
            windowsInfo(path, false)
            return absolute(path)
        }
        return withWindowsHandle(path) { handle ->
            val name = windowsString(path) { size, buffer ->
                GetFinalPathNameByHandleW(handle, buffer, size, 0u)
            }
            path(if (path.pathString.startsWith("\\\\?\\")) name else windowsDosPath(name))
        }
    }

    override fun readAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot {
        assertFS(path)
        return readWindowsAttributes(path, followLinks)
    }

    override fun writeAttributes(path: Path, attributes: FileAttributesChanges, followLinks: Boolean) {
        assertFS(path)
        writeWindowsAttributes(path, attributes, followLinks)
    }

    private fun accessible(path: Path, access: UInt): Boolean {
        assertFS(path)
        return try {
            withWindowsHandle(path, access) { true }
        } catch (_: FileSystemException) { false }
    }

    override fun isReadable(path: Path): Boolean = accessible(path, GENERIC_READ)
    override fun isWritable(path: Path): Boolean = accessible(path, GENERIC_WRITE.toUInt())
    override fun isExecutable(path: Path): Boolean = accessible(path, GENERIC_EXECUTE.toUInt())

    override fun mimeType(path: Path): String {
        assertFS(path)
        return windowsMimeType(path.extension)
    }

    override fun streamDirectory(directory: Path): SyncStream<SyncPath> {
        assertFS(directory)
        return KnWindowsDirectoryStream(path(directory.pathString))
    }

    override fun exists(path: Path, followLinks: Boolean): Boolean =  try {
        assertFS(path)
        windowsInfo(path, followLinks)
        true
    } catch (_: NoSuchFileException) { false } catch (_: FileSystemException) { false }

    override fun notExists(path: Path, followLinks: Boolean): Boolean = try {
        assertFS(path)
        windowsInfo(path, followLinks)
        false
    } catch (_: NoSuchFileException) { true } catch (_: FileSystemException) { false }

    override fun isSameFiles(path: Path, other: Path): Boolean {
        assertFS(path)
        assertFS(other)
        return windowsInfo(path).sameFile(windowsInfo(other))
    }

    override fun createFile(path: Path, permissions: Set<PosixFilePermission>?): SyncPath {
        assertFS(path)
        requireWindowsPermissions(permissions)
        checkWindows(path,
            CloseHandle(openWindows(path, GENERIC_WRITE.toUInt(), CREATE_NEW.toUInt()))
        )
        return path(path.pathString)
    }

    override fun createDirectory(path: Path, permissions: Set<PosixFilePermission>?): SyncPath {
        assertFS(path)
        requireWindowsPermissions(permissions)
        checkWindows(path,
            CreateDirectoryW(absolute(path).pathString, null)
        )
        return path(path.pathString)
    }

    override fun createDirectories(path: Path, permissions: Set<PosixFilePermission>?): SyncPath {
        assertFS(path)
        requireWindowsPermissions(permissions)
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
            if (!windowsInfo(path).directory) throw error
        }
    }

    override fun deleteExisting(path: Path) {
        assertFS(path)
        val info = windowsInfo(path, false)
        val name = absolute(path).pathString
        checkWindows(path,
            if (info.directoryAttribute) RemoveDirectoryW(name) else DeleteFileW(name)
        )
    }

    override fun deleteIfExists(path: Path): Boolean = try {
        deleteExisting(path)
        true
    } catch (_: NoSuchFileException) { false }

    override fun createSymbolicLink(path: Path, target: Path): SyncPath {
        assertFS(path)
        assertFS(target)
        val addressed = if (target.isAbsolute || target.root != null) target
            else absolute(path).parent!!.resolve(target)
        createWindowsLink(path, target, windowsInfoOrNull(addressed)?.directory ?: false)
        return path(path.pathString)
    }

    override fun readSymbolicLink(path: Path): SyncPath {
        assertFS(path)
        return path(readWindowsLink(path))
    }

    override fun copyTo(source: Path, target: Path, overwrite: Boolean, copyAttributes: Boolean, followLinks: Boolean): SyncPath {
        assertFS(source)
        assertFS(target)
        val info = windowsInfo(source, followLinks)
        if (!followLinks && info.tag != 0u && !info.symbolicLink)
            throw UnsupportedOperationException("Copying this Windows reparse point is not supported")
        if (checkTarget(info, target, overwrite)) return path(target.pathString)
        when {
            info.symbolicLink -> {
                val link = readSymbolicLink(source)
                if (overwrite) deleteIfExists(target)
                createWindowsLink(target, link, info.directoryAttribute)
            }
            info.directory -> {
                if (overwrite) deleteIfExists(target)
                createDirectory(target, null)
            }
            else -> newInputStream(source).use { input ->
                if (overwrite) deleteIfExists(target)
                val handle = openWindows(target, GENERIC_WRITE.toUInt(), CREATE_NEW.toUInt(), followLinks = false)
                try {
                    WindowsOutputStream(target, handle).use { output -> input.copyTo(output) }
                } catch (error: Throwable) {
                    try { deleteIfExists(target) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                    throw error
                }
            }
        }
        if (copyAttributes) {
            setWindowsTimes(target, !info.symbolicLink, info.created, info.accessed, info.modified)
            writeAttributes(target, FileAttributesChanges(
                isHidden = info.attributes and FILE_ATTRIBUTE_HIDDEN.toUInt() != 0u,
                dosIsReadOnly = info.attributes and FILE_ATTRIBUTE_READONLY.toUInt() != 0u
            ), !info.symbolicLink)
        }
        return path(target.pathString)
    }

    override fun moveTo(source: Path, target: Path, overwrite: Boolean, atomicMove: Boolean): SyncPath {
        assertFS(source)
        assertFS(target)
        val info = windowsInfo(source, false)
        if (checkTarget(info, target, overwrite || atomicMove)) return path(target.pathString)

        val flags = if (overwrite || atomicMove) MOVEFILE_REPLACE_EXISTING.toUInt() else 0u
        val from = absolute(source).pathString
        val to = absolute(target).pathString
        if (MoveFileExW(from, to, flags) != 0) return path(target.pathString)

        val code = GetLastError()
        if (atomicMove) {
            if (code == ERROR_NOT_SAME_DEVICE.toUInt()) throw AtomicMoveNotSupportedException(source, target)
            throwWindowsError(source, code)
        }
        if (code == ERROR_NOT_SAME_DEVICE.toUInt()) {
            if (info.directory && streamDirectory(source).use { it.hasNext() })
                throw FileSystemException(source, target, "Cannot move a non-empty directory across filesystems")
            copyTo(source, target, overwrite, copyAttributes = true, followLinks = false)
            deleteExisting(source)
        } else {
            val targetInfo = windowsInfoOrNull(target, false)
            if (!overwrite || targetInfo == null || (!targetInfo.directory && !info.directory)) throwWindowsError(source, code)
            // MoveFileEx cannot replace directories; only an empty target may be removed.
            deleteExisting(target)
            checkWindows(source, MoveFileExW(from, to, 0u))
        }
        return path(target.pathString)
    }

    override fun fileStore(path: Path): FileStore {
        assertFS(path)
        val name = realPath(path, true).pathString
        val mount = windowsString(path) { size, buffer -> GetVolumePathNameW(name, buffer, size).toUInt() }
        return KnWindowsFileStore(mount)
    }

    override fun newInputStream(path: Path): SyncInputStream {
        assertFS(path)
        return WindowsInputStream(path, openWindows(path, GENERIC_READ))
    }

    override fun readBytes(path: Path): ByteArray {
        assertFS(path)
        return withWindowsHandle(path, GENERIC_READ) { handle -> memScoped {
            val size = alloc<LARGE_INTEGER>()
            checkWindows(path, GetFileSizeEx(handle, size.ptr))
            val count = alloc<UIntVar>()
            readWholeFileBytes(path, size.QuadPart) { bytes, offset, length ->
                val result = bytes.usePinned { ReadFile(handle, it.addressOf(offset), length.toUInt(), count.ptr, null) }
                if (result == 0) {
                    val code = GetLastError()
                    if (code == ERROR_HANDLE_EOF.toUInt() || code == ERROR_BROKEN_PIPE.toUInt()) 0
                    else throwWindowsError(path, code)
                } else count.value.toInt()
            }
        } }
    }

    override fun writeBytes(path: Path, bytes: ByteArray, mode: FileWriteMode, creation: FileCreation): SyncPath {
        assertFS(path)
        // OPEN_ALWAYS avoids CREATE_ALWAYS's special handling of hidden/system files.
        val handle = openWriteHandle(path, mode, creation)
        var failure: Throwable? = null
        try {
            writeWindowsFully(path, handle, bytes)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { checkWindows(path, CloseHandle(handle)) } catch (error: Throwable) {
                if (failure == null) throw error
                if (failure !== error) failure.addSuppressed(error)
            }
        }
        return path(path.pathString)
    }

    override fun newOutputStream(path: Path, mode: FileWriteMode, creation: FileCreation): SyncOutputStream {
        assertFS(path)
        val handle = openWriteHandle(path, mode, creation)
        return WindowsOutputStream(path, handle)
    }

    override fun asSync(): KnWindowsFileSystem = this
    override fun asAsync(): AsyncKnWindowsFileSystem = async
}

class AsyncKnWindowsFileSystem(
    override val wrapped: KnWindowsFileSystem
): AsyncWindowsFileSystem, AsyncUserFileSystem, WrappedAsyncFileSystem, WrappedAsyncUserFileSystem {
    override fun absolute(path: Path): AsyncPath = super<WrappedAsyncFileSystem>.absolute(path)
    override fun asSync(): KnWindowsFileSystem = wrapped
    override fun asAsync(): AsyncKnWindowsFileSystem = this
}

private fun openWriteHandle(path: Path, mode: FileWriteMode, creation: FileCreation): HANDLE {
    val access = if (mode == FileWriteMode.APPEND) FILE_APPEND_DATA.toUInt() else GENERIC_WRITE.toUInt()
    val disposition = when (creation) {
        FileCreation.OPEN_EXISTING -> OPEN_EXISTING
        FileCreation.CREATE_IF_MISSING -> OPEN_ALWAYS
        FileCreation.CREATE_NEW -> CREATE_NEW
    }.toUInt()
    val handle = openWindows(path, access, disposition)
    try {
        if (mode == FileWriteMode.TRUNCATE)
            checkWindows(path, SetEndOfFile(handle))
    } catch (error: Throwable) {
        try {
            checkWindows(path, CloseHandle(handle))
        } catch (cleanup: Throwable) {
            error.addSuppressed(cleanup)
        }
        throw error
    }
    return handle
}

private fun requireWindowsPermissions(permissions: Set<PosixFilePermission>?) {
    if (permissions != null)
        throw UnsupportedOperationException("POSIX permissions are not supported on Windows")
}

private fun checkTarget(source: WindowsFileInfo, target: Path, overwrite: Boolean): Boolean {
    val targetInfo = windowsInfoOrNull(target, false) ?: return false
    if (source.sameFile(targetInfo)) return true
    if (!overwrite) throw FileAlreadyExistsException(target)
    return false
}
