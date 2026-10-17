package com.huskerdev.stdlibx.io.fs.nodejs

import com.huskerdev.stdlibx.io.fs.*

internal fun readNodeAttributes(path: Path, followLinks: Boolean, windows: Boolean): FileAttributesSnapshot {
    val current = info(path, followLinks)
    return FileAttributesSnapshot(
        isRegularFile = current.regular,
        isDirectory = current.directory,
        isSymbolicLink = current.link,
        size = current.size,
        isHidden = if (windows) null else path.name.startsWith('.'),
        lastModifiedTime = current.modified,
        lastAccessTime = current.accessed,
        creationTime = current.created,
        posixPermissions = if (windows) null else PosixFilePermission.entries
            .filterIndexed { index, _ -> current.mode and (256 shr index) != 0 }.toSet()
    )
}

internal fun writeNodeAttributes(path: Path, changes: FileAttributesChanges, followLinks: Boolean, windows: Boolean) {
    val current = info(path, followLinks)

    if (changes.isHidden != null || changes.dosIsReadOnly != null || changes.creationTime != null)
        throw UnsupportedOperationException("Node cannot set hidden/DOS flags or birthtime")

    if (changes.posixPermissions != null && (windows || !followLinks && current.link))
        throw UnsupportedOperationException("POSIX permissions on Windows or on a symbolic link are not supported")

    changes.posixPermissions?.let {
        nodeCall(path) { nodeFs().chmodSync(path.nodeName, it.convertPermissions(windows)) }
    }
    if (changes.lastAccessTime != null || changes.lastModifiedTime != null) {
        setTimes(
            path, followLinks,
            changes.lastAccessTime ?: current.accessed,
            changes.lastModifiedTime ?: current.modified
        )
    }
}

internal fun nodeAccessible(path: Path, mode: Int): Boolean = try {
    nodeCall(path) { nodeFs().accessSync(path.nodeName, mode) }
    true
} catch (_: FileSystemException) { false }
