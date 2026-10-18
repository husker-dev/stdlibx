package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.io.IOException

/**
 * A file operation failure. [path] and [otherPath] identify the affected entries; [reason]
 * describes the failure separately from the formatted message. Unknown details may be null.
 */
open class FileSystemException(
    val path: Path?,
    val otherPath: Path? = null,
    val reason: String? = null,
    cause: Throwable? = null,
) : IOException(fileSystemMessage(path, otherPath, reason), cause) {
    /** Creates a failure with a reason and no second path. */
    constructor(
        path: Path?,
        message: String,
        cause: Throwable? = null,
    ) : this(path, null, message, cause)
}

/** An operation required an entry that does not exist. */
class NoSuchFileException(
    path: Path?,
    cause: Throwable? = null,
    otherPath: Path? = null,
    reason: String? = "No such file",
) : FileSystemException(path, otherPath, reason, cause)

/** An entry already exists where an operation requires a new one. */
class FileAlreadyExistsException(
    path: Path?,
    cause: Throwable? = null,
    otherPath: Path? = null,
    reason: String? = "File already exists",
) : FileSystemException(path, otherPath, reason, cause)

/** A directory could not be deleted because it still contains entries. */
class DirectoryNotEmptyException(
    path: Path?,
    cause: Throwable? = null,
    otherPath: Path? = null,
    reason: String? = "Directory not empty",
) : FileSystemException(path, otherPath, reason, cause)

/** The current process does not have permission to perform the file operation. */
class AccessDeniedException(
    path: Path?,
    cause: Throwable? = null,
    otherPath: Path? = null,
    reason: String? = "Access denied",
) : FileSystemException(path, otherPath, reason, cause)

/** An operation required a symbolic link, but the entry is not a link. */
class NotLinkException(
    path: Path?,
    cause: Throwable? = null,
    otherPath: Path? = null,
    reason: String? = "Not a symbolic link",
) : FileSystemException(path, otherPath, reason, cause)

/** Resolving links or walking a directory tree encountered a cycle. */
class FileSystemLoopException(
    path: Path?,
    cause: Throwable? = null,
    reason: String? = "File system loop",
) : FileSystemException(path, null, reason, cause)

/** The requested move cannot be performed atomically; no non-atomic fallback is attempted. */
class AtomicMoveNotSupportedException(
    path: Path?,
    otherPath: Path?,
    reason: String? = "Atomic move is not supported",
    cause: Throwable? = null,
) : FileSystemException(path, otherPath, reason, cause)

private fun fileSystemMessage(path: Path?, otherPath: Path?, reason: String?): String? {
    if (path == null && otherPath == null) return reason
    return buildString {
        path?.let { append(it.pathString) }
        otherPath?.let {
            append(" -> ")
            append(it.pathString)
        }
        reason?.let {
            append(": ")
            append(it)
        }
    }
}
