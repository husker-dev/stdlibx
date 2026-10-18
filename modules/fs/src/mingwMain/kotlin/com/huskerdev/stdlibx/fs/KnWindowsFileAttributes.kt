@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import kotlinx.cinterop.*
import platform.windows.*

internal fun readWindowsAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot {
    val info = windowsInfo(path, followLinks)
    return FileAttributesSnapshot(
        isRegularFile = info.regular,
        isDirectory = info.directory,
        isSymbolicLink = info.symbolicLink,
        size = info.size,
        isHidden = info.attributes and FILE_ATTRIBUTE_HIDDEN.toUInt() != 0u,
        dosIsReadOnly = info.attributes and FILE_ATTRIBUTE_READONLY.toUInt() != 0u,
        creationTime = info.created,
        lastAccessTime = info.accessed,
        lastModifiedTime = info.modified
    )
}

internal fun writeWindowsAttributes(path: Path, changes: FileAttributesChanges, followLinks: Boolean) {
    if (changes.posixPermissions != null)
        throw UnsupportedOperationException("POSIX permissions are not supported on Windows")

    if (changes.creationTime != null || changes.lastAccessTime != null || changes.lastModifiedTime != null)
        setWindowsTimes(path, followLinks, changes.creationTime, changes.lastAccessTime, changes.lastModifiedTime)

    if (changes.isHidden != null || changes.dosIsReadOnly != null) {
        withWindowsHandle(path, FILE_WRITE_ATTRIBUTES.toUInt(), followLinks) { handle -> memScoped {

            val basic = alloc<FILE_BASIC_INFO>()
            checkWindows(path,
                GetFileInformationByHandleEx(
                    handle, FILE_INFO_BY_HANDLE_CLASS.FileBasicInfo,
                    basic.ptr,
                    sizeOf<FILE_BASIC_INFO>().toUInt()
                )
            )

            var flags = basic.FileAttributes and FILE_ATTRIBUTE_NORMAL.toUInt().inv()
            fun set(bit: Int, value: Boolean?) {
                if (value != null) {
                    flags = if (value) flags or bit.toUInt()
                    else flags and bit.toUInt().inv()
                }
            }
            set(FILE_ATTRIBUTE_HIDDEN, changes.isHidden)
            set(FILE_ATTRIBUTE_READONLY, changes.dosIsReadOnly)

            basic.FileAttributes = if (flags == 0u) FILE_ATTRIBUTE_NORMAL.toUInt() else flags
            checkWindows(path,
                SetFileInformationByHandle(
                    handle,
                    FILE_INFO_BY_HANDLE_CLASS.FileBasicInfo,
                    basic.ptr,
                    sizeOf<FILE_BASIC_INFO>().toUInt()
                )
            )
        } }
    }
}
