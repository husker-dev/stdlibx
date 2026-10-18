@file:OptIn(ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.huskerdev.stdlibx.process

import com.huskerdev.stdlibx.io.*

import com.huskerdev.stdlibx.env
import com.huskerdev.stdlibx.fs.AsyncKnWindowsFileSystem
import com.huskerdev.stdlibx.fs.KnWindowsFileSystem
import com.huskerdev.stdlibx.fs.Path
import kotlinx.cinterop.*
import platform.windows.*
import platform.posix.memset
import kotlin.concurrent.atomics.AtomicInt

private fun processWindowsError(operation: String, code: UInt = GetLastError()): Nothing =
    throw ProcessException("$operation: Windows error $code")

/** Quoting for the conventional Microsoft CRT argv parser, NOT for cmd.exe or PowerShell. */
internal fun quoteProcessWindowsArgument(value: String): String {
    if (value.isNotEmpty() && value.none { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '"' })
        return value
    return buildString {
        append('"')
        var slashes = 0
        for (char in value) {
            if (char == '\\') {
                slashes++
                continue
            }
            repeat(if (char == '"') slashes * 2 + 1 else slashes) {
                append('\\')
            }
            slashes = 0
            append(char)
        }
        repeat(slashes * 2) { append('\\') }
        append('"')
    }
}

private fun windowsProcessExecutable(command: String, directory: String): String {
    val fs = KnWindowsFileSystem()

    fun absolute(value: String): String {
        val path = fs.path(value)
        return if (path.isAbsolute)
            path.pathString
        else fs.absolute(fs.path(directory).resolve(path)).pathString
    }
    fun candidates(value: String): List<String> {
        val path = absolute(value)
        return if (fs.path(value).extension.isEmpty())
            listOf(path, "$path.exe")
        else listOf(path)
    }

    val locations = processExecutablePaths(command, windows = true) {
        val path = env().entries.firstOrNull {
            it.key.equals("PATH", true)
        }?.value
        path ?: memScoped {
            val buffer = allocArray<UShortVar>(32768)
            val count = GetSystemDirectoryW(buffer, 32768u)
            if (count == 0u || count >= 32768u)
                processWindowsError("GetSystemDirectoryW")
            buffer.toKString()
        }
    }
    for (location in locations) {
        for (candidate in candidates(location)) {
            val attributes = GetFileAttributesW(candidate)
            if (attributes != INVALID_FILE_ATTRIBUTES && attributes and FILE_ATTRIBUTE_DIRECTORY.toUInt() == 0u)
                return candidate
        }
    }
    throw ProcessException("Executable not found: $command")
}

internal actual fun startProcess(
    arguments: Array<String>,
    workingDirectory: Path?,
    environment: Map<String, String>?
): ProcessBackend {
    validateProcessArguments(arguments, environment, windows = true)
    val directory = workingDirectory?.let {
        require(it.fileSystem is KnWindowsFileSystem || it.fileSystem is AsyncKnWindowsFileSystem) {
            "Working directory must use the host filesystem"
        }
        it.absolute().pathString
    } ?: com.huskerdev.stdlibx.workingDir

    val executable = windowsProcessExecutable(arguments[0], directory)
    arguments[0] = executable

    val commandLine = arguments.joinToString(" ", transform = ::quoteProcessWindowsArgument)
    require(commandLine.length < 32767) {
        "Windows command line exceeds 32767 UTF-16 code units"
    }

    return memScoped {
        val handles = mutableListOf<HANDLE>()
        var attributeList: CPointer<ByteVar>? = null
        var attributesInitialized = false
        var started: HANDLE? = null

        try {
            val security = alloc<SECURITY_ATTRIBUTES> {
                nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
                lpSecurityDescriptor = null
                bInheritHandle = TRUE
            }
            fun pipe(): Pair<HANDLE, HANDLE> {
                val reader = alloc<HANDLEVar>()
                val writer = alloc<HANDLEVar>()
                if (CreatePipe(reader.ptr, writer.ptr, security.ptr, 0u) == 0)
                    processWindowsError("CreatePipe")
                val readHandle = checkNotNull(reader.value)
                val writeHandle = checkNotNull(writer.value)
                handles += readHandle
                handles += writeHandle
                return readHandle to writeHandle
            }

            val (stdinRead, stdinWrite) = pipe()
            val (stdoutRead, stdoutWrite) = pipe()
            val (stderrRead, stderrWrite) = pipe()
            for (handle in listOf(stdinWrite, stdoutRead, stderrRead)) {
                if (SetHandleInformation(handle, HANDLE_FLAG_INHERIT.toUInt(), 0u) == 0)
                    processWindowsError("SetHandleInformation")
            }

            val bytes = alloc<ULongVar>()
            bytes.value = 0uL
            InitializeProcThreadAttributeList(null, 1u, 0u, bytes.ptr.reinterpret())

            attributeList = allocArray(bytes.value.toLong())
            val list = attributeList.reinterpret<cnames.structs._PROC_THREAD_ATTRIBUTE_LIST>()
            if (InitializeProcThreadAttributeList(list, 1u, 0u, bytes.ptr.reinterpret()) == 0)
                processWindowsError("InitializeProcThreadAttributeList")
            attributesInitialized = true

            val inherited = allocArray<HANDLEVar>(3)
            inherited[0] = stdinRead
            inherited[1] = stdoutWrite
            inherited[2] = stderrWrite

            if (UpdateProcThreadAttribute(
                    list,
                    0u,
                    PROC_THREAD_ATTRIBUTE_HANDLE_LIST.convert(),
                    inherited,
                    (3 * sizeOf<HANDLEVar>()).convert(),
                    null,
                    null
            ) == 0) processWindowsError("UpdateProcThreadAttribute")

            val startup = alloc<STARTUPINFOEXW>()
            memset(startup.ptr, 0, sizeOf<STARTUPINFOEXW>().convert())
            startup.StartupInfo.cb = sizeOf<STARTUPINFOEXW>().toUInt()
            startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES.toUInt()
            startup.StartupInfo.hStdInput = stdinRead
            startup.StartupInfo.hStdOutput = stdoutWrite
            startup.StartupInfo.hStdError = stderrWrite
            startup.lpAttributeList = list

            val env = environment?.entries
                ?.sortedBy { it.key.uppercase() }
                ?.joinToString("\u0000", postfix = "\u0000") {
                    "${it.key}=${it.value}"
                }?.wcstr?.getPointer(this)

            val info = alloc<PROCESS_INFORMATION>()
            if (CreateProcessW(
                    executable,
                    commandLine.wcstr.getPointer(this),
                    null,
                    null,
                    TRUE,
                    (CREATE_UNICODE_ENVIRONMENT or EXTENDED_STARTUPINFO_PRESENT).toUInt(),
                    env,
                    directory,
                    startup.ptr.reinterpret(),
                    info.ptr
            ) == 0) processWindowsError("CreateProcessW")

            started = info.hProcess
            CloseHandle(info.hThread)
            for (handle in listOf(stdinRead, stdoutWrite, stderrWrite)) {
                CloseHandle(handle)
                handles.remove(handle)
            }

            val backend = WindowsProcessBackend(
                info.dwProcessId.toLong(),
                checkNotNull(info.hProcess),
                stdinWrite,
                stdoutRead,
                stderrRead
            )
            handles.clear()
            started = null
            backend
        } finally {
            if (attributesInitialized)
                DeleteProcThreadAttributeList(attributeList!!.reinterpret())
            for (handle in handles)
                CloseHandle(handle)
            started?.let {
                TerminateProcess(it, 1u)
                WaitForSingleObject(it, INFINITE)
                CloseHandle(it)
            }
        }
    }
}

private class WindowsProcessBackend(
    override val pid: Long,
    private val handle: HANDLE,
    input: HANDLE,
    output: HANDLE,
    error: HANDLE
) : ProcessBackend {
    override val stdin: SyncOutputStream = ProcessWindowsOutputStream(input)
    override val stdout: SyncInputStream = ProcessWindowsInputStream(output)
    override val stderr: SyncInputStream = ProcessWindowsInputStream(error)

    private val lock = AtomicInt(0)
    private var status: Int? = null
    private var released = false
    private var terminationRequested = false

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.compareAndSet(0, 1))
            Sleep(0u)
        try {
            return block()
        } finally {
            lock.store(0)
        }
    }

    private fun poll(): Int? {
        status?.let { return it }

        val wait = WaitForSingleObject(handle, 0u)
        if (wait == WAIT_TIMEOUT.toUInt()) return null
        if (wait != WAIT_OBJECT_0) processWindowsError("WaitForSingleObject")

        return memScoped {
            val code = alloc<UIntVar>()
            if (GetExitCodeProcess(handle, code.ptr) == 0)
                processWindowsError("GetExitCodeProcess")
            code.value.toInt().also { status = it }
        }
    }

    override fun exitCode(): Int? = locked {
        poll()
    }

    override fun waitFor(): Int {
        while (true) {
            exitCode()?.let { return it }
            Sleep(10u)
        }
    }

    override fun kill(forcibly: Boolean) = locked {
        // Like JVM Process.destroy(), Windows has no general graceful-termination primitive.
        if (terminationRequested || poll() != null) return@locked
        if (TerminateProcess(handle, 1u) == 0) {
            val error = GetLastError()
            // CreateProcessW gave us termination rights. ACCESS_DENIED means exit raced
            // this request; the process handle may not be signaled yet during teardown.
            if (error != ERROR_ACCESS_DENIED.toUInt() && poll() == null)
                processWindowsError("TerminateProcess", error)
        }
        terminationRequested = true
    }

    override fun release() = locked {
        if (!released) {
            released = true
            if (CloseHandle(handle) == 0)
                processWindowsError("CloseHandle")
        }
    }
}

