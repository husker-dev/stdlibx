package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.io.IOException
import com.huskerdev.stdlibx.io.SecurityException
import kotlin.test.*

class FileSystemExceptionsTest {
    private val fs = LexicalFileSystem()

    @Test
    fun diagnosticsKeepBothPathsAndReasonSeparateFromMessage() {
        val source = fs.path("/source")
        val target = fs.path("/target")
        val cause = IllegalStateException("Platform detail")
        val error = FileSystemException(source, target, "Cannot move", cause)
        assertSame(source, error.path)
        assertSame(target, error.otherPath)
        assertEquals("Cannot move", error.reason)
        assertEquals("/source -> /target: Cannot move", error.message)
        assertSame(cause, error.cause)
        assertEquals("Reason only", FileSystemException(null, reason = "Reason only").message)
        assertEquals("/source", FileSystemException(source).message)
        assertNull(FileSystemException(null).message)
    }

    @Test
    fun linkCycleAndAtomicMoveErrorsBelongToIoHierarchy() {
        val source = fs.path("/source")
        val target = fs.path("/target")
        val errors =
            listOf(
                NotLinkException(source),
                FileSystemLoopException(source),
                AtomicMoveNotSupportedException(source, target),
            )
        for (error in errors) assertIs<IOException>(error)
        assertEquals(target, errors.last().otherPath)
    }

    @Test
    fun securityPolicyErrorsAreNotFilePermissionFailures() {
        val cause = IllegalStateException("Policy")
        val error: Throwable = SecurityException("Forbidden", cause)
        assertIs<RuntimeException>(error)
        assertFalse(error is IOException)
        assertSame(cause, error.cause)
    }
}
