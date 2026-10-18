package com.huskerdev.stdlibx.process

import com.huskerdev.stdlibx.io.*

import com.huskerdev.stdlibx.fs.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmStatic
import kotlin.time.Duration.Companion.milliseconds

private const val DEFAULT_STREAM_BUFFER_SIZE = 8192

/**
 * A child process with blocking byte streams. The first command argument is the executable;
 * no shell expansion or quoting interpretation is performed on the argument array. On Unix,
 * an executable with an unrecognized format (ENOEXEC) is retried through /bin/sh, like OpenJDK.
 *
 * Drain [stdout] and [stderr] concurrently when both can produce substantial output. Closing
 * [stdin] sends EOF. Each stdin write is flushed to the OS pipe without an explicit flush call.
 * Waiting for exit does not drain streams and may otherwise deadlock.
 * Stream async adapters use blocking IO on worker threads; cancellation need not interrupt IO.
 * Streams support one reader/writer each, not concurrent operations on the same stream.
 * Process and pipe I/O failures throw [ProcessException]; invalid arguments and closed streams
 * throw [IllegalArgumentException] and [IllegalStateException], respectively.
 * Security-policy denials throw [SecurityException] without being wrapped as process I/O errors.
 *
 * [close] forcefully stops this process if necessary, waits for it, and closes all resources.
 * Descendants are not killed. Kill/close must not race stream operations or another close.
 */
class Process private constructor(private val backend: ProcessBackend) : AutoCloseable {
    companion object {
        /**
         * Starts a desktop JVM/Native process. This API is unavailable on Android and JS/Node.
         * [workingDirectory] must use the host's native
         * filesystem, not a virtual filesystem. Null inherits the parent's current directory.
         *
         * Null [environment] inherits the parent environment; a map replaces it completely.
         * Executable lookup uses the parent's PATH, not PATH from [environment], like OpenJDK.
         * JVM delegates executable lookup to ProcessBuilder. Native Windows
         * additionally recognizes .exe for extensionless names; .bat/.cmd require explicit cmd.
         * Windows arguments use the conventional Microsoft C runtime quoting rules.
         *
         * Startup failures throw [ProcessException]. Success does not imply that the executable
         * has finished initializing. Arguments/environment cannot contain NUL characters.
         */
        @JvmStatic
        fun exec(
            arguments: Array<String>,
            workingDirectory: Path? = null,
            environment: Map<String, String>? = null
        ): Process = Process(startProcess(arguments.copyOf(), workingDirectory, environment?.toMap()))

        /**
         * Blocks until exit and captures both streams as UTF-8 text (invalid sequences are
         * replaced). No input is supplied: stdin is closed immediately. Nonzero exit codes
         * are returned, not thrown. The entire output is retained in memory.
         *
         * Unless [silent], bytes are also written and flushed to the parent's stdout/stderr
         * as they arrive. [errAsStd] merges stderr into both captured and mirrored stdout,
         * leaving [ProcessResult.stdErr] empty. Cross-stream ordering follows received chunks,
         * not necessarily the child's write order. Both readers finish before returning.
         * Pass [silent] or [errAsStd] explicitly to select this overload rather than [exec].
         */
        @JvmStatic
        fun exec(
            arguments: Array<String>,
            workingDirectory: Path? = null,
            environment: Map<String, String>? = null,
            silent: Boolean = false,
            errAsStd: Boolean = false,
        ): ProcessResult = runBlocking {
            exec(arguments, workingDirectory, environment).use { process ->
                process.stdin.close()
                val output = ProcessOutputBuffer()
                val error = if (errAsStd) output else ProcessOutputBuffer()
                val lock = Mutex()

                suspend fun drain(input: SyncInputStream, buffer: ProcessOutputBuffer, mirror: SyncOutputStream) {
                    val bytes = ByteArray(DEFAULT_STREAM_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(bytes)
                        if (count == 0) return
                        lock.withLock {
                            buffer.append(bytes, count)
                            if (!silent) {
                                mirror.write(bytes, 0, count)
                                mirror.flush()
                            }
                        }
                    }
                }
                coroutineScope {
                    val outReader = async(Dispatchers.IO) {
                        drain(process.stdout, output, stdout)
                    }
                    val errReader = async(Dispatchers.IO) {
                        drain(process.stderr, error, if (errAsStd) stdout else stderr)
                    }
                    try {
                        val code = process.awaitExit()
                        outReader.await()
                        errReader.await()
                        ProcessResult(code, output.text(), if (errAsStd) "" else error.text())
                    } catch (failure: Throwable) {
                        // Unblock pipe readers before coroutineScope joins them. Do not close
                        // parent streams while those readers may still be inside a native read.
                        try {
                            process.backend.kill(forcibly = true)
                        } catch (cleanup: Throwable) {
                            failure.addSuppressed(cleanup)
                        }
                        throw failure
                    }
                }
            }
        }
    }

