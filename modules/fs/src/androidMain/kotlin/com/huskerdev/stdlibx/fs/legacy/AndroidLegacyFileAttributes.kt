package com.huskerdev.stdlibx.fs.legacy

import android.system.Os
import android.system.OsConstants
import com.huskerdev.stdlibx.fs.*
import kotlin.time.Instant

internal val permissionBits = intArrayOf(
    OsConstants.S_IRUSR, OsConstants.S_IWUSR, OsConstants.S_IXUSR,
    OsConstants.S_IRGRP, OsConstants.S_IWGRP, OsConstants.S_IXGRP,
    OsConstants.S_IROTH, OsConstants.S_IWOTH, OsConstants.S_IXOTH
)

internal fun FileSystem.readLegacyAttributes(
    path: Path,
    followLinks: Boolean
): FileAttributesSnapshot {
    val info = stat(path, followLinks)
    return FileAttributesSnapshot(
        isRegularFile = OsConstants.S_ISREG(info.st_mode),
        isDirectory = OsConstants.S_ISDIR(info.st_mode),
        isSymbolicLink = OsConstants.S_ISLNK(info.st_mode),
        size = info.st_size,
        isHidden = path.name.startsWith('.'),
        lastModifiedTime = Instant.fromEpochSeconds(info.st_mtime),
        lastAccessTime = Instant.fromEpochSeconds(info.st_atime),
        posixPermissions = PosixFilePermission.entries.filterTo(mutableSetOf()) {
            info.st_mode and permissionBits[it.ordinal] != 0
        }
    )
}

internal fun FileSystem.writeLegacyAttributes(path: Path, changes: FileAttributesChanges, followLinks: Boolean) {
    val info = stat(path, followLinks)
    if (changes.isHidden != null || changes.dosIsReadOnly != null || changes.creationTime != null || changes.lastAccessTime != null)
        throw UnsupportedOperationException("Requested attributes cannot be changed by java.io")

    if (!followLinks && OsConstants.S_ISLNK(info.st_mode) &&
        (changes.posixPermissions != null || changes.lastModifiedTime != null))
        throw UnsupportedOperationException("Cannot change symlink attributes without following it")

    val modified = changes.lastModifiedTime
    if (modified != null && modified.epochSeconds < 0)
        throw UnsupportedOperationException("java.io cannot set modification times before 1970")

    val file = file(path)
    changes.posixPermissions?.let {
        syscall(path) { Os.chmod(file.path, convertPermissions(it, 0)) }
    }
    if (modified != null && !file.setLastModified(modified.toEpochMilliseconds()))
        throw FileSystemException(path, "Cannot set last modified time")
}
