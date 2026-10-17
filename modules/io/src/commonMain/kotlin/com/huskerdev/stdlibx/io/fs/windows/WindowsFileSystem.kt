package com.huskerdev.stdlibx.io.fs.windows

import com.huskerdev.stdlibx.io.fs.*


/** A file system that constructs Windows paths, including drive and network-share paths. */
interface WindowsFileSystem: FileSystem {
    override fun path(vararg path: String): WindowsPath =
        WindowsPath(path.joinToString("\\"), this)
}

/** A suspending file system using Windows path syntax. */
interface AsyncWindowsFileSystem: WindowsFileSystem, AsyncFileSystem {
    override fun path(vararg path: String): AsyncWindowsPath =
        AsyncWindowsPath(path.joinToString("\\"), this)
}

/** A blocking file system using Windows path syntax. */
interface SyncWindowsFileSystem: WindowsFileSystem, SyncFileSystem {
    override fun path(vararg path: String): SyncWindowsPath =
        SyncWindowsPath(path.joinToString("\\"), this)
}
