@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io

import com.huskerdev.stdlibx.io.fs.FileSystem
import com.huskerdev.stdlibx.io.fs.FileSystemException
import com.huskerdev.stdlibx.io.fs.writeUnixFully
import kotlin.test.*
import kotlinx.cinterop.*
import platform.posix.close
import platform.posix.pipe

class StandardStreamsUnixTest {
    @Test
    fun brokenOutputPipeThrowsIOExceptionInsteadOfSigpipe() = memScoped {
        val fds = allocArray<IntVar>(2)
        assertEquals(0, pipe(fds))
        close(fds[0])
        try {
            val byte = alloc<ByteVar>()
            byte.value = 42
            val error =
                assertFailsWith<IOException> { writeStandardStreamBytes(fds[1], byte.ptr, 1) }
            assertFalse(error is ProcessException)
            val path = checkNotNull(FileSystem.default).path("broken-pipe")
            val fileError =
                assertFailsWith<FileSystemException> {
                    writeUnixFully(path, fds[1], byteArrayOf(1))
                }
            assertSame(path, fileError.path)
        } finally {
            close(fds[1])
        }
    }
}
