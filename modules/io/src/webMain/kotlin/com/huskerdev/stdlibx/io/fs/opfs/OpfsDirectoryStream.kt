package com.huskerdev.stdlibx.io.fs.opfs

import com.huskerdev.stdlibx.io.AsyncStream
import com.huskerdev.stdlibx.io.fs.AsyncPath
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class OpfsDirectoryStream(
    val entries: OpfsDirectoryEntries,
    val path: AsyncPath
): AsyncStream<AsyncPath> {
    private var pending: AsyncPath? = null
    private var closed = false
    private var ended = false

    override suspend fun hasNext(): Boolean {
        if (closed || ended) return false
        currentCoroutineContext().ensureActive()
        if (pending != null) return true

        val entry = opfsCall(path) { entries.next() }
        if (entry == null) {
            ended = true
            return false
        }
        pending = path.resolve(entry.name)
        return true
    }

    override suspend fun next(): AsyncPath {
        check(!closed) { "Directory stream is closed" }
        if (!hasNext())
            throw NoSuchElementException()
        return pending!!.also { pending = null }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        pending = null
        opfsCall(path) { entries.close() }
    }
}