    val pid: Long
        get() = backend.pid

    val stdin: SyncOutputStream
        get() = backend.stdin

    val stdout: SyncInputStream
        get() = backend.stdout

    val stderr: SyncInputStream
        get() = backend.stderr

    /** Null while running; signal termination on Unix is represented as 128 + signal. */
    val exitCode: Int?
        get() = backend.exitCode()

    val isAlive: Boolean
        get() = exitCode == null

    private var closed = false

    /** Blocks until exit, caching the result for subsequent calls. Windows preserves all 32 bits. */
    fun waitFor(): Int = backend.waitFor()

    /** Cancellable polling wait. Cancelling it does not terminate the process. */
    suspend fun awaitExit(): Int {
        while (true) {
            exitCode?.let { return it }
            delay(10.milliseconds)
        }
    }

    /**
     * Requests termination without waiting, like JVM Process.destroy(). Unix sends SIGTERM,
     * which the child may handle or ignore; Windows terminates forcibly. Descendants are unaffected.
     * Closes stdin/stdout/stderr after the request, even if the child ignores termination.
     */
    fun kill() = terminate(forcibly = false)

    /** Requests forceful termination without waiting and closes all three streams, like kill(). */
    fun killForcibly() = terminate(forcibly = true)

    private fun terminate(forcibly: Boolean) {
        backend.kill(forcibly)
        for (stream in listOf(stdin, stdout, stderr)) {
            try {
                stream.close()
            } catch (_: Exception) { /* Termination request already succeeded. */ }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                if (failure == null)
                    failure = error
                else failure.addSuppressed(error)
            }
        }
        cleanup { backend.kill(forcibly = true) }
        cleanup { backend.waitFor() }
        cleanup { stdin.close() }
        cleanup { stdout.close() }
        cleanup { stderr.close() }
        cleanup { backend.release() }
        failure?.let { throw it }
    }
}

/** A process startup, control, or pipe I/O failure, with the platform cause where available. */
class ProcessException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Captured UTF-8 output and the exit status. [stdErr] is empty when errAsStd was enabled. */
class ProcessResult(
    val exitCode: Int,
    val stdOut: String,
    val stdErr: String
)

private class ProcessOutputBuffer {
    private val chunks = ArrayList<ByteArray>()
    private var size = 0
    fun append(bytes: ByteArray, count: Int) {
        check(count in 1..bytes.size && size <= Int.MAX_VALUE - count) { "Process output is too large" }
        chunks.add(bytes.copyOf(count))
        size += count
    }
    fun text(): String {
        val bytes = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        return bytes.decodeToString()
    }
}

internal interface ProcessBackend {
    val pid: Long
    val stdin: SyncOutputStream
    val stdout: SyncInputStream
    val stderr: SyncInputStream
    fun exitCode(): Int?
    fun waitFor(): Int
    fun kill(forcibly: Boolean)
    fun release()
}

/** Receives private argument/environment snapshots from exec; backends may reuse the argument array. */
internal expect fun startProcess(
    arguments: Array<String>,
    workingDirectory: Path?,
    environment: Map<String, String>?
): ProcessBackend

internal fun validateProcessArguments(arguments: Array<String>, environment: Map<String, String>?, windows: Boolean = false) {
    require(arguments.isNotEmpty() && arguments[0].isNotEmpty()) { "An executable is required" }
    require(arguments.none { '\u0000' in it }) { "Process arguments cannot contain NUL" }
    environment?.forEach { (key, value) ->
        require(key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value) {
            "Invalid environment entry: $key"
        }
    }
    if (windows && environment != null)
        require(environment.keys.map(String::lowercase).toSet().size == environment.size) { "Windows environment names are case-insensitive" }
}

/** Explicit paths bypass PATH; empty search entries mean the child's working directory. */
internal fun processExecutablePaths(command: String, windows: Boolean, searchPath: () -> String): List<String> {
    if ('/' in command || (windows && ('\\' in command || ':' in command))) return listOf(command)
    val separator = if (windows) '\\' else '/'
    return searchPath().split(if (windows) ';' else ':').map { entry ->
        val prefix = if (windows) entry.removeSurrounding("\"") else entry
        if (prefix.isEmpty()) command else "$prefix$separator$command"
    }
}
