package com.huskerdev.stdlibx.io.fs

import com.huskerdev.stdlibx.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.*

class NioErrorsTest {
    private val path = checkNotNull(FileSystem.default).path("stdlibx-error-contract")

    @Test
    fun oversizedFilesFailBeforeAllocatingAnArray() {
        val file = java.nio.file.Files.createTempFile("stdlibx-large-file-", ".bin")
        try {
            java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE).use {
                channel ->
                channel.position(Int.MAX_VALUE.toLong())
                channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(1)))
            }
            val target = checkNotNull(FileSystem.default).asSync()!!.path(file.toString())
            assertFailsWith<FileSystemException> { target.readBytes() }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun platformErrorsUsePortableTypesAndPreserveCauses() {
        val failures =
            listOf(
                java.nio.file.NoSuchFileException(path.pathString) to NoSuchFileException::class,
                java.nio.file.FileAlreadyExistsException(path.pathString) to
                    FileAlreadyExistsException::class,
                java.nio.file.DirectoryNotEmptyException(path.pathString) to
                    DirectoryNotEmptyException::class,
                java.nio.file.NotDirectoryException(path.pathString) to FileSystemException::class,
                java.nio.file.AccessDeniedException(path.pathString) to
                    AccessDeniedException::class,
                java.io.IOException("Device failed") to FileSystemException::class,
                java.nio.file.NotLinkException(path.pathString) to NotLinkException::class,
                java.nio.file.FileSystemLoopException(path.pathString) to
                    FileSystemLoopException::class,
                java.nio.file.AtomicMoveNotSupportedException(
                    path.pathString,
                    null,
                    "Cross-device",
                ) to AtomicMoveNotSupportedException::class,
            )
        for ((original, expected) in failures) {
            val error = assertFailsWith<IOException> { nioCall(path) { throw original } }
            assertEquals(expected, error::class)
            assertSame(original, error.cause)
            assertEquals(path, (error as FileSystemException).path)
        }
    }

    @Test
    fun argumentStateCancellationAndExistingErrorsAreNotRemapped() {
        for (original in
            listOf(
                IllegalArgumentException("Argument"),
                IllegalStateException("State"),
                UnsupportedOperationException("Unsupported"),
                SecurityException("Denied by policy"),
                CancellationException("Cancelled"),
                FileSystemException(path, "Already mapped"),
            )) {
            assertSame(original, assertFails { nioCall(path) { throw original } })
        }
    }

    @Test
    fun nioDiagnosticsPreserveBothPathsAndReason() {
        val target = path.resolveSibling("stdlibx-target")
        val original =
            java.nio.file.AtomicMoveNotSupportedException(
                path.pathString,
                target.pathString,
                "Cross-device",
            )
        val failure =
            assertFailsWith<AtomicMoveNotSupportedException> { nioCall(path) { throw original } }
        assertEquals(path, failure.path)
        assertEquals(target, failure.otherPath)
        assertEquals("Cross-device", failure.reason)
        assertEquals(original.message, failure.message)
        assertSame(original, failure.cause)
    }

    @Test
    fun existenceProbesDelegateToNioWithoutReclassifyingUnknownStatus() {
        val directory = java.nio.file.Files.createTempDirectory("stdlibx-nio-probes-")
        try {
            val file = java.nio.file.Files.write(directory.resolve("file"), byteArrayOf(1))
            val nio = file.resolve("child")
            val wrapped = checkNotNull(FileSystem.default).asSync()!!.path(nio.toString())
            assertEquals(java.nio.file.Files.exists(nio), wrapped.exists())
            assertEquals(java.nio.file.Files.notExists(nio), wrapped.notExist())
        } finally {
            java.nio.file.Files.deleteIfExists(directory.resolve("file"))
            java.nio.file.Files.deleteIfExists(directory)
        }
    }
}
