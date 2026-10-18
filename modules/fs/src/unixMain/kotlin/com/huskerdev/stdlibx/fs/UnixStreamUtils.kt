@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import kotlinx.cinterop.*

internal expect fun writeStandardStreamBytes(fd: Int, bytes: CPointer<ByteVar>, length: Int): Int
