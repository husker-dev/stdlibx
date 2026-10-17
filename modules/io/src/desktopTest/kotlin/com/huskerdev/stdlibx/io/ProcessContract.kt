package com.huskerdev.stdlibx.io

import com.huskerdev.stdlibx.io.fs.SyncPath
import kotlin.test.*

internal class ProcessContract(private val directory: SyncPath, private val windows: Boolean) {
    private fun command(unix: String, windowsCommand: String): Array<String> =
        if (windows) arrayOf("cmd.exe", "/d", "/c", windowsCommand)
        else arrayOf("/bin/sh", "-c", unix)

    fun brokenStdinHasPortableException() {
        Process.exec(command("exit 0", "exit /b 0"), directory).use { process ->
            assertEquals(0, process.waitFor())
            assertFailsWith<ProcessException> { process.stdin.write(ByteArray(32)) }
            // Some JVM pipe streams also report the failed write while flushing on close.
            try {
                process.stdin.close()
            } catch (error: IOException) {
                assertIs<ProcessException>(error)
            }
        }
    }

    fun unicodeExecutableAndWorkingDirectoryPaths() {
        val root = directory.resolve("каталог с пробелами 世界 😀").createDirectory()
        val workingDirectory = root.resolve("рабочая папка 漢字 🧪").createDirectory()
        val executableName = if (windows) "программа 工具 🚀.exe" else "программа 工具 🚀"
        val executable = root.resolve(executableName)
        val source =
            if (windows) {
                val systemRoot = checkNotNull(com.huskerdev.stdlibx.getenv("SystemRoot"))
                directory.fileSystem.path(systemRoot).resolve("System32").resolve("cmd.exe")
            } else directory.fileSystem.path("/bin/sh")
        // macOS may reject relocated system executables; a link exercises the Unicode launch path.
        if (windows) source.copyTo(executable) else executable.createSymbolicLink(source)
        workingDirectory.resolve("marker.txt").writeString("present")
        val relativeExecutable = executable.relativeTo(workingDirectory).pathString
        for (path in listOf(executable.pathString, relativeExecutable)) {
            val arguments =
                if (windows) {
                    arrayOf(
                        path,
                        "/d",
                        "/c",
                        "if exist marker.txt (echo unicode-path-ok) else (exit /b 9)",
                    )
                } else arrayOf(path, "-c", "test -f marker.txt && printf unicode-path-ok")
            val result = Process.exec(arguments, workingDirectory, silent = true)
            assertEquals(0, result.exitCode, "Executable: $path")
            assertEquals("unicode-path-ok", result.stdOut.trimEnd())
            assertEquals("", result.stdErr)
        }
    }

    fun capturingExecSeparatesOrMergesOutput() {
        val arguments =
            command(
                "printf out; printf err >&2; exit 7",
                "<nul set /p =out&<nul set /p =err>&2&exit /b 7",
            )
        val original = arguments.copyOf()
        val separate = Process.exec(arguments, directory, silent = true)
        assertEquals(7, separate.exitCode)
        assertEquals("out", separate.stdOut)
        assertEquals("err", separate.stdErr)
        val merged = Process.exec(arguments, directory, silent = true, errAsStd = true)
        assertEquals(7, merged.exitCode)
        assertEquals("", merged.stdErr)
        assertTrue(merged.stdOut == "outerr" || merged.stdOut == "errout")
        assertContentEquals(original, arguments)
    }

    fun capturingExecSendsEofAndPreservesLaunchOptions() {
        val environment = mutableMapOf("STDLIBX_PROCESS_TEST" to "capture-value")
        if (windows)
            com.huskerdev.stdlibx.getenv("SystemRoot")?.let { environment["SystemRoot"] = it }
        val args =
            command(
                "cat; printf '%s|' \"\$STDLIBX_PROCESS_TEST\"; pwd -P",
                "set /p value=&<nul set /p =%STDLIBX_PROCESS_TEST%^|&cd",
            )
        val result = Process.exec(args, directory, environment, silent = true)
        assertEquals(0, result.exitCode)
        assertEquals("capture-value|${directory.realPath().pathString}", result.stdOut.trimEnd())
        assertEquals("", result.stdErr)
        val empty = Process.exec(command("exit 0", "exit /b 0"), directory, silent = true)
        assertEquals("", empty.stdOut)
        assertEquals("", empty.stdErr)
        assertEquals(0, empty.exitCode)
    }

