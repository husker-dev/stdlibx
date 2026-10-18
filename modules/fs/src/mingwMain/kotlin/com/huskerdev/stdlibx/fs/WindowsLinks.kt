@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import kotlinx.cinterop.*
import platform.windows.*

internal fun createWindowsLink(link: Path, target: Path, directory: Boolean): Path {
    val flags = if (directory) SYMBOLIC_LINK_FLAG_DIRECTORY.toUInt() else 0u
    val name = link.absolute().pathString
    if (CreateSymbolicLinkW(
            name,
            target.pathString,
            flags or SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE.toUInt()
    ) == 0.toUByte()) {
        val code = GetLastError()
        if (code != ERROR_INVALID_PARAMETER.toUInt()) throwWindowsError(link, code)
        if (CreateSymbolicLinkW(
                name,
                target.pathString,
                flags
        ) == 0.toUByte()) throwWindowsError(link)
    }
    return link
}

internal fun readWindowsLink(path: Path): String = withWindowsHandle(path, followLinks = false) { handle -> memScoped {
    val buffer = ByteArray(MAXIMUM_REPARSE_DATA_BUFFER_SIZE)
    val count = alloc<UIntVar>()
    buffer.usePinned {
        checkWindows(path, DeviceIoControl(
            handle,
            FSCTL_GET_REPARSE_POINT.toUInt(),
            null,
            0u,
            it.addressOf(0),
            buffer.size.toUInt(),
            count.ptr,
            null
        ))
    }
    decodeWindowsLink(path, buffer, count.value.toInt())
} }

/** Decode the documented Windows reparse wire layout, which is independent of pointer size. */
internal fun decodeWindowsLink(path: Path, bytes: ByteArray, count: Int): String {
    fun ushort(offset: Int): Int {
        if (offset < 0 || offset > count - 2)
            throw FileSystemException(path, "Invalid reparse data")
        return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    }
    require(count in 0..bytes.size)
    val tag = ushort(0).toUInt() or (ushort(2).toUInt() shl 16)

    val dataEnd = 8 + ushort(4)
    if (dataEnd > count)
        throw FileSystemException(path, "Invalid reparse data length")

    val base = when (tag) {
        IO_REPARSE_TAG_SYMLINK -> 20
        IO_REPARSE_TAG_MOUNT_POINT -> 16
        else -> throw NotLinkException(path, reason = "Not a symbolic link or junction")
    }

    val printLength = ushort(14)

    val offset = if (printLength != 0)
        ushort(12) else ushort(8)

    val length = if (printLength != 0)
        printLength else ushort(10)

    if (offset % 2 != 0 || length % 2 != 0 || base + offset + length > dataEnd)
        throw FileSystemException(path, "Invalid reparse target")

    val target = CharArray(length / 2) {
        ushort(base + offset + it * 2).toChar()
    }.concatToString()
    return windowsDosPath(target)
}
