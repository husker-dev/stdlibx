package com.huskerdev.stdlibx.fs.unix

import com.huskerdev.stdlibx.fs.*

/** A file system that constructs paths using Unix syntax and forward-slash separators. */
interface UnixFileSystem: FileSystem {
    override fun path(vararg path: String): UnixPath =
        UnixPath(path.joinToString("/"), this)
}

/** A blocking file system using Unix path syntax. */
interface SyncUnixFileSystem: SyncFileSystem, UnixFileSystem {
    override fun path(vararg path: String): SyncUnixPath =
        SyncUnixPath(path.joinToString("/"), this)
}

/** A suspending file system using Unix path syntax. */
interface AsyncUnixFileSystem: AsyncFileSystem, UnixFileSystem {
    override fun path(vararg path: String): AsyncUnixPath =
        AsyncUnixPath(path.joinToString("/"), this)
}
