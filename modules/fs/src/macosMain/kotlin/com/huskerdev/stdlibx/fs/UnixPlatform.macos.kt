@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.osx.MNT_NOWAIT
import platform.osx.MNT_RDONLY
import platform.osx.getmntinfo_r_np
import platform.osx.statfs
import platform.posix.*
import kotlin.time.Instant

internal actual fun clearUnixErrno() {
    __error()!!.pointed.value = 0
}

internal actual fun stat(path: Path, followLinks: Boolean): UnixStat = memScoped {
    val name = path.absolute().pathString
    val info = alloc<stat>()
    checkUnixError(path, if (followLinks) stat(name, info.ptr) else lstat(name, info.ptr))
    UnixStat(
        info.st_dev.toULong(),
        info.st_ino,
        info.st_mode.toInt(),
        info.st_size,
        accessTime = Instant.fromEpochSeconds(
            epochSeconds = info.st_atimespec.tv_sec,
            nanosecondAdjustment = info.st_atimespec.tv_nsec
        ),
        modifiedTime = Instant.fromEpochSeconds(
            epochSeconds = info.st_mtimespec.tv_sec,
            nanosecondAdjustment = info.st_mtimespec.tv_nsec
        ),
        birthTime = Instant.fromEpochSeconds(
            epochSeconds = info.st_birthtimespec.tv_sec,
            nanosecondAdjustment = info.st_birthtimespec.tv_nsec
        )
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
    checkUnixError(path, utimensat(AT_FDCWD, name, times, if (followLinks) 0 else AT_SYMLINK_NOFOLLOW))
}

internal actual fun nativeHomeDirectory(): String? =
    NSHomeDirectory()

internal actual fun nativeUserDirectory(kind: String): String? {
    return NSSearchPathForDirectoriesInDomains(
        when (kind) {
            "DOCUMENTS" -> NSDocumentDirectory
            "DOWNLOAD" -> NSDownloadsDirectory
            "PICTURES" -> NSPicturesDirectory
            "VIDEOS" -> NSMoviesDirectory
            "MUSIC" -> NSMusicDirectory
            "DESKTOP" -> NSDesktopDirectory
            else -> return null
        },
        NSUserDomainMask,
        true
    ).firstOrNull() as? String?
}

internal actual fun nativeTemporaryDirectory(): String = NSTemporaryDirectory()

internal actual fun nativeMimeType(fs: SyncKnUnixFileSystem, path: Path): String? {
    val url = NSURL.fileURLWithPath(fs.absolute(path).pathString)
    return url.mimeTypeFromMetadata()
        ?: url.mimeTypeFromExtension()
}

internal actual fun nativeMounts(): List<KnUnixFileStore> = memScoped {
    val mounts = alloc<CPointerVar<statfs>>()
    mounts.value = null
    val count = getmntinfo_r_np(mounts.ptr, MNT_NOWAIT)
    val entries = mounts.value
    try {
        if (count <= 0 || entries == null)
            throw FileSystemException(null, "Cannot read filesystem mounts")
        List(count) { index ->
            entries[index].toFileStore()
        }
    } finally { free(entries) }
}

private fun statfs.toFileStore(): KnUnixFileStore = KnUnixFileStore(
    f_mntfromname.toKString(),
    f_fstypename.toKString(),
    f_mntonname.toKString()
)

private inline fun <T> withNativeSpace(path: Path, name: String, block: (statfs) -> T): T = memScoped {
    val info = alloc<statfs>()
    checkUnixError(path, statfs(name, info.ptr))
    block(info)
}

internal actual fun nativeFileMount(path: Path, name: String): KnUnixFileStore? =
    withNativeSpace(path, name) { it.toFileStore() }

internal actual fun nativeSpace(path: Path, name: String): UnixSpace = withNativeSpace(path, name) { info ->
    UnixSpace(
        info.f_flags and MNT_RDONLY.toUInt() != 0u,
        (info.f_blocks * info.f_bsize.toULong()).toLong(),
        (info.f_bavail * info.f_bsize.toULong()).toLong(),
        (info.f_bfree * info.f_bsize.toULong()).toLong()
    )
}
