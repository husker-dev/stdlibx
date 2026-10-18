@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.io.*
import kotlinx.cinterop.*
import platform.windows.*

internal class WindowsInputStream(private val path: Path, private val handle: HANDLE): SyncInputStream {
    private val async by lazy { WrappedAsyncInputStream(this) }
    private var closed = false
    private var pending: Byte? = null
    private var eof = false

    private fun checkOpen() = check(!closed) { "Input stream is closed" }

    private fun readNative(bytes: ByteArray, offset: Int, length: Int): Int = memScoped {
        val count = alloc<UIntVar>()
        val success = bytes.usePinned { ReadFile(handle, it.addressOf(offset), length.toUInt(), count.ptr, null) }
        if (success == 0) {
            val code = GetLastError()
            if (code == ERROR_BROKEN_PIPE.toUInt() || code == ERROR_HANDLE_EOF.toUInt()) return@memScoped 0
            throwWindowsError(path, code)
        }
        count.value.toInt()
    }

    override fun hasNext(): Boolean {
        checkOpen()
        if (pending != null) return true
        if (eof) return false
        val byte = ByteArray(1)
        if (readNative(byte, 0, 1) == 0) { eof = true; return false }
        pending = byte[0]
        return true
    }

    override fun read(): Byte {
        if (!hasNext()) throw NoSuchElementException()
        return pending!!.also { pending = null }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkOpen()
        checkBounds(buffer, offset, length)
        if (length == 0) return 0
        var count = 0
        pending?.let { buffer[offset] = it; pending = null; count = 1 }
        if (count < length && !eof) {
            val read = readNative(buffer, offset + count, length - count)
            if (read == 0) eof = true
            count += read
        }
        return count
    }

    override fun availableBytes(): Long {
        checkOpen()
        return memScoped {
            val cached = if (pending == null) 0L else 1L
            if (GetFileType(handle) == FILE_TYPE_PIPE.toUInt()) {
                val available = alloc<UIntVar>()
                if (PeekNamedPipe(handle, null, 0u, null, available.ptr, null) == 0) {
                    if (GetLastError() == ERROR_BROKEN_PIPE.toUInt()) return@memScoped cached
                    throwWindowsError(path)
                }
                return@memScoped available.value.toLong() + cached
            }
            if (GetFileType(handle) != FILE_TYPE_DISK.toUInt()) return@memScoped cached
            val size = alloc<LARGE_INTEGER>()
            val position = alloc<LARGE_INTEGER>()
            checkWindows(path, GetFileSizeEx(handle, size.ptr))
            checkWindows(path, SetFilePointerEx(handle, cValue<LARGE_INTEGER> { QuadPart = 0 }, position.ptr, FILE_CURRENT.toUInt()))
            (size.QuadPart - position.QuadPart).coerceAtLeast(0) + cached
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pending = null
        checkWindows(path, CloseHandle(handle))
    }

    override fun asAsync() = async
}

internal class WindowsOutputStream(private val path: Path, private val handle: HANDLE): SyncOutputStream {
    private val async by lazy { WrappedAsyncOutputStream(this) }
    private var closed = false

    private fun checkOpen() = check(!closed) { "Output stream is closed" }

    override fun write(byte: Byte) =
        write(byteArrayOf(byte), 0, 1)

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkOpen()
        checkBounds(bytes, offset, length)
        writeWindowsFully(path, handle, bytes, offset, length)
    }

    // This stream has no application-side buffer. flush() is not a durability/fsync operation.
    override fun flush() {
        checkOpen()
    }

    override fun close() {
        if (closed) return
        closed = true
        checkWindows(path, CloseHandle(handle))
    }

    override fun asAsync(): AsyncOutputStream = async
}

/** Writes a validated byte range, handling partial writes from WriteFile. */
internal fun writeWindowsFully(path: Path, handle: HANDLE, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
    if (length == 0) return
    memScoped {
        val written = alloc<UIntVar>()
        bytes.usePinned { pinned ->
            var count = 0
            while (count < length) {
                checkWindows(path, WriteFile(handle, pinned.addressOf(offset + count),
                    (length - count).toUInt(), written.ptr, null))
                if (written.value == 0u) throw FileSystemException(path, "Write made no progress")
                count += written.value.toInt()
            }
        }
    }
}
