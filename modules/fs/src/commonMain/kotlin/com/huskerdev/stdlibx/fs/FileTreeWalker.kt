package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.io.SyncStream
import com.huskerdev.stdlibx.io.AsyncStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Result of a [SyncFileTreeVisitor] or [AsyncFileTreeVisitor] callback. */
enum class FileTreeVisitResult {
    /** Continue the walk. */
    CONTINUE,

    /** Skips a directory's descendants and post-visit callback when returned from `preVisitDirectory`. */
    SKIP_SUBTREE,

    /** Stops the walk without post-visit callbacks for unfinished directories. */
    TERMINATE
}

/** Receives file and directory callbacks during a blocking tree walk. */
interface SyncFileTreeVisitor {
    /** Called for a directory before its entries are visited. */
    fun preVisitDirectory(path: SyncPath, attributes: FileAttributesSnapshot): FileTreeVisitResult =
        FileTreeVisitResult.CONTINUE

    /** Called for a visited path that is not descended into: a file, or a directory at maxDepth. */
    fun visit(path: SyncPath, attributes: FileAttributesSnapshot): FileTreeVisitResult

    /** Called after all entries of the directory have been visited. */
    fun postVisitDirectory(path: SyncPath, attributes: FileAttributesSnapshot): FileTreeVisitResult =
        FileTreeVisitResult.CONTINUE
}

/** Receives suspending file and directory callbacks during a tree walk. */
interface AsyncFileTreeVisitor {
    /** Called for a directory before its entries are visited. */
    suspend fun preVisitDirectory(path: AsyncPath, attributes: FileAttributesSnapshot): FileTreeVisitResult =
        FileTreeVisitResult.CONTINUE

    /** Called for a file or a directory whose entries are beyond the walk's depth limit. */
    suspend fun visit(path: AsyncPath, attributes: FileAttributesSnapshot): FileTreeVisitResult

    /** Called after all entries of the directory have been visited. */
    suspend fun postVisitDirectory(path: AsyncPath, attributes: FileAttributesSnapshot): FileTreeVisitResult =
        FileTreeVisitResult.CONTINUE
}

/**
 * Walks file trees depth-first. The starting path has depth zero and is always visited.
 * Symbolic links are not followed by default; detected cycles throw [FileSystemLoopException].
 * Directory streams are closed even if traversal fails or is cancelled.
 *
 * @property maxDepth Maximum visited depth; must be non-negative.
 * @property followLinks Whether symbolic links to directories are traversed.
 */
