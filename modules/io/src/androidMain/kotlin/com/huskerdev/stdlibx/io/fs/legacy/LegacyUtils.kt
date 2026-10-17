package com.huskerdev.stdlibx.io.fs.legacy

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.huskerdev.stdlibx.io.fs.DirectoryNotEmptyException
import com.huskerdev.stdlibx.io.fs.FileAlreadyExistsException
import com.huskerdev.stdlibx.io.fs.FileSystem
import com.huskerdev.stdlibx.io.fs.FileSystemException
import com.huskerdev.stdlibx.io.fs.NoSuchFileException
import com.huskerdev.stdlibx.io.fs.Path
import com.huskerdev.stdlibx.io.fs.PosixFilePermission
import com.huskerdev.stdlibx.io.fs.AccessDeniedException
import com.huskerdev.stdlibx.io.fs.FileSystemLoopException
import java.io.File

internal fun FileSystem.file(path: Path): File {
    assertFS(path)
    return File(absolute(path).pathString)
}

internal fun <T> syscall(path: Path, block: () -> T): T = try {
    block()
} catch (error: ErrnoException) {
    throwLegacyError(path, error)
} catch (error: FileSystemException) {
    throw error
} catch (error: java.io.IOException) {
    val cause = error.cause
    if (cause is ErrnoException) throwLegacyError(path, cause)
    if (error is java.io.FileNotFoundException) {
        try { Os.stat(path.absoluteString()) }
        catch (missing: ErrnoException) { throwLegacyError(path, missing) }
    }
    throw FileSystemException(path, error.message ?: "File I/O failed", error)
}

internal fun throwLegacyError(path: Path, error: ErrnoException): Nothing =
    throw when (error.errno) {
        OsConstants.ENOENT -> NoSuchFileException(path, error)
        OsConstants.ENOTDIR -> FileSystemException(path, "Not a directory", error)
        OsConstants.ELOOP -> FileSystemLoopException(path, error)
        OsConstants.EACCES, OsConstants.EPERM -> AccessDeniedException(path, error)
        OsConstants.EEXIST -> FileAlreadyExistsException(path, error)
        OsConstants.ENOTEMPTY -> DirectoryNotEmptyException(path, error)
        else -> FileSystemException(path, error.message ?: "Filesystem operation failed", error)
    }

internal fun FileSystem.stat(path: Path, followLinks: Boolean): StructStat {
    assertFS(path)
    return syscall(path) {
        val name = absolute(path).pathString
        if (followLinks) Os.stat(name) else Os.lstat(name)
    }
}

internal fun convertPermissions(permissions: Set<PosixFilePermission>?, default: Int): Int =
    permissions?.fold(0) { mode, permission -> mode or permissionBits[permission.ordinal] } ?: default
