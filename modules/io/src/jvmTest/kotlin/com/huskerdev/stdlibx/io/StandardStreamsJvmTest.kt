package com.huskerdev.stdlibx.io

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class StandardStreamsJvmTest {
    @Test
    fun printStreamFailuresUseIOException() {
        val original = System.out
        try {
            System.setOut(
                PrintStream(
                    object : java.io.OutputStream() {
                        override fun write(value: Int) {
                            throw java.io.IOException("Destination failed")
                        }
                    }
                )
            )
            assertFailsWith<IOException> { stdout.write(1) }
            assertFailsWith<IOException> { stdout.flush() }
        } finally {
            System.setOut(original)
        }
    }

    @Test
    fun outputUsesCurrentSystemStreamsAndDoesNotCloseThem(): Unit = runBlocking {
        val originalOut = System.out
        val originalErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        var closes = 0
        var flushes = 0
        val replacement =
            object : PrintStream(out) {
                override fun close() {
                    closes++
                    super.close()
                }

                override fun flush() {
                    flushes++
                    super.flush()
                }
            }
        try {
            System.setOut(replacement)
            System.setErr(PrintStream(err))
            val bytes = byteArrayOf(0, -1, 10, 42)
            stdout.write(bytes)
            stdout.close()
            stdout.asAsync().write(7)
            stdout.asAsync().close()
            stderr.asAsync().write(bytes)
            stderr.close()
            assertContentEquals(bytes + byteArrayOf(7), out.toByteArray())
            assertContentEquals(bytes, err.toByteArray())
            assertEquals(0, closes)
            assertTrue(flushes >= 2)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
