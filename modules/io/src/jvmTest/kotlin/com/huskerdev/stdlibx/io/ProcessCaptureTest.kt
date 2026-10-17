package com.huskerdev.stdlibx.io

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class ProcessCaptureTest {
    @Test
    fun interruptingCaptureStopsTheChildAndUnblocksReaders() {
        if (java.io.File.separatorChar == '\\') return
        val pidFile = Files.createTempDirectory("stdlibx-cancel-capture-").resolve("pid")
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                Process.exec(
                    arrayOf(
                        "/bin/sh",
                        "-c",
                        "echo \$\$ > \"\$1\"; exec sleep 60",
                        "test",
                        pidFile.toString(),
                    ),
                    silent = true,
                )
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        var pid: Long? = null
        try {
            worker.start()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                if (Files.exists(pidFile)) pid = Files.readString(pidFile).trim().toLongOrNull()
                if (pid != null) break
                Thread.sleep(10)
            }
            assertNotNull(pid, "Child did not start")
            worker.interrupt()
            worker.join(5000)
            assertFalse(
                worker.isAlive,
                "Capture must not wait for blocked readers after interruption",
            )
            assertIs<InterruptedException>(failure.get())
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally {
            pid?.let { ProcessHandle.of(it).ifPresent { child -> child.destroyForcibly() } }
            worker.interrupt()
            worker.join(5000)
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(pidFile.parent)
        }
    }

    @Test
    fun mirrorsToCorrectStandardChannelsUnlessSilent() {
        if (java.io.File.separatorChar == '\\') return
        val originalOut = System.out
        val originalErr = System.err
        try {
            for (silent in listOf(false, true)) for (merge in listOf(false, true)) {
                val out = ByteArrayOutputStream()
                val err = ByteArrayOutputStream()
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(err))
                val result =
                    Process.exec(
                        arrayOf("/bin/sh", "-c", "printf '😀out'; printf '世界err' >&2"),
                        silent = silent,
                        errAsStd = merge,
                    )
                assertEquals(0, result.exitCode)
                if (merge) {
                    assertTrue(result.stdOut == "😀out世界err" || result.stdOut == "世界err😀out")
                    assertEquals("", result.stdErr)
                } else {
                    assertEquals("😀out", result.stdOut)
                    assertEquals("世界err", result.stdErr)
                }
                assertEquals(if (silent) "" else result.stdOut, out.toByteArray().decodeToString())
                assertEquals(if (silent) "" else result.stdErr, err.toByteArray().decodeToString())
            }
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    @Test
    fun mirrorsShortOutputBeforeChildExitWithoutWaitingForBufferToFill() {
        if (java.io.File.separatorChar == '\\') return
        val marker = Files.createTempDirectory("stdlibx-live-output-").resolve("ready")
        val originalOut = System.out
        val captured = ByteArrayOutputStream()
        try {
            System.setOut(
                object : PrintStream(captured) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        super.write(bytes, offset, length)
                        Files.write(marker, byteArrayOf())
                    }
                }
            )
            val result =
                Process.exec(
                    arrayOf(
                        "/bin/sh",
                        "-c",
                        "printf ready; i=0; while [ ! -f \"\$1\" ]; do i=\$((i+1)); [ \"\$i\" -lt 100 ] || exit 9; sleep 0.02; done; printf done",
                        "test",
                        marker.toString(),
                    ),
                    silent = false,
                )
            assertEquals(0, result.exitCode)
            assertEquals("readydone", result.stdOut)
            assertEquals(result.stdOut, captured.toByteArray().decodeToString())
        } finally {
            System.setOut(originalOut)
            Files.deleteIfExists(marker)
            Files.deleteIfExists(marker.parent)
        }
    }

    @Test
    fun mirrorFailureStopsChildAndPropagatesTheFailure() {
        if (java.io.File.separatorChar == '\\') return
        val originalOut = System.out
        val failure = IllegalStateException("Broken mirror")
        try {
            System.setOut(
                object : PrintStream(ByteArrayOutputStream()) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        throw failure
                    }
                }
            )
            val thrown =
                assertFailsWith<IllegalStateException> {
                    Process.exec(
                        arrayOf("/bin/sh", "-c", "printf ready; exec sleep 60"),
                        silent = false,
                    )
                }
            assertEquals(failure.message, thrown.message)
        } finally {
            System.setOut(originalOut)
        }
    }
}
