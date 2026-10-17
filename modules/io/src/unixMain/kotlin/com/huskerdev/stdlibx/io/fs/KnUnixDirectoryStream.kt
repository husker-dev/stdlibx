@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io.fs

import kotlinx.cinterop.*
import com.huskerdev.stdlibx.io.SyncStream
import platform.posix.*

class KnUnixDirectoryStream(val directory: SyncPath): SyncStream<SyncPath> {
    private val handle = opendir(directory.absolute().pathString) ?: throwUnixError(directory)
    private var closed = false
    private var pending: SyncPath? = null
    private var ended = false

    override fun close() {
        if (closed) return
        closed = true
        pending = null
        checkUnixError(directory, closedir(handle))
    }

    override fun hasNext(): Boolean {
        if (closed || ended) return false
        if (pending != null) return true
        while (true) {
            clearUnixErrno()
            val entry = readdir(handle)
            if (entry == null) {
                if (errno != 0) throwUnixError(directory)
                ended = true
                return false
            }
            val name = entry.pointed.d_name.toKString()
            if (name != "." && name != "..") {
                pending = directory.resolve(name)
                return true
            }
        }
    }

    override fun next(): SyncPath {
        check(!closed) { "Directory stream is closed" }
        if (!hasNext()) throw NoSuchElementException()
        return pending!!.also { pending = null }
    }
}
