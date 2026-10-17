@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io.fs

import kotlinx.cinterop.*
import platform.windows.*
import kotlin.time.Instant

internal fun checkWindows(path: Path?, result: Int) {
    if (result == 0) throwWindowsError(path)
}

internal fun throwWindowsError(path: Path?, code: UInt = GetLastError()): Nothing {
    val message = memScoped {
        val buffer = allocArray<UShortVar>(2048)
        val size = FormatMessageW(
            (FORMAT_MESSAGE_FROM_SYSTEM or FORMAT_MESSAGE_IGNORE_INSERTS).toUInt(),
            null,
            code,
            0u,
            buffer,
            2048u,
            null
        )
        if (size == 0u) "Windows error $code" else buffer.toKStringFromUtf16().trim()
    }
    val reason = "$message (Windows error $code)"
    if (path != null) when (code) {
        ERROR_FILE_NOT_FOUND.toUInt(),
        ERROR_PATH_NOT_FOUND.toUInt() -> throw NoSuchFileException(path, reason = reason)
        ERROR_ACCESS_DENIED.toUInt(),
        ERROR_PRIVILEGE_NOT_HELD.toUInt() -> throw AccessDeniedException(path, reason = reason)
        ERROR_FILE_EXISTS.toUInt(),
        ERROR_ALREADY_EXISTS.toUInt() -> throw FileAlreadyExistsException(path, reason = reason)
        ERROR_DIR_NOT_EMPTY.toUInt() -> throw DirectoryNotEmptyException(path, reason = reason)
        ERROR_NOT_A_REPARSE_POINT.toUInt() -> throw NotLinkException(path, reason = reason)
    }
    throw FileSystemException(path, reason)
}

internal inline fun windowsString(path: Path?, query: (UInt, CPointer<UShortVar>) -> UInt): String =
    withWindowsStringBuffer(path, query) { buffer, _ -> buffer.toKStringFromUtf16() }

/** Reads a WinAPI MULTI_SZ list rather than stopping at the first NUL. */
internal inline fun windowsStrings(path: Path?, query: (UInt, CPointer<UShortVar>) -> UInt): List<String> =
    withWindowsStringBuffer(path, query) { buffer, count ->
        buildList {
            var start = 0
            for (index in 0 until count) {
                if (buffer[index] == 0.toUShort()) {
                    if (index == start) break
                    add((buffer + start)!!.toKStringFromUtf16())
                    start = index + 1
                }
            }
        }
    }

/** A growing UTF-16 buffer for WinAPI functions returning the required character count. */
private inline fun <T> withWindowsStringBuffer(
    path: Path?,
    query: (UInt, CPointer<UShortVar>) -> UInt,
    decode: (CPointer<UShortVar>, Int) -> T
): T {
    var capacity = 256
    while (true) {
        memScoped {
            val buffer = allocArray<UShortVar>(capacity)
            val count = query(capacity.toUInt(), buffer)
            if (count == 0u) throwWindowsError(path)
            if (count < capacity.toUInt()) return decode(buffer, count.toInt())
            if (count >= Int.MAX_VALUE.toUInt()) throw FileSystemException(path, "Windows path is too long")
            capacity = count.toInt() + 1
        }
    }
}

internal fun openWindows(
    path: Path,
    access: UInt = 0u,
    disposition: UInt = OPEN_EXISTING.toUInt(),
    followLinks: Boolean = true
): HANDLE {
    val flags = FILE_FLAG_BACKUP_SEMANTICS.toUInt() or
        if (followLinks) 0u else FILE_FLAG_OPEN_REPARSE_POINT.toUInt()
    val handle = CreateFileW(
        path.absolute().pathString,
        access,
        (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).toUInt(),
        null,
        disposition,
        flags,
        null
    )
    if (handle == null || handle == INVALID_HANDLE_VALUE) throwWindowsError(path)
    return handle
}

internal inline fun <T> withWindowsHandle(
    path: Path,
    access: UInt = 0u,
    followLinks: Boolean = true,
    block: (HANDLE) -> T
): T {
    val handle = openWindows(path, access, followLinks = followLinks)
    var failure: Throwable? = null
    try {
        return block(handle)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try { checkWindows(path, CloseHandle(handle)) } catch (error: Throwable) {
            if (failure == null) throw error
            if (failure !== error) failure.addSuppressed(error)
        }
    }
}

internal fun SyncFileSystem.knownFolder(id: CValuesRef<KNOWNFOLDERID>): SyncPath? = memScoped {
    val result = alloc<CPointerVar<UShortVar>>()
    result.value = null
    val status = SHGetKnownFolderPath(id, 0u, null, result.ptr)
    try {
        if (status < 0) null else result.value?.toKStringFromUtf16()?.let { path(it) }
    } finally {
        CoTaskMemFree(result.value)
    }
}

