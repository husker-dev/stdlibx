package com.huskerdev.stdlibx.io

import com.huskerdev.stdlibx.io.fs.Path

internal actual fun suitableResourceForPath(path: String): Resource =
    Path(path)