private class ProcessWindowsInputStream(private val handle: HANDLE) : ProcessPipeInput() {

    override fun readNative(buffer: ByteArray, offset: Int, length: Int): Int = memScoped {
        val count = alloc<UIntVar>()
        if (buffer.usePinned {
            ReadFile(
                handle,
                it.addressOf(offset),
                length.toUInt(),
                count.ptr,
                null
            )
        } == 0) {
            val code = GetLastError()
            if (code == ERROR_BROKEN_PIPE.toUInt() || code == ERROR_HANDLE_EOF.toUInt())
                return@memScoped 0
            processWindowsError("ReadFile", code)
        }
        count.value.toInt()
    }

    override fun availableNative(): Long = memScoped {
        val count = alloc<UIntVar>()
        if (PeekNamedPipe(
                handle,
                null,
                0u,
                null,
                count.ptr,
                null
        ) == 0) {
            if (GetLastError() == ERROR_BROKEN_PIPE.toUInt())
                return@memScoped 0L
            processWindowsError("PeekNamedPipe")
        }
        count.value.toLong()
    }

    override fun closeNative() {
        if (CloseHandle(handle) == 0)
            processWindowsError("CloseHandle")
    }
}

private class ProcessWindowsOutputStream(private val handle: HANDLE) : ProcessPipeOutput() {
    override fun writeNative(bytes: ByteArray, offset: Int, length: Int): Int = memScoped {
        val written = alloc<UIntVar>()
        if (bytes.usePinned {
            WriteFile(
                handle,
                it.addressOf(offset),
                length.toUInt(),
                written.ptr,
                null)
        } == 0) processWindowsError("WriteFile")
        written.value.toInt()
    }

    override fun closeNative() {
        if (CloseHandle(handle) == 0)
            processWindowsError("CloseHandle")
    }
}
