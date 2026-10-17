@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.io

import kotlin.test.*

private fun isNodeStreamsTest(): Boolean =
    js("typeof process !== 'undefined' && !!process.versions && !!process.versions.node")

private external interface ConsoleCapture : JsAny {
    fun result(): String

    fun restore()
}

private fun captureConsole(): ConsoleCapture =
    js(
        """(() => {
    const log = console.log, error = console.error, messages = [];
    console.log = text => messages.push('out:' + text);
    console.error = text => messages.push('err:' + text);
    return { result: () => messages.join('|'), restore: () => { console.log = log; console.error = error; } };
})()"""
    )

private fun captureNodeOutput(): ConsoleCapture =
    js(
        """(() => {
    const fs = process.getBuiltinModule('fs'), write = fs.writeSync, close = fs.closeSync, messages = [];
    fs.writeSync = (fd, bytes, offset, length, position) => {
        if (fd !== 1 && fd !== 2) return write(fd, bytes, offset, length, position);
        const count = Math.min(2, length);
        messages.push(fd + ':' + bytes.subarray(offset, offset + count).toString('hex'));
        return count;
    };
    fs.closeSync = fd => {
        if (fd === 1 || fd === 2) throw new Error('Closed standard output');
        return close(fd);
    };
    return { result: () => messages.join('|'), restore: () => { fs.writeSync = write; fs.closeSync = close; } };
})()"""
    )

private fun failNodeOutput(name: String = "Error", code: String = "EIO"): ConsoleCapture =
    js(
        """(() => {
    const fs = process.getBuiltinModule('fs'), write = fs.writeSync;
    fs.writeSync = (fd, ...args) => {
        if (fd !== 1) return write(fd, ...args);
        const error = new Error('Destination failed');
        error.name = name;
        error.code = code;
        throw error;
    };
    return { result: () => '', restore: () => { fs.writeSync = write; } };
})()"""
    )

class StandardStreamsWebTest {
    @Test
    fun nodePolicyFailuresUseSecurityException() {
        if (!isNodeStreamsTest()) return
        for ((name, code) in listOf("SecurityError" to "EIO", "Error" to "ERR_ACCESS_DENIED")) {
            val capture = failNodeOutput(name, code)
            try {
                val error = assertFailsWith<SecurityException> { stdout.write(1) }
                assertNotNull(error.cause)
            } finally {
                capture.restore()
            }
        }
    }

    @Test
    fun nodeIoFailuresUseIOException() {
        if (!isNodeStreamsTest()) return
        val capture = failNodeOutput()
        try {
            val error = assertFailsWith<IOException> { stdout.write(1) }
            assertNotNull(error.cause)
        } finally {
            capture.restore()
        }
    }

    @Test
    fun nodeOutputPreservesBytesHandlesPartialWritesAndDoesNotCloseDescriptors() {
        if (!isNodeStreamsTest()) return
        val capture = captureNodeOutput()
        try {
            stdout.write(byteArrayOf(0, -1, 10))
            stdout.close()
            stdout.write(42)
            stderr.write(byteArrayOf(-128, 0))
            stderr.close()
            assertEquals("1:00ff|1:0a|1:2a|2:8000", capture.result())
        } finally {
            capture.restore()
        }
    }

    @Test
    fun browserInputIsEofAndConsoleBuffersUtf8UntilNewlineOrFlush() {
        if (isNodeStreamsTest()) return
        assertFalse(stdin.hasNext())
        stdin.close()
        assertEquals(0, stdin.read(ByteArray(1)))
        val capture = captureConsole()
        try {
            val bytes = "😀\npartial".encodeToByteArray()
            stdout.write(bytes, 0, 2)
            assertEquals("", capture.result())
            stdout.write(bytes, 2, bytes.size - 2)
            assertEquals("out:😀", capture.result())
            stdout.close()
            stderr.write("error\n".encodeToByteArray())
            stdout.write("again\n".encodeToByteArray())
            assertEquals("out:😀|out:partial|err:error|out:again", capture.result())
        } finally {
            capture.restore()
        }
    }
}