class FileTreeWalker(
    val maxDepth: Int = Int.MAX_VALUE,
    val followLinks: Boolean = false
) {
    init {
        require(maxDepth >= 0) { "maxDepth must be non-negative" }
    }

    /**
     * Calls [visitor] once per visited path, before any descendants. Does not send post-visit events.
     */
    fun walk(start: SyncPath, visitor: (SyncPath, FileAttributesSnapshot) -> FileTreeVisitResult) =
        walk(start, object : SyncFileTreeVisitor {
            override fun preVisitDirectory(path: SyncPath, attributes: FileAttributesSnapshot) =
                visitor(path, attributes)

            override fun visit(path: SyncPath, attributes: FileAttributesSnapshot) =
                visitor(path, attributes)
        })

    /** Walks the tree rooted at [start]. */
    fun walk(start: SyncPath, visitor: SyncFileTreeVisitor) {
        val frames = mutableListOf<SyncFrame>()
        var failure: Throwable? = null

        fun visit(path: SyncPath, depth: Int): FileTreeVisitResult {
            val attributes = path.readAttributes(followLinks)
            if (depth >= maxDepth || attributes.isDirectory != true)
                return visitor.visit(path, attributes)
            if (followLinks && frames.any { path.isSameFileAs(it.path) })
                throw FileSystemLoopException(path)

            val pre = visitor.preVisitDirectory(path, attributes)
            if (pre != FileTreeVisitResult.CONTINUE) return pre
            frames.add(SyncFrame(path, depth, attributes, path.streamDirectory()))
            return FileTreeVisitResult.CONTINUE
        }

        try {
            if (visit(start, 0) == FileTreeVisitResult.TERMINATE) return
            while (frames.isNotEmpty()) {
                val frame = frames.last()
                if (frame.stream.hasNext()) {
                    if (visit(frame.stream.next(), frame.depth + 1) == FileTreeVisitResult.TERMINATE) return
                } else {
                    frames.removeAt(frames.lastIndex)
                    frame.stream.close()
                    if (visitor.postVisitDirectory(frame.path, frame.attributes) ==
                        FileTreeVisitResult.TERMINATE) return
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            while (frames.isNotEmpty()) {
                val frame = frames.removeAt(frames.lastIndex)
                try {
                    frame.stream.close()
                } catch (error: Throwable) {
                    if (failure == null) failure = error
                    else if (failure !== error) failure.addSuppressed(error)
                }
            }
            // Do not replace a walk error with a later close error.
            failure?.let { throw it }
        }
    }

    /** Calls [visitor] once per visited path, before any descendants, starting at [start]. */
    suspend fun walk(start: AsyncPath, visitor: suspend (AsyncPath, FileAttributesSnapshot) -> FileTreeVisitResult) =
        walk(start, object : AsyncFileTreeVisitor {
            override suspend fun preVisitDirectory(path: AsyncPath, attributes: FileAttributesSnapshot) =
                visitor(path, attributes)

            override suspend fun visit(path: AsyncPath, attributes: FileAttributesSnapshot) =
                visitor(path, attributes)
        })

    /** Walks the tree rooted at [start], delivering directory and file callbacks to [visitor]. */
    suspend fun walk(start: AsyncPath, visitor: AsyncFileTreeVisitor) {
        val frames = mutableListOf<AsyncFrame>()
        var failure: Throwable? = null

        suspend fun visit(path: AsyncPath, depth: Int): FileTreeVisitResult {
            val attributes = path.readAttributes(followLinks)
            if (depth >= maxDepth || attributes.isDirectory != true)
                return visitor.visit(path, attributes)
            if (followLinks) {
                for (frame in frames) {
                    if (path.isSameFileAs(frame.path))
                        throw FileSystemLoopException(path)
                }
            }

            val pre = visitor.preVisitDirectory(path, attributes)
            if (pre != FileTreeVisitResult.CONTINUE) return pre
            frames.add(AsyncFrame(path, depth, attributes, path.streamDirectory()))
            return FileTreeVisitResult.CONTINUE
        }

        try {
            if (visit(start, 0) == FileTreeVisitResult.TERMINATE) return
            while (frames.isNotEmpty()) {
                val frame = frames.last()
                if (frame.stream.hasNext()) {
                    if (visit(frame.stream.next(), frame.depth + 1) == FileTreeVisitResult.TERMINATE) return
                } else {
                    frames.removeAt(frames.lastIndex)
                    frame.stream.close()
                    if (visitor.postVisitDirectory(frame.path, frame.attributes) ==
                        FileTreeVisitResult.TERMINATE) return
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                while (frames.isNotEmpty()) {
                    val frame = frames.removeAt(frames.lastIndex)
                    try {
                        frame.stream.close()
                    } catch (error: Throwable) {
                        if (failure == null) failure = error
                        else if (failure !== error) failure.addSuppressed(error)
                    }
                }
            }
            // Do not replace a walk error with a later close error.
            failure?.let { throw it }
        }
    }

    private class SyncFrame(
        val path: SyncPath, val depth: Int, val attributes: FileAttributesSnapshot, val stream: SyncStream<SyncPath>
    )

    private class AsyncFrame(
        val path: AsyncPath, val depth: Int, val attributes: FileAttributesSnapshot, val stream: AsyncStream<AsyncPath>
    )
}
