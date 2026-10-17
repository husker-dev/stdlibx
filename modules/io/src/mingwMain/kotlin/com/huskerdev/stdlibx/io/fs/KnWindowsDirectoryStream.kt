@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io.fs

import kotlinx.cinterop.*
import com.huskerdev.stdlibx.io.SyncStream
import platform.windows.*

class KnWindowsDirectoryStream internal constructor(val directory: SyncPath): SyncStream<SyncPath> {
    private var handle: HANDLE? = null
    private var pending: SyncPath? = null
    private var ended = false
    private var closed = false

    init {
        if (!windowsInfo(directory).directory)
            throw FileSystemException(directory, "Not a directory")
        memScoped {
            val data = alloc<WIN32_FIND_DATAW>()
            val name = directory.absolute().pathString.trimEnd('\\') + "\\*"
            val found = FindFirstFileW(name, data.ptr)
            if (found == null || found == INVALID_HANDLE_VALUE) {
                val code = GetLastError()
                if (code == ERROR_FILE_NOT_FOUND.toUInt()) ended = true else throwWindowsError(directory, code)
            } else {
                handle = found
                try {
                    val child = data.cFileName.toKStringFromUtf16()
                    if (child != "." && child != "..") pending = childPath(child)
                } catch (error: Throwable) {
                    try {
                        checkWindows(directory, FindClose(found))
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                    throw error
                }
            }
        }
    }

    override fun hasNext(): Boolean {
        if (closed || ended) return false
        if (pending != null) return true
        return memScoped {
            val data = alloc<WIN32_FIND_DATAW>()
            while (true) {
                if (FindNextFileW(handle, data.ptr) == 0) {
                    val code = GetLastError()
                    if (code != ERROR_NO_MORE_FILES.toUInt()) throwWindowsError(directory, code)
                    ended = true
                    return@memScoped false
                }
                val child = data.cFileName.toKStringFromUtf16()
                if (child != "." && child != "..") {
                    pending = childPath(child)
                    return@memScoped true
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }
    }

    override fun next(): SyncPath {
        check(!closed) { "Directory stream is closed" }
        if (!hasNext()) throw NoSuchElementException()
        return pending!!.also { pending = null }
    }

    private fun childPath(name: String): SyncPath =
        if (directory.pathString.startsWith("\\\\?\\"))
            directory.fileSystem.path(directory.pathString.trimEnd('\\') + "\\" + name)
        else directory.resolve(name)

    override fun close() {
        if (closed) return
        closed = true
        pending = null
        handle?.let { checkWindows(directory, FindClose(it)) }
        handle = null
    }
}
