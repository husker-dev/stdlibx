package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.fs.unix.SyncUnixFileSystem
import com.huskerdev.stdlibx.fs.unix.SyncUnixPath

/** Separate lexical filesystem identity for ownership checks without opening another provider. */
internal fun foreignUnixFileSystem(): SyncUnixFileSystem =
    object : SyncUnixFileSystem, SyncFileSystem by checkNotNull(FileSystem.default?.asSync()) {
        override val identity: Any = Any()

        override fun path(vararg path: String) = SyncUnixPath(path.joinToString("/"), this)

        override fun assertFS(path: Path) {
            require(path.fileSystem.identity === identity) {
                "Path belongs to a different file system"
            }
        }
    }
