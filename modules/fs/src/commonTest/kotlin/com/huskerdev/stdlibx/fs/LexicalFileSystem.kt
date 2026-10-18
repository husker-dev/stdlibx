package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.fs.unix.UnixFileSystem
import com.huskerdev.stdlibx.fs.unix.UnixPath

/** No default provider, browser storage, or host OS is needed for lexical tests. */
internal class LexicalFileSystem(override val identity: Any = Any()) : UnixFileSystem {
    override val separator: String = "/"

    override fun path(vararg path: String): UnixPath = UnixPath(path.joinToString(separator), this)

    override fun absolute(path: Path): Path = error("Lexical fixture has no working directory")

    override fun asSync(): SyncFileSystem? = null

    override fun asAsync(): AsyncFileSystem = error("Lexical fixture cannot perform IO")
}
