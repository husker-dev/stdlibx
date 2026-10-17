package com.huskerdev.stdlibx.io

import com.huskerdev.stdlibx.io.android.AndroidContentPathImpl
import com.huskerdev.stdlibx.io.fs.FileSystem

internal actual fun suitableResourceForPath(path: String): Resource {
    return if(path.startsWith("content://", ignoreCase = true))
        AndroidContentPathImpl(path)
    else FileSystem.default!!.path(path)
}
