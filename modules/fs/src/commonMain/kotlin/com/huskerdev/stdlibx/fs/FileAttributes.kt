package com.huskerdev.stdlibx.fs

import kotlin.time.Instant

/**
 * File metadata read at a particular moment. Null fields indicate unavailable information.
 *
 * @property size The reported size in bytes.
 * @property lastModifiedTime The time the file's contents were last modified.
 * @property lastAccessTime The time the file was last accessed.
 * @property creationTime The file's creation time, where available.
 * @property dosIsReadOnly The DOS read-only flag, where supported.
 * @property posixPermissions The POSIX access permissions, where supported.
 */
data class FileAttributesSnapshot(
    val isRegularFile: Boolean? = null,
    val isDirectory: Boolean? = null,
    val isSymbolicLink: Boolean? = null,

    val size: Long? = null,

    val isHidden: Boolean? = null,

    val lastModifiedTime: Instant? = null,
    val lastAccessTime: Instant? = null,
    val creationTime: Instant? = null,

    val dosIsReadOnly: Boolean? = null,
    val posixPermissions: Set<PosixFilePermission>? = null,
)

/** Changes to file metadata. Null fields are left unchanged; support varies by file system. */
data class FileAttributesChanges(
    val isHidden: Boolean? = null,

    val lastModifiedTime: Instant? = null,
    val lastAccessTime: Instant? = null,
    val creationTime: Instant? = null,

    val dosIsReadOnly: Boolean? = null,
    val posixPermissions: Set<PosixFilePermission>? = null,
)

/** Read, write, and execute permissions for the owner, group, and other users. */
enum class PosixFilePermission {
    OWNER_READ,
    OWNER_WRITE,
    OWNER_EXECUTE,

    GROUP_READ,
    GROUP_WRITE,
    GROUP_EXECUTE,

    OTHERS_READ,
    OTHERS_WRITE,
    OTHERS_EXECUTE;
}
