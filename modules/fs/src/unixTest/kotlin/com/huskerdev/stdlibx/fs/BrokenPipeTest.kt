@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import kotlinx.cinterop.*
import platform.posix.close
import platform.posix.pipe
import kotlin.test.*

class BrokenPipeTest {
    @Test
    fun brokenOutputPipeThrowsFileSystemExceptionInsteadOfSigpipe() = memScoped {
        val fds = allocArray<IntVar>(2)
        assertEquals(0, pipe(fds))
        close(fds[0])
        try {
            val path = checkNotNull(FileSystem.default).path("broken-pipe")
            val error = assertFailsWith<FileSystemException> {
                writeUnixFully(path, fds[1], byteArrayOf(1))
            }
            assertSame(path, error.path)
        } finally {
            close(fds[1])
        }
    }
}
