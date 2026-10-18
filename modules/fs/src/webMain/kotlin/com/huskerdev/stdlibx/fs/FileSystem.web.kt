package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.*
import com.huskerdev.stdlibx.fs.indexeddb.*
import com.huskerdev.stdlibx.fs.nodejs.*
import com.huskerdev.stdlibx.fs.opfs.*

internal actual val currentUserFileSystem: UserFileSystem? = when(EnvironmentKind.current) {
    EnvironmentKind.WEB -> when {
        isOpfsSupported() -> OpfsFileSystem()
        isIndexedDbSupported() -> IndexedDbFileSystem()
        else -> null
    }

    EnvironmentKind.NODEJS -> when(OS.current) {
        OS.WINDOWS -> SyncWindowsNodeJsFileSystem()
        else -> SyncUnixNodeJsFileSystem()
    }
    else -> throw UnsupportedOperationException()
}