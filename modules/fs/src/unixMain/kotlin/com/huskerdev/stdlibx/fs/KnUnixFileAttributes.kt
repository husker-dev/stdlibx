@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package com.huskerdev.stdlibx.fs

import kotlinx.cinterop.convert
import platform.posix.chmod

internal fun readUnixAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot {
    val info = stat(path, followLinks)
    return FileAttributesSnapshot(
        isRegularFile = info.isRegularFile,
        isDirectory = info.isDirectory,
        isSymbolicLink = info.isSymbolicLink,
        size = info.size,
        isHidden = path.name.startsWith('.'),
        lastModifiedTime = info.modifiedTime,
        lastAccessTime = info.accessTime,
        creationTime = info.birthTime,
        posixPermissions = PosixFilePermission.entries.filterTo(mutableSetOf()) {
            info.mode and unixPermissionBits[it.ordinal] != 0
        }
    )
}

internal fun writeUnixAttributes(path: Path, attributes: FileAttributesChanges, followLinks: Boolean) {
    val info = stat(path, followLinks)

    if (attributes.isHidden != null || attributes.dosIsReadOnly != null || attributes.creationTime != null)
        throw UnsupportedOperationException("Hidden/DOS flags and setting creation time are not supported")

    if (attributes.posixPermissions != null && !followLinks && info.isSymbolicLink)
        throw UnsupportedOperationException("Cannot change symlink permissions without following it")

    val name = path.absolute().pathString
    attributes.posixPermissions?.let {
        checkUnixError(path, chmod(name, convertPermissions(it, 0).convert()))
    }
    if (attributes.lastAccessTime != null || attributes.lastModifiedTime != null) {
        setUnixTimes(
            path, name, followLinks,
            attributes.lastAccessTime ?: info.accessTime,
            attributes.lastModifiedTime ?: info.modifiedTime
        )
    }
}
