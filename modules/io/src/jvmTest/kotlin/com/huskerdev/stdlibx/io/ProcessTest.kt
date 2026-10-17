package com.huskerdev.stdlibx.io

import com.huskerdev.stdlibx.io.fs.FileSystem
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*

class ProcessTest {
    @Test fun brokenStdinHasPortableException() = contract { brokenStdinHasPortableException() }

    @Test
    fun unicodeExecutableAndWorkingDirectoryPaths() = contract {
        unicodeExecutableAndWorkingDirectoryPaths()
    }

    @Test
    fun repeatedWindowsTerminationIsIdempotent() = contract {
        repeatedWindowsTerminationIsIdempotent()
    }

    private fun contract(block: ProcessContract.() -> Unit) {
        val native = Files.createTempDirectory("stdlibx-process-")
        val directory = checkNotNull(FileSystem.default?.asSync()).path(native.toString())
        try {
            ProcessContract(directory, java.io.File.separatorChar == '\\').block()
        } finally {
            runBlocking { directory.deleteRecursively() }
        }
    }

    @Test fun outputErrorAndExit() = contract { outputErrorAndExit() }

    @Test
    fun capturingExecSeparatesOrMergesOutput() = contract { capturingExecSeparatesOrMergesOutput() }

    @Test
    fun capturingExecSendsEofAndPreservesLaunchOptions() = contract {
        capturingExecSendsEofAndPreservesLaunchOptions()
    }

    @Test
    fun capturingExecDrainsBothFullPipesAndDecodesOnlyAfterEof() = contract {
        capturingExecDrainsBothFullPipesAndDecodesOnlyAfterEof()
    }

    @Test fun stdinRoundTrip() = contract { stdinRoundTrip() }

    @Test
    fun stdinWritesAreImmediateWithoutFlushOrClose() = contract {
        stdinWritesAreImmediateWithoutFlushOrClose()
    }

    @Test fun workingDirectoryAndEnvironment() = contract { workingDirectoryAndEnvironment() }

    @Test
    fun invalidArgumentsAndStartupFailures() = contract { invalidArgumentsAndStartupFailures() }

    @Test fun closeKillsAndReleasesStreams() = contract { closeKillsAndReleasesStreams() }

    @Test
    fun killRequestsPlatformDefaultTermination() = contract {
        killRequestsPlatformDefaultTermination()
    }

    @Test
    fun killForciblyBypassesUnixSignalHandlers() = contract {
        killForciblyBypassesUnixSignalHandlers()
    }

    @Test
    fun bothKillMethodsCloseStreamsImmediately() = contract {
        bothKillMethodsCloseStreamsImmediately()
    }

    @Test
    fun argumentsRemainSeparateWithoutShellExpansion() = contract {
        argumentsRemainSeparateWithoutShellExpansion()
    }

    @Test fun binaryStreamsAndLookahead() = contract { binaryStreamsAndLookahead() }

    @Test
    fun interactiveLookaheadDoesNotWaitForAnotherByte() = contract {
        interactiveLookaheadDoesNotWaitForAnotherByte()
    }

    @Test
    fun executableLookupUsesParentPathAndExplicitChildDirectory() = contract {
        executableLookupUsesParentPathAndExplicitChildDirectory()
    }

    @Test
    fun childPathDoesNotOverrideParentExecutableSearch() = contract {
        childPathDoesNotOverrideParentExecutableSearch()
    }

    @Test
    fun unixFallbackPreservesArgumentsEnvironmentAndDirectory() = contract {
        unixFallbackPreservesArgumentsEnvironmentAndDirectory()
    }

    @Test
    fun unixFallbackDoesNotMaskPermissionOrInterpreterErrors() = contract {
        unixFallbackDoesNotMaskPermissionOrInterpreterErrors()
    }

    @Test
    fun cancellingAwaitDoesNotKillTheChild(): Unit = runBlocking {
        if (java.io.File.separatorChar == '\\') return@runBlocking
        Process.exec(arrayOf("/bin/sh", "-c", "exec sleep 60")).use { process ->
            val wait = launch(start = CoroutineStart.UNDISPATCHED) { process.awaitExit() }
            wait.cancelAndJoin()
            assertTrue(process.isAlive)
            process.kill()
            assertEquals(process.waitFor(), process.awaitExit())
        }
    }

    @Test
    fun drainsBothStreamsConcurrently(): Unit = runBlocking {
        if (java.io.File.separatorChar == '\\') return@runBlocking
        Process.exec(
                arrayOf(
                    "/bin/sh",
                    "-c",
                    "i=0; while [ \"\$i\" -lt 20000 ]; do printf out; printf err >&2; i=\$((i+1)); done",
                )
            )
            .use { process ->
                process.stdin.close()
                val out = async(Dispatchers.IO) { process.stdout.readAllBytes() }
                val err = async(Dispatchers.IO) { process.stderr.readAllBytes() }
                assertEquals(60000, out.await().size)
                assertEquals(60000, err.await().size)
                assertEquals(0, process.awaitExit())
            }
    }
}
