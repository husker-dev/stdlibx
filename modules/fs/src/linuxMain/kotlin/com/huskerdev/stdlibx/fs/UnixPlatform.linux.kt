@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.fs.interop.utimensat
import com.huskerdev.stdlibx.fs.interop.IO_AT_FDCWD
import com.huskerdev.stdlibx.fs.interop.IO_AT_SYMLINK_NOFOLLOW
import kotlinx.cinterop.*
import platform.linux.ST_RDONLY
import platform.linux.statvfs
import platform.posix.*
import kotlin.time.Instant

internal actual fun clearUnixErrno() { __errno_location()!!.pointed.value = 0 }

internal actual fun stat(path: Path, followLinks: Boolean): UnixStat = memScoped {
    val name = path.absolute().pathString
    val info = alloc<stat>()
    checkUnixError(path, if (followLinks) stat(name, info.ptr) else lstat(name, info.ptr))
    UnixStat(
        info.st_dev,
        info.st_ino,
        info.st_mode.toInt(),
        info.st_size,
        accessTime = Instant.fromEpochSeconds(
            epochSeconds = info.st_atim.tv_sec,
            nanosecondAdjustment = info.st_atim.tv_nsec),
        modifiedTime = Instant.fromEpochSeconds(
            epochSeconds = info.st_mtim.tv_sec,
            nanosecondAdjustment = info.st_mtim.tv_nsec),
        null
    )
}

internal actual fun setUnixTimes(
    path: Path,
    name: String,
    followLinks: Boolean,
    access: Instant,
    modified: Instant
) = memScoped {
    val times = allocArray<timespec>(2)
    times[0].tv_sec = access.epochSeconds.convert()
    times[0].tv_nsec = access.nanosecondsOfSecond.convert()
    times[1].tv_sec = modified.epochSeconds.convert()
    times[1].tv_nsec = modified.nanosecondsOfSecond.convert()
    checkUnixError(path, utimensat(IO_AT_FDCWD, name, times, if (followLinks) 0 else IO_AT_SYMLINK_NOFOLLOW))
}

internal actual fun nativeHomeDirectory(): String? =
    unixEnvironmentPath("HOME")
    ?: getpwuid(getuid())?.pointed?.pw_dir?.toKString()

internal actual fun nativeUserDirectory(kind: String): String? = try {
    parseXdgConfigFile(kind)
} catch (_: NoSuchFileException) { null }

internal actual fun nativeTemporaryDirectory(): String =
    unixEnvironmentPath("TMPDIR") ?: "/tmp"

internal actual fun nativeMimeType(fs: SyncKnUnixFileSystem, path: Path): String? =
    linuxMimeGlobs.find(path.name)

internal actual fun nativeMounts(): List<KnUnixFileStore> {
    val text = readText("/proc/self/mounts") ?: readText("/etc/mtab")
        ?: throw FileSystemException(null, "Cannot read filesystem mounts")

    return text.lineSequence().mapNotNull { line ->
        val fields = line.split(Regex("\\s+"))
        if (fields.size < 3) null
        else KnUnixFileStore(
            decodeMountField(fields[0]),
            fields[2],
            decodeMountField(fields[1])
        )
    }.toList()
}

// Not used
internal actual fun nativeFileMount(path: Path, name: String): KnUnixFileStore? = null

internal actual fun nativeSpace(path: Path, name: String): UnixSpace = memScoped {
    val info = alloc<statvfs>()
    checkUnixError(path, statvfs(name, info.ptr))
    UnixSpace(
        info.f_flag and ST_RDONLY.toULong() != 0uL,
        (info.f_blocks * info.f_frsize).toLong(),
        (info.f_bavail * info.f_frsize).toLong(),
        (info.f_bfree * info.f_frsize).toLong()
    )
}