    fun capturingExecDrainsBothFullPipesAndDecodesOnlyAfterEof() {
        if (windows) return
        val args =
            command(
                "i=0; while [ \"\$i\" -lt 10000 ]; do printf '😀out'; printf '世界err' >&2; i=\$((i+1)); done; printf tail; printf end >&2",
                "",
            )
        val result = Process.exec(args, directory, silent = true)
        assertEquals(0, result.exitCode)
        assertEquals("😀out".repeat(10000) + "tail", result.stdOut)
        assertEquals("世界err".repeat(10000) + "end", result.stdErr)
    }

    fun outputErrorAndExit() {
        val arguments =
            command(
                "printf out; printf err >&2; exit 7",
                "<nul set /p =out&<nul set /p =err>&2&exit /b 7",
            )
        val original = arguments.copyOf()
        Process.exec(arguments, directory).use { process ->
            assertContentEquals(original, arguments)
            assertTrue(process.pid > 0)
            process.stdin.close()
            assertEquals("out", process.stdout.readAllBytes().decodeToString())
            assertEquals("err", process.stderr.readAllBytes().decodeToString())
            assertEquals(7, process.waitFor())
            assertEquals(7, process.waitFor())
            assertEquals(7, process.exitCode)
            assertFalse(process.isAlive)
            process.kill()
            process.killForcibly()
        }
    }

    fun stdinRoundTrip() {
        val args =
            if (windows) arrayOf("cmd.exe", "/d", "/v:on", "/c", "set /p value=&echo !value!")
            else arrayOf("/bin/cat")
        Process.exec(args, directory).use { process ->
            val text = "hello-process"
            process.stdin.write("$text\n".encodeToByteArray())
            process.stdin.flush()
            process.stdin.close()
            process.stdin.close()
            assertEquals(text, process.stdout.readAllBytes().decodeToString().trimEnd())
            assertEquals(0, process.waitFor())
            assertFailsWith<IllegalStateException> { process.stdin.write(1) }
        }
    }

    fun repeatedWindowsTerminationIsIdempotent() {
        if (!windows) return
        repeat(30) { iteration ->
            Process.exec(command("", "<nul set /p =R&set /p STDLIBX_WAIT="), directory).use {
                process ->
                assertEquals('R'.code.toByte(), process.stdout.read())
                if (iteration % 2 == 0) process.kill() else process.killForcibly()
                repeat(4) {
                    process.kill()
                    process.killForcibly()
                }
                assertNotEquals(0, process.waitFor())
            }
        }
    }

    fun stdinWritesAreImmediateWithoutFlushOrClose() {
        val args =
            command(
                "read first; printf R; read second; printf D",
                "set /p first=&<nul set /p =R&set /p second=&<nul set /p =D&exit /b 0",
            )
        Process.exec(args, directory).use { process ->
            process.stdin.write("first\n".encodeToByteArray())
            assertEquals('R'.code.toByte(), process.stdout.read())
            process.stdin.write('\n'.code.toByte())
            assertEquals('D'.code.toByte(), process.stdout.read())
            process.stdin.close()
            assertEquals(0, process.waitFor())
        }
    }

