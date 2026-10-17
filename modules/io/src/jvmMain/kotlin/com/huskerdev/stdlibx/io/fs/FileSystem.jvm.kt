package com.huskerdev.stdlibx.io.fs

import com.huskerdev.stdlibx.OS
import com.huskerdev.stdlibx.io.fs.unix.*
import com.huskerdev.stdlibx.io.fs.windows.*
import java.nio.file.FileSystem
import java.nio.file.FileSystems

internal actual val currentUserFileSystem: UserFileSystem? =
    if(OS.isWindows) SyncNioUserWindowsFileSystem()
    else SyncNioUserUnixFileSystem()

// Common

interface NioUserFileSystem: NioFileSystem, UserFileSystem {
    override val nioFileSystem: FileSystem
        get() = FileSystems.getDefault()

    override fun asSync(): SyncNioUserFileSystem?
    override fun asAsync(): AsyncNioUserFileSystem
}

interface SyncNioUserFileSystem: NioUserFileSystem, SyncNioFileSystem, SyncUserFileSystem {

    override fun workingDir(): SyncPath =
        path(nioFileSystem.getPath(".").toAbsolutePath().toString())

    override fun homeDir(): SyncPath? =
        System.getProperty("user.home")?.let { path(it) }

    override fun tmpDir(): SyncPath =
        path(System.getProperty("java.io.tmpdir"))

    override fun documentsDir(): SyncPath? =
        userDirOrNull(USER_DIR_DOCUMENTS)

    override fun downloadsDir(): SyncPath? =
        userDirOrNull(USER_DIR_DOWNLOADS)

    override fun picturesDir(): SyncPath? =
        userDirOrNull(USER_DIR_PICTURES)

    override fun videosDir(): SyncPath? =
        userDirOrNull(USER_DIR_VIDEOS)

    override fun musicDir(): SyncPath? =
        userDirOrNull(USER_DIR_MUSIC)

    override fun desktopDir(): SyncPath? =
        userDirOrNull(USER_DIR_DESKTOP)

    override fun absolute(path: Path): SyncPath = super<SyncNioFileSystem>.absolute(path)

    override fun asSync(): SyncNioUserFileSystem?
    override fun asAsync(): AsyncNioUserFileSystem
}

interface AsyncNioUserFileSystem:
    NioUserFileSystem,
    AsyncNioFileSystem,
    WrappedAsyncFileSystem,
    WrappedAsyncUserFileSystem
{
    override fun absolute(path: Path): AsyncPath = super<WrappedAsyncFileSystem>.absolute(path)

    override fun asSync(): SyncNioUserFileSystem?
    override fun asAsync(): AsyncNioUserFileSystem
}

// Unix

interface NioUserUnixFileSystem: NioUserFileSystem, UnixFileSystem

class SyncNioUserUnixFileSystem: NioUserUnixFileSystem, SyncNioUserFileSystem, SyncUnixFileSystem {
    val async by lazy { AsyncNioUserUnixFileSystem(this) }
    override fun asSync(): SyncNioUserFileSystem = this
    override fun asAsync(): AsyncNioUserFileSystem = async
}

class AsyncNioUserUnixFileSystem(
    override val wrapped: SyncNioUserUnixFileSystem
): NioUserUnixFileSystem, AsyncNioUserFileSystem, AsyncUnixFileSystem {
    override fun asSync(): SyncNioUserFileSystem = wrapped
    override fun asAsync(): AsyncNioUserFileSystem = this
}

// Windows

interface NioUserWindowsFileSystem: NioUserFileSystem, WindowsFileSystem

class SyncNioUserWindowsFileSystem: NioUserWindowsFileSystem, SyncNioUserFileSystem, SyncWindowsFileSystem {
    val async by lazy { AsyncNioUserWindowsFileSystem(this) }
    override fun asSync(): SyncNioUserWindowsFileSystem = this
    override fun asAsync(): AsyncNioUserWindowsFileSystem = async
}

class AsyncNioUserWindowsFileSystem(
    override val wrapped: SyncNioUserWindowsFileSystem
): NioUserWindowsFileSystem, AsyncNioUserFileSystem, AsyncWindowsFileSystem {
    override fun asSync(): SyncNioUserWindowsFileSystem = wrapped
    override fun asAsync(): AsyncNioUserWindowsFileSystem = this
}
