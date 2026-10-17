package com.huskerdev.stdlibx.io

import com.huskerdev.stdlibx.io.fs.NioFileSystem
import com.huskerdev.stdlibx.io.fs.Path
import com.huskerdev.stdlibx.io.process.ProcessPipeInput
import com.huskerdev.stdlibx.io.process.ProcessPipeOutput
import java.nio.file.FileSystems
import java.io.File

internal actual fun startProcess(arguments: Array<String>, workingDirectory: Path?, environment: Map<String, String>?): ProcessBackend {
    validateProcessArguments(arguments, environment, windows = File.separatorChar == '\\')
    val directory = workingDirectory?.let {
        val fs = it.fileSystem as? NioFileSystem
        require(fs?.nioFileSystem === FileSystems.getDefault()) {
            "Working directory must use the host filesystem"
        }
        fs.nioFileSystem.getPath(it.pathString).toAbsolutePath().toFile()
    }
    try {
        val builder = ProcessBuilder(arguments.toList())
            .directory(directory)
        environment?.let {
            builder.environment().apply {
                clear()
                putAll(it)
            }
        }
        return JvmProcessBackend(builder.start())
    } catch (error: java.io.IOException) {
        throw ProcessException("Cannot start process: ${arguments[0]}", error)
    }
}

private class JvmProcessBackend(private val process: java.lang.Process) : ProcessBackend {
    override val pid: Long = process.pid()
    override val stdin: SyncOutputStream = JvmProcessOutputStream(process.outputStream)
    override val stdout: SyncInputStream = JvmProcessInputStream(process.inputStream)
    override val stderr: SyncInputStream = JvmProcessInputStream(process.errorStream)

    override fun exitCode(): Int? = try {
        process.exitValue()
    } catch (_: IllegalThreadStateException) { null }

    override fun waitFor(): Int = process.waitFor()

    override fun kill(forcibly: Boolean) = processCall {
        if (process.isAlive) {
            if (forcibly) process.destroyForcibly()
            else process.destroy()
        }
    }
    override fun release() = Unit
}

private class JvmProcessInputStream(private val source: java.io.InputStream) : ProcessPipeInput() {
    override fun readNative(buffer: ByteArray, offset: Int, length: Int): Int =
        processCall { source.read(buffer, offset, length).coerceAtLeast(0) }

    override fun availableNative(): Long =
        processCall { source.available().toLong() }

    override fun closeNative() =
        processCall { source.close() }
}

private class JvmProcessOutputStream(private val source: java.io.OutputStream) : ProcessPipeOutput() {
    override fun writeNative(bytes: ByteArray, offset: Int, length: Int): Int {
        processCall {
            source.write(bytes, offset, length)
            source.flush() // Expose immediate writes like Native.
        }
        return length
    }
    override fun closeNative() = processCall { source.close() }
}

private inline fun <T> processCall(block: () -> T): T = try {
    block()
} catch (error: java.io.IOException) {
    throw ProcessException(error.message ?: "Process I/O failed", error)
}
