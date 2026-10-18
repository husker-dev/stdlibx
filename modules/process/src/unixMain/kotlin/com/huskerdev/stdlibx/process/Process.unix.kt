@file:OptIn(ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.huskerdev.stdlibx.process

import com.huskerdev.stdlibx.io.*

import com.huskerdev.stdlibx.fs.KnUnixFileSystem
import com.huskerdev.stdlibx.env
import com.huskerdev.stdlibx.fs.Path
import com.huskerdev.stdlibx.process.interop.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.concurrent.atomics.AtomicInt

internal fun processUnixError(operation: String, code: Int = errno): Nothing =
    throw ProcessException("$operation: ${strerror(code)?.toKString() ?: "Unix error $code"}")

internal actual fun startProcess(
    arguments: Array<String>,
    workingDirectory: Path?,
    environment: Map<String, String>?
): ProcessBackend {
    validateProcessArguments(arguments, environment)
    val directory = workingDirectory?.let {
        require(it.fileSystem is KnUnixFileSystem) { "Working directory must use the host filesystem" }
        it.absolute().pathString
    }
    val parentEnvironment = env()
    val effectiveEnvironment = environment ?: parentEnvironment
    val executable = arguments[0]
    val candidates = processExecutablePaths(executable, windows = false) {
        parentEnvironment["PATH"] ?: "/usr/bin:/bin"
    }

    return memScoped {
        fun strings(values: List<String>): CPointer<CPointerVar<ByteVar>> {
            val array = allocArray<CPointerVar<ByteVar>>(values.size + 1)
            values.forEachIndexed { index, value ->
                array[index] = value.cstr.getPointer(this)
            }
            array[values.size] = null
            return array
        }

        val argv = strings(arguments.toList())

        // Prepare fallback argv before fork; the C child only substitutes the executable path.
        val shellArgv = allocArray<CPointerVar<ByteVar>>(arguments.size + 2)
        shellArgv[0] = "/bin/sh".cstr.getPointer(this)
        shellArgv[1] = null
        for (index in 1 until arguments.size)
            shellArgv[index + 1] = argv[index]
        shellArgv[arguments.size + 1] = null

        val envp = strings(effectiveEnvironment.map { "${it.key}=${it.value}" })
        val paths = strings(candidates)

        val pipes = allocArray<IntVar>(8)
        repeat(8) {
            pipes[it] = -1
        }
        fun closePipe(index: Int) {
            val fd = pipes[index]
            pipes[index] = -1
            if (fd >= 0)
                close(fd)
        }

        var pid = -1
        var transferred = false
        try {
            for (index in 0 until 8 step 2) {
                if (openProcessPipe(checkNotNull(pipes + index)) != 0)
                    processUnixError("pipe")

                for (end in index..index + 1) {
                    var fd = pipes[end]
                    if (fd < 3) {
                        val duplicate = fcntl(fd, F_DUPFD_CLOEXEC, 3)
                        if (duplicate < 0)
                            processUnixError("fcntl(F_DUPFD_CLOEXEC)")
                        closePipe(end)
                        fd = duplicate
                        pipes[end] = fd
                    }
                    if (fcntl(fd, F_SETFD, FD_CLOEXEC) < 0)
                        processUnixError("fcntl(FD_CLOEXEC)")
                }
            }

            configureProcessStdin(pipes[1])
            val maxFd = sysconf(_SC_OPEN_MAX).let {
                if (it < 0) 65536
                else minOf(it, Int.MAX_VALUE.toLong()).toInt()
            }

            pid = stdlibx_process_fork_exec(argv, envp, paths, shellArgv, directory, pipes, maxFd)
            if (pid < 0)
                processUnixError("fork")
            for (index in arrayOf(0, 3, 5, 7))
                closePipe(index)

            // Check startup result
            val error = alloc<IntVar>()
            val bytes = error.ptr.reinterpret<ByteVar>()
            var received = 0
            while (received < sizeOf<IntVar>().toInt()) {
                val count = read(pipes[6], bytes + received, (sizeOf<IntVar>() - received).convert()).toInt()
                if (count < 0 && errno == EINTR) continue
                if (count < 0) processUnixError("read process startup status")
                if (count == 0) break // Successful execve closes the CLOEXEC status pipe.
                received += count
            }
            if (received != 0) {
                if (received != sizeOf<IntVar>().toInt())
                    processUnixError("Incomplete process startup status", EIO)
                processUnixError("Cannot start $executable", error.value)
            }

            // Successful, return Process backend
            val backend = UnixProcessBackend(
                processId = pid,
                input = pipes[1],
                output = pipes[2],
                error = pipes[4]
            )
            for (index in listOf(1, 2, 4))
                pipes[index] = -1
            transferred = true
            backend
        } finally {
            if (pid > 0 && !transferred) {
                kill(pid, SIGKILL)
                while (waitpid(pid, null, 0) < 0 && errno == EINTR) {
                    /* Retry interrupted reaping. */
                }
            }
            repeat(8, ::closePipe)
        }
    }
}

private class UnixProcessBackend(private val processId: Int, input: Int, output: Int, error: Int) : ProcessBackend {
    override val pid: Long = processId.toLong()

    override val stdin: SyncOutputStream = ProcessUnixOutputStream(input)
    override val stdout: SyncInputStream = ProcessUnixInputStream(output)
    override val stderr: SyncInputStream = ProcessUnixInputStream(error)

    private val lock = AtomicInt(0)
    private var status: Int? = null

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.compareAndSet(0, 1))
            usleep(100u)
        try {
            return block()
        } finally {
            lock.store(0)
        }
    }

    private fun poll(): Int? {
        status?.let { return it }
        return memScoped {
            val value = alloc<IntVar>()
            var result: Int
            do {
                result = waitpid(processId, value.ptr, WNOHANG)
            } while (result < 0 && errno == EINTR)
            if (result < 0) processUnixError("waitpid")
            if (result != 0) {
                // Linux and Darwin use the same wait status layout for exited/signaled children.
                val signal = value.value and 0x7f
                (if (signal == 0) (value.value ushr 8) and 0xff else 128 + signal)
                    .also { status = it }
            } else null
        }
    }

    override fun exitCode(): Int? = locked {
        poll()
    }

    override fun waitFor(): Int {
        while (true) {
            exitCode()?.let { return it }
            usleep(10_000u)
        }
    }

    override fun kill(forcibly: Boolean) = locked {
        val signal = if (forcibly) SIGKILL else SIGTERM
        if (poll() == null && kill(processId, signal) != 0 && errno != ESRCH)
            processUnixError("kill")
    }

    override fun release() = Unit
}

private class ProcessUnixInputStream(private val fd: Int) : ProcessPipeInput() {

    override fun readNative(buffer: ByteArray, offset: Int, length: Int): Int = buffer.usePinned {
        var count: Long
        do {
            count = read(fd, it.addressOf(offset), length.convert())
        } while (count < 0 && errno == EINTR)
        if (count < 0)
            processUnixError("read process pipe")
        count.toInt()
    }

    override fun closeNative() {
        if (close(fd) != 0)
            processUnixError("close process pipe")
    }
}

private class ProcessUnixOutputStream(private val fd: Int) : ProcessPipeOutput() {
    override fun writeNative(bytes: ByteArray, offset: Int, length: Int): Int = bytes.usePinned {
        writeProcessPipe(fd, it.addressOf(offset), length)
    }

    override fun closeNative() {
        if (close(fd) != 0)
            processUnixError("close process pipe")
    }
}

internal expect fun openProcessPipe(fds: CPointer<IntVar>): Int
internal expect fun configureProcessStdin(fd: Int)
internal expect fun writeProcessPipe(fd: Int, bytes: CPointer<ByteVar>, length: Int): Int
