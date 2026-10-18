package com.huskerdev.stdlibx.process

import com.huskerdev.stdlibx.fs.FileSystem
import kotlin.random.Random
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
        val fs = checkNotNull(FileSystem.default?.asSync())
        val directory =
            fs.tmpDir()
                .resolve("stdlibx-process-${Random.nextLong().toULong().toString(16)}")
                .createDirectory()
        try {
            ProcessContract(directory, fs.separator == "\\").block()
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
    fun brokenStdinDoesNotRaiseSigpipe() {
        if (checkNotNull(FileSystem.default).separator == "\\") return
        Process.exec(arrayOf("/bin/sh", "-c", "exit 0")).use { process ->
            assertEquals(0, process.waitFor())
            assertFailsWith<ProcessException> { process.stdin.write(ByteArray(32)) }
        }
    }

    @Test
    fun concurrentWaitAndKill(): Unit = runBlocking {
        if (checkNotNull(FileSystem.default).separator == "\\") return@runBlocking
        Process.exec(arrayOf("/bin/sh", "-c", "exec sleep 60")).use { process ->
            val wait = async(Dispatchers.Default) { process.waitFor() }
            process.kill()
            assertNotEquals(0, wait.await())
            assertEquals(process.waitFor(), process.awaitExit())
        }
    }
}