    fun workingDirectoryAndEnvironment() {
        val args =
            command(
                "printf '%s' \"\$STDLIBX_PROCESS_TEST\"; printf '|' ; pwd -P",
                "<nul set /p =%STDLIBX_PROCESS_TEST%^|&cd",
            )
        // Preserve SystemRoot on Windows: some system utilities require it even with an explicit
        // executable.
        val env = mutableMapOf("STDLIBX_PROCESS_TEST" to "value with spaces")
        if (windows) com.huskerdev.stdlibx.getenv("SystemRoot")?.let { env["SystemRoot"] = it }
        Process.exec(args, directory, env).use { process ->
            process.stdin.close()
            val output = process.stdout.readAllBytes().decodeToString().trimEnd()
            assertEquals("value with spaces|${directory.realPath().pathString}", output)
            assertEquals(0, process.waitFor())
        }
        if (!windows)
            Process.exec(
                    arrayOf("/bin/sh", "-c", "test -z \"\$STDLIBX_PROCESS_TEST\""),
                    directory,
                    emptyMap(),
                )
                .use {
                    assertEquals(0, it.waitFor())
                }
    }

    fun invalidArgumentsAndStartupFailures() {
        assertFailsWith<IllegalArgumentException> { Process.exec(emptyArray()) }
        assertFailsWith<IllegalArgumentException> { Process.exec(arrayOf("")) }
        assertFailsWith<IllegalArgumentException> { Process.exec(arrayOf("bad\u0000argument")) }
        assertFailsWith<IllegalArgumentException> {
            Process.exec(arrayOf("anything"), environment = mapOf("a=b" to "c"))
        }
        assertFailsWith<IllegalArgumentException> {
            Process.exec(arrayOf("anything"), environment = mapOf("a" to "b\u0000c"))
        }
        assertFailsWith<ProcessException> {
            Process.exec(arrayOf("stdlibx-nonexistent-executable-58654"), directory)
        }
        assertFailsWith<ProcessException> {
            Process.exec(command("exit 0", "exit /b 0"), directory.resolve("missing"))
        }
    }

    fun closeKillsAndReleasesStreams() {
        val process = Process.exec(command("exec sleep 60", "set /p STDLIBX_WAIT="), directory)
        try {
            assertTrue(process.isAlive)
            process.killForcibly()
            assertNotEquals(0, process.waitFor())
        } finally {
            process.close()
        }
        process.close()
        assertFalse(process.isAlive)
        assertFailsWith<IllegalStateException> { process.stdout.read() }
        assertFailsWith<IllegalStateException> { process.stderr.hasNext() }
        assertFailsWith<IllegalStateException> { process.stdin.flush() }
        val live =
            Process.exec(
                command("trap '' TERM; printf R; while :; do :; done", "set /p STDLIBX_WAIT="),
                directory,
            )
        try {
            if (!windows) assertEquals('R'.code.toByte(), live.stdout.read())
        } finally {
            live.close()
        }
        assertFalse(live.isAlive)
    }

    fun killRequestsPlatformDefaultTermination() {
        // Readiness proves the Unix SIGTERM handler is installed before kill() is called.
        val args =
            command("trap 'exit 42' TERM; printf R; while :; do :; done", "set /p STDLIBX_WAIT=")
        Process.exec(args, directory).use { process ->
            if (!windows) assertEquals('R'.code.toByte(), process.stdout.read())
            process.kill()
            val exit = process.waitFor()
            if (windows) assertNotEquals(0, exit) else assertEquals(42, exit)
            process.kill()
            process.killForcibly()
        }
    }

    fun killForciblyBypassesUnixSignalHandlers() {
        if (windows)
            return // Windows forcible termination is covered by closeKillsAndReleasesStreams.
        Process.exec(
                arrayOf("/bin/sh", "-c", "trap '' TERM; printf R; while :; do :; done"),
                directory,
            )
            .use { process ->
                assertEquals('R'.code.toByte(), process.stdout.read())
                process.killForcibly()
                assertEquals(137, process.waitFor())
                process.killForcibly()
            }
    }

