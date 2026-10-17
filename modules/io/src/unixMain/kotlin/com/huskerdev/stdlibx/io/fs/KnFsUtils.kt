@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.huskerdev.stdlibx.io.fs

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.time.Instant

internal val unixPermissionBits = intArrayOf(
    0x100, 0x80, 0x40,
    0x20, 0x10, 0x8,
    0x4, 0x2, 0x1
)

internal val unixMimeTypes: Map<String, String> by lazy {
    val result = mutableMapOf<String, String>()
    listOf(
        "/etc/mime.types",
        "/etc/apache2/mime.types"
    ).forEach { name ->
        readOptionalMimeDatabase(name)?.let { result.putAll(parseUnixMimeTypes(it)) }
    }
    result
}

private val mimeTypePattern = Regex("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*")


internal expect fun clearUnixErrno()

internal expect fun stat(path: Path, followLinks: Boolean = true): UnixStat
internal expect fun setUnixTimes(path: Path, name: String, followLinks: Boolean, access: Instant, modified: Instant)

internal expect fun nativeHomeDirectory(): String?
internal expect fun nativeUserDirectory(kind: String): String?
internal expect fun nativeTemporaryDirectory(): String

internal expect fun nativeMimeType(fs: SyncKnUnixFileSystem, path: Path): String?

internal expect fun nativeMounts(): List<KnUnixFileStore>
internal expect fun nativeFileMount(path: Path, name: String): KnUnixFileStore?
internal expect fun nativeSpace(path: Path, name: String): UnixSpace


internal fun statOrNull(path: Path, followLinks: Boolean): UnixStat? = try {
    stat(path, followLinks)
} catch (_: NoSuchFileException) { null }

internal fun throwUnixError(
    path: Path,
    code: Int = errno
): Nothing = throw when (code) {
    ENOENT -> NoSuchFileException(path)
    ENOTDIR -> FileSystemException(path, "Not a directory")
    ELOOP -> FileSystemLoopException(path)
    EACCES, EPERM -> AccessDeniedException(path)
    EEXIST -> FileAlreadyExistsException(path)
    ENOTEMPTY -> DirectoryNotEmptyException(path)
    else -> FileSystemException(path, strerror(code)?.toKString() ?: "POSIX error $code")
}

internal fun checkUnixError(path: Path, result: Int) {
    if (result < 0) throwUnixError(path)
}

internal inline fun retryUnix(path: Path, operation: () -> Long): Long {
    while (true) {
        val result = operation()
        if (result >= 0) return result
        val code = errno
        if (code != EINTR) throwUnixError(path, code)
    }
}

internal fun open(path: Path, name: String, flags: Int, mode: Int = 0): Int =
    retryUnix(path) { open(name, flags, mode.convert<mode_t>()).toLong() }.toInt()

internal inline fun <T> withUnixDescriptor(path: Path, fd: Int, action: (Int) -> T): T {
    var failure: Throwable? = null
    try {
        return action(fd)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try { checkUnixError(path, close(fd)) } catch (error: Throwable) {
            if (failure == null) throw error
            if (failure !== error) failure.addSuppressed(error)
        }
    }
}

internal fun unixEnvironmentPath(name: String): String? =
    getenv(name)?.toKString()?.takeIf { it.startsWith('/') }

internal fun decodeMountField(value: String): String =
    Regex("\\\\([0-7]{3})").replace(value) { it.groupValues[1].toInt(8).toChar().toString() }

internal fun readLink(
    path: Path,
    bytes: ByteArray
): Int = bytes.usePinned { pinned ->
    retryUnix(path) {
        val result = readlink(path.absolute().pathString, pinned.addressOf(0), bytes.size.convert())
        if (result < 0 && errno == EINVAL) throw NotLinkException(path)
        result
    }
}.toInt()

internal fun convertPermissions(permissions: Set<PosixFilePermission>?, default: Int): Int =
    permissions?.fold(0) { mode, permission ->
        mode or unixPermissionBits[permission.ordinal]
    } ?: default

internal fun isSameFileOrCheckOverwrite(source: UnixStat, target: Path, overwrite: Boolean): Boolean {
    val targetInfo = statOrNull(target, false) ?: return false
    if (source.sameFile(targetInfo)) return true
    if (!overwrite) throw FileAlreadyExistsException(target)
    return false
}

internal fun readText(name: String): String? = try {
    val fs = FileSystem.default!!.asSync()!!
    fs.readString(fs.path(name))
} catch (_: NoSuchFileException) { null }

internal fun readOptionalMimeDatabase(name: String): String? = try {
    readText(name)
} catch (_: FileSystemException) { null }

internal fun parseUnixMimeTypes(database: String): Map<String, String> = buildMap {
    for (line in database.lineSequence()) {
        val fields = line.substringBefore('#').trim().split(Regex("\\s+"))
        if (fields.size < 2) continue
        val mimeType = validMimeType(fields[0]) ?: continue
        for (extension in fields.drop(1)) put(extension.lowercase(), mimeType)
    }
}

internal fun validMimeType(value: String): String? =
    value.takeIf { mimeTypePattern.matches(it) }


internal fun unixWriteFlags(mode: FileWriteMode, creation: FileCreation): Int =
    O_WRONLY or O_CLOEXEC or when (mode) {
        FileWriteMode.FROM_START -> 0
        FileWriteMode.TRUNCATE -> O_TRUNC
        FileWriteMode.APPEND -> O_APPEND
    } or when (creation) {
        FileCreation.OPEN_EXISTING -> 0
        FileCreation.CREATE_IF_MISSING -> O_CREAT
        FileCreation.CREATE_NEW -> O_CREAT or O_EXCL
    }
