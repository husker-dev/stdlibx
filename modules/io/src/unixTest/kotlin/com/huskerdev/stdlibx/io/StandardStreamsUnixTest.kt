@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io

import kotlin.test.*
import kotlinx.cinterop.*
import platform.posix.close
import platform.posix.pipe

class StandardStreamsUnixTest {
    @Test
    fun brokenOutputPipeThrowsIOExceptionInsteadOfSigpipe(): Unit = memScoped {
        val fds = allocArray<IntVar>(2)
        assertEquals(0, pipe(fds))
        close(fds[0])
        try {
            val byte = alloc<ByteVar>()
            byte.value = 42
            assertFailsWith<IOException> { writeStandardStreamBytes(fds[1], byte.ptr, 1) }
        } finally {
            close(fds[1])
        }
    }
}
