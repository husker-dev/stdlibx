@file:Suppress("NewApi")

package com.huskerdev.stdlibx.fs

/** Converts platform I/O failures without changing argument, state, or cancellation errors. */
internal inline fun <T> nioCall(path: Path? = null, block: () -> T): T = try {
    block()
} catch (error: java.nio.file.DirectoryIteratorException) {
    throw nioFailure(path, error.cause ?: error)
} catch (error: java.io.IOException) {
    throw nioFailure(path, error)
}

internal fun nioFailure(path: Path?, error: Throwable): FileSystemException {
    if (error is FileSystemException) return error
    val nio = error as? java.nio.file.FileSystemException

    val addressed = reportedPath(path, nio?.file) ?: path
    val other = reportedPath(path, nio?.otherFile)
    val reason = if (nio != null)
        nio.reason else error.message

    return when (error) {
        is java.nio.file.NoSuchFileException             -> NoSuchFileException(addressed, error, other, reason)
        is java.nio.file.FileAlreadyExistsException      -> FileAlreadyExistsException(addressed, error, other, reason)
        is java.nio.file.DirectoryNotEmptyException      -> DirectoryNotEmptyException(addressed, error, other, reason)
        is java.nio.file.AccessDeniedException           -> AccessDeniedException(addressed, error, other, reason)
        is java.nio.file.NotLinkException                -> NotLinkException(addressed, error, other, reason)
        is java.nio.file.FileSystemLoopException         -> FileSystemLoopException(addressed, error, reason)
        is java.nio.file.AtomicMoveNotSupportedException -> AtomicMoveNotSupportedException(addressed, other, reason, error)
        is java.nio.file.NotDirectoryException           -> FileSystemException(addressed, other, reason ?: "Not a directory", error)
        else -> FileSystemException(addressed, other, reason, error)
    }
}

/** Parses diagnostic paths only; translating an exception never accesses the file system. */
private fun reportedPath(context: Path?, name: String?): Path? =
    if (context == null || name == null) null else try {
        context.fileSystem.path(name)
    } catch (_: IllegalArgumentException) { null }
