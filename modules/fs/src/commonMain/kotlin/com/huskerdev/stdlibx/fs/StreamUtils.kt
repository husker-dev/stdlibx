package com.huskerdev.stdlibx.fs

import kotlinx.coroutines.CoroutineDispatcher

internal expect val asyncDispatcher: CoroutineDispatcher

internal fun checkBounds(bytes: ByteArray, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > bytes.size || length > bytes.size - offset)
        throw IndexOutOfBoundsException()
}