    fun bothKillMethodsCloseStreamsImmediately() {
        for (forcibly in listOf(false, true)) {
            val args =
                command("trap '' TERM; printf R; while :; do :; done", "set /p STDLIBX_WAIT=")
            Process.exec(args, directory).use { process ->
                if (!windows) assertEquals('R'.code.toByte(), process.stdout.read())
                if (forcibly) process.killForcibly() else process.kill()
                assertFailsWith<IllegalStateException> { process.stdin.write(ByteArray(0)) }
                assertFailsWith<IllegalStateException> { process.stdin.flush() }
                assertFailsWith<IllegalStateException> { process.stdout.read(ByteArray(0)) }
                assertFailsWith<IllegalStateException> { process.stderr.hasNext() }
                if (!forcibly && !windows)
                    assertTrue(process.isAlive) // Ignoring TERM does not keep streams open.
                process.killForcibly()
                assertNotEquals(0, process.waitFor())
            }
        }
    }

    fun argumentsRemainSeparateWithoutShellExpansion() {
        if (windows)
            return // CRT round-trip requires a helper executable; tested separately on Windows.
        val argument = "spaces 'quotes' \"double\" \\ trailing\\ \$HOME ; echo injected 😀"
        Process.exec(
                arrayOf(
                    "/bin/sh",
                    "-c",
                    "printf '%s' \"\$1\"; printf '|%s' \"\$2\"",
                    "test",
                    argument,
                    "",
                ),
                directory,
            )
            .use { process ->
                process.stdin.close()
                assertEquals("$argument|", process.stdout.readAllBytes().decodeToString())
                assertEquals(0, process.waitFor())
            }
    }

    fun binaryStreamsAndLookahead() {
        if (windows) return
        val bytes = ByteArray(1024 * 1024 + 1) { it.toByte() }
        // Redirect stdin to disk first: writing a megabyte then reading an echo pipe would
        // deadlock.
        val target = directory.resolve("binary")
        try {
            Process.exec(arrayOf("/bin/sh", "-c", "cat > binary"), directory).use { process ->
                process.stdin.write(bytes)
                process.stdin.close()
                assertEquals(0, process.waitFor())
            }
            Process.exec(arrayOf("/bin/cat", "binary"), directory).use { process ->
                process.stdin.close()
                assertTrue(process.stdout.hasNext())
                assertTrue(process.stdout.hasNext())
                assertEquals(bytes[0], process.stdout.read())
                assertContentEquals(bytes.copyOfRange(1, bytes.size), process.stdout.readAllBytes())
                assertFalse(process.stdout.hasNext())
                assertEquals(0, process.stdout.read(ByteArray(1)))
                assertFailsWith<NoSuchElementException> { process.stdout.read() }
                assertEquals(0, process.waitFor())
            }
        } finally {
            target.deleteIfExists()
        }
    }

    fun interactiveLookaheadDoesNotWaitForAnotherByte() {
        if (windows) return
        Process.exec(arrayOf("/bin/sh", "-c", "printf R; read line; printf X"), directory).use {
            process ->
            assertTrue(process.stdout.hasNext())
            val bytes = ByteArray(32)
            assertEquals(1, process.stdout.read(bytes))
            assertEquals('R'.code.toByte(), bytes[0])
            process.stdin.write("continue\n".encodeToByteArray())
            process.stdin.close()
            assertEquals("X", process.stdout.readAllBytes().decodeToString())
            assertEquals(0, process.waitFor())
        }
    }

    fun executableLookupUsesParentPathAndExplicitChildDirectory() {
        if (windows) return
        val executable = directory.resolve("stdlibx-child-path-test")
        try {
            executable.writeString("#!/bin/sh\nprintf path-ok\n")
            Process.exec(arrayOf("/bin/chmod", "u+x", executable.pathString), directory).use {
                assertEquals(0, it.waitFor())
            }
            assertFailsWith<ProcessException> {
                Process.exec(
                        arrayOf("stdlibx-child-path-test"),
                        directory,
                        mapOf("PATH" to directory.pathString),
                    )
                    .use { it.waitFor() }
            }
            for (command in listOf(executable.pathString, "./stdlibx-child-path-test")) {
                Process.exec(arrayOf(command), directory, mapOf("PATH" to ".")).use { process ->
                    process.stdin.close()
                    assertEquals("path-ok", process.stdout.readAllBytes().decodeToString())
                    assertEquals(0, process.waitFor())
                }
            }
        } finally {
            executable.deleteIfExists()
        }
    }