internal data class WindowsFileInfo(
    val attributes: UInt, val tag: UInt, val size: Long,
    val volume: UInt, val index: ULong,
    val created: Instant, val accessed: Instant, val modified: Instant
) {
    val directoryAttribute: Boolean
        get() = attributes and FILE_ATTRIBUTE_DIRECTORY.toUInt() != 0u

    val directory: Boolean
        get() = directoryAttribute && !symbolicLink && !junction

    val symbolicLink: Boolean
        get() = tag == IO_REPARSE_TAG_SYMLINK

    val junction: Boolean
        get() = tag == IO_REPARSE_TAG_MOUNT_POINT

    val regular: Boolean
        get() = !directoryAttribute && !symbolicLink && !junction

    fun sameFile(other: WindowsFileInfo): Boolean = volume == other.volume && index == other.index
}

internal fun windowsInfo(path: Path, followLinks: Boolean = true): WindowsFileInfo =
    withWindowsHandle(path, followLinks = followLinks) { handle -> memScoped {
        val info = alloc<BY_HANDLE_FILE_INFORMATION>()
        checkWindows(path, GetFileInformationByHandle(handle, info.ptr))
        var tag = 0u
        if (info.dwFileAttributes and FILE_ATTRIBUTE_REPARSE_POINT.toUInt() != 0u) {
            val tags = alloc<FILE_ATTRIBUTE_TAG_INFO>()
            checkWindows(path, GetFileInformationByHandleEx(
                handle,
                FILE_INFO_BY_HANDLE_CLASS.FileAttributeTagInfo,
                tags.ptr,
                sizeOf<FILE_ATTRIBUTE_TAG_INFO>().toUInt()
            ))
            tag = tags.ReparseTag
        }
        WindowsFileInfo(info.dwFileAttributes, tag,
            ((info.nFileSizeHigh.toULong() shl 32) or info.nFileSizeLow.toULong()).toLong(),
            info.dwVolumeSerialNumber,
            (info.nFileIndexHigh.toULong() shl 32) or info.nFileIndexLow.toULong(),
            info.ftCreationTime.toInstant(),
            info.ftLastAccessTime.toInstant(),
            info.ftLastWriteTime.toInstant()
        )
    } }

internal fun windowsInfoOrNull(path: Path, followLinks: Boolean = true): WindowsFileInfo? = try {
    windowsInfo(path, followLinks)
} catch (_: NoSuchFileException) { null }

private const val WINDOWS_EPOCH_OFFSET = 11644473600L
private const val WINDOWS_TICKS_PER_SECOND = 10000000uL

internal fun FILETIME.toInstant(): Instant {
    val ticks = (dwHighDateTime.toULong() shl 32) or dwLowDateTime.toULong()
    return Instant.fromEpochSeconds((ticks / WINDOWS_TICKS_PER_SECOND).toLong() - WINDOWS_EPOCH_OFFSET,
        ((ticks % WINDOWS_TICKS_PER_SECOND) * 100u).toLong())
}

internal fun Instant.toWindowsTicks(): ULong {
    require(epochSeconds >= -WINDOWS_EPOCH_OFFSET) { "Windows timestamps cannot precede 1601" }
    val seconds = (epochSeconds + WINDOWS_EPOCH_OFFSET).toULong()
    val fraction = (nanosecondsOfSecond / 100).toULong()
    require(seconds <= (ULong.MAX_VALUE - fraction) / WINDOWS_TICKS_PER_SECOND) { "Windows timestamp is out of range" }
    return seconds * WINDOWS_TICKS_PER_SECOND + fraction
}

internal fun FILETIME.set(value: Instant) {
    val ticks = value.toWindowsTicks()
    dwHighDateTime = (ticks shr 32).toUInt()
    dwLowDateTime = ticks.toUInt()
}

internal fun setWindowsTimes(path: Path, followLinks: Boolean, created: Instant? = null,
                             accessed: Instant? = null, modified: Instant? = null) {
    withWindowsHandle(path, FILE_WRITE_ATTRIBUTES.toUInt(), followLinks) { handle -> memScoped {
        val creation = created?.let { alloc<FILETIME>().apply { set(it) }.ptr }
        val access = accessed?.let { alloc<FILETIME>().apply { set(it) }.ptr }
        val modification = modified?.let { alloc<FILETIME>().apply { set(it) }.ptr }
        checkWindows(path, SetFileTime(handle, creation, access, modification))
    } }
}

internal fun windowsDosPath(name: String): String = when {
    name.startsWith("\\\\?\\UNC\\", ignoreCase = true) -> "\\\\" + name.substring(8)
    name.startsWith("\\??\\UNC\\", ignoreCase = true) -> "\\\\" + name.substring(8)
    (name.startsWith("\\\\?\\") || name.startsWith("\\??\\")) && name.getOrNull(5) == ':' -> name.substring(4)
    name.startsWith("\\??\\") -> "\\\\?\\" + name.substring(4)
    else -> name
}
