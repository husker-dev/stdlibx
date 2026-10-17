@file:Suppress("NewApi")

package com.huskerdev.stdlibx.io.fs

import java.nio.file.Files
import com.huskerdev.stdlibx.io.SyncStream

class NioDirectoryIterator(
    val fs: NioFileSystem,
    val directory: Path
): SyncStream<SyncPath> {
    private val stream = nioCall(directory) {
        Files.newDirectoryStream(fs.toNio(directory))
    }

    private val streamIterator = try {
        nioCall(directory) { stream.iterator() }
    } catch (error: Throwable) {
        try {
            nioCall(directory) { stream.close() } }
        catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
        throw error
    }

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        nioCall(directory) {
            stream.close()
        }
    }

    override fun next(): SyncPath {
        check(!closed) { "Directory stream is closed" }
        return nioCall(directory) {
            fs.fromNio(streamIterator.next())
        }
    }

    override fun hasNext(): Boolean =
        !closed && nioCall(directory) { streamIterator.hasNext() }
}