    fun childPathDoesNotOverrideParentExecutableSearch() {
        if (windows) {
            val result =
                Process.exec(
                    arrayOf("cmd.exe", "/d", "/c", "echo %PATH%"),
                    directory,
                    mapOf("PATH" to directory.pathString),
                    silent = true,
                )
            assertEquals(0, result.exitCode)
            assertEquals(directory.pathString, result.stdOut.trimEnd())
            return
        }
        val shadow = directory.resolve("sh")
        try {
            shadow.writeString("#!/bin/sh\nprintf child-path-was-used\n")
            Process.exec(arrayOf("/bin/chmod", "u+x", shadow.pathString), directory).use {
                assertEquals(0, it.waitFor())
            }
            // The parent's sh is selected, but the new process still receives the replacement PATH.
            for (path in listOf(directory.pathString, ".", "/stdlibx-nonexistent-path-57283", "")) {
                val result =
                    Process.exec(
                        arrayOf("sh", "-c", "printf '%s' \"\$PATH\""),
                        directory,
                        mapOf("PATH" to path),
                        silent = true,
                    )
                assertEquals(0, result.exitCode)
                assertEquals(path, result.stdOut)
                assertEquals("", result.stdErr)
            }
            val result =
                Process.exec(
                    arrayOf("sh", "-c", "printf parent-path"),
                    directory,
                    emptyMap(),
                    silent = true,
                )
            assertEquals(0, result.exitCode)
            assertEquals("parent-path", result.stdOut)
        } finally {
            shadow.deleteIfExists()
        }
    }

    fun unixFallbackPreservesArgumentsEnvironmentAndDirectory() {
        if (windows) return
        val name = "fallback script"
        val executable = directory.resolve(name)
        val argument = "space 'single' \"double\" \\ \$HOME ; not-a-command 😀"
        try {
            executable.writeString(
                "printf '%s|%s|%s|%s|' \"\${0##*/}\" \"\$1\" \"\$2\" \"\$STDLIBX_FALLBACK\"; pwd -P; exit 7\n"
            )
            Process.exec(arrayOf("/bin/chmod", "u+x", executable.pathString), directory).use {
                assertEquals(0, it.waitFor())
            }
            for (command in listOf(executable.pathString, "./$name")) {
                Process.exec(
                        arrayOf(command, argument, ""),
                        directory,
                        mapOf("PATH" to ".", "STDLIBX_FALLBACK" to "value=😀"),
                    )
                    .use { process ->
                        process.stdin.close()
                        assertEquals(
                            "$name|$argument||value=😀|${directory.realPath().pathString}",
                            process.stdout.readAllBytes().decodeToString().trimEnd(),
                        )
                        assertContentEquals(ByteArray(0), process.stderr.readAllBytes())
                        assertEquals(7, process.waitFor())
                    }
            }
        } finally {
            executable.deleteIfExists()
        }
    }

    fun unixFallbackDoesNotMaskPermissionOrInterpreterErrors() {
        if (windows) return
        val executable = directory.resolve("fallback-errors")
        val marker = directory.resolve("must-not-run")
        try {
            executable.writeString("printf wrong > must-not-run\n")
            Process.exec(arrayOf("/bin/chmod", "a-x", executable.pathString), directory).use {
                assertEquals(0, it.waitFor())
            }
            assertFailsWith<ProcessException> {
                Process.exec(arrayOf(executable.pathString), directory).use { it.waitFor() }
            }
            assertFalse(marker.exists())
            executable.writeString(
                "#!/stdlibx-nonexistent-interpreter-31875\nprintf wrong > must-not-run\n"
            )
            Process.exec(arrayOf("/bin/chmod", "u+x", executable.pathString), directory).use {
                assertEquals(0, it.waitFor())
            }
            assertFailsWith<ProcessException> {
                Process.exec(arrayOf(executable.pathString), directory).use { it.waitFor() }
            }
            assertFalse(marker.exists())
        } finally {
            executable.deleteIfExists()
            marker.deleteIfExists()
        }
    }
}
