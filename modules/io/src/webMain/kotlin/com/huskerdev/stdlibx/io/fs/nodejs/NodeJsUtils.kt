@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.io.fs.nodejs

import com.huskerdev.stdlibx.OS
import com.huskerdev.stdlibx.io.fs.DirectoryNotEmptyException
import com.huskerdev.stdlibx.io.fs.FileAlreadyExistsException
import com.huskerdev.stdlibx.io.fs.FileCreation
import com.huskerdev.stdlibx.io.fs.FileSystemException
import com.huskerdev.stdlibx.io.fs.FileWriteMode
import com.huskerdev.stdlibx.io.fs.NoSuchFileException
import com.huskerdev.stdlibx.io.fs.Path
import com.huskerdev.stdlibx.io.fs.PosixFilePermission
import com.huskerdev.stdlibx.io.fs.SyncUserFileSystem
import com.huskerdev.stdlibx.io.fs.SyncPath
import com.huskerdev.stdlibx.io.fs.FileSystemLoopException
import com.huskerdev.stdlibx.io.fs.AccessDeniedException
import com.huskerdev.stdlibx.io.fs.parseXdgConfigFile
import kotlin.time.Instant
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun nodeFailureCode(error: FileSystemException): String? =
    (error.cause as? JsException)?.let { nodeError(it.thrownValue)?.code }

internal fun SyncUserFileSystem.userDirectory(key: String, defaultName: String): SyncPath? {
    if (OS.isLinux)
        return try { parseXdgConfigFile(key)?.let(::path) } catch (_: NoSuchFileException) { null }

    val directory = homeDir()!!.resolve(defaultName)
    return if (infoOrNull(directory)?.directory == true)
        directory
    else null
}

internal fun info(path: Path, followLinks: Boolean = true): NodeStat = NodeStat(nodeCall(path) {
    if (followLinks) nodeFs().statSync(path.nodeName, statOptions())
    else nodeFs().lstatSync(path.nodeName, statOptions())
})

internal fun infoOrNull(path: Path, followLinks: Boolean = true): NodeStat? = try {
    info(path, followLinks)
} catch (_: NoSuchFileException) { null }

internal fun Set<PosixFilePermission>.convertPermissions(isWindows: Boolean): Int {
    if (isWindows) throw UnsupportedOperationException("POSIX permissions on Windows")
    return fold(0) { mode, permission ->
        mode or (256 shr permission.ordinal)
    }
}

internal fun setTimes(path: Path, followLinks: Boolean, access: Instant, modification: Instant) {
    fun seconds(time: Instant) = time.epochSeconds.toDouble() + time.nanosecondsOfSecond / 1e9
    nodeCall(path) {
        if (followLinks) nodeFs().utimesSync(path.nodeName, seconds(access), seconds(modification))
        else nodeFs().lutimesSync(path.nodeName, seconds(access), seconds(modification))
    }
}

internal inline fun <T> nodeCall(path: Path, action: () -> T): T = try {
    action()
} catch (error: com.huskerdev.stdlibx.io.IOException) {
    throw error
} catch (error: RuntimeException) {
    throw error
} catch (error: JsException) {
    val details = nodeError(error.thrownValue)
    when (val code = details?.code) {
        "ERR_ACCESS_DENIED" -> throw com.huskerdev.stdlibx.io.SecurityException(details?.message ?: error.message, error)
        "ENOENT" -> throw NoSuchFileException(path, error)
        "ENOTDIR" -> throw FileSystemException(path, "Not a directory", error)
        "ELOOP" -> throw FileSystemLoopException(path, error)
        "EACCES", "EPERM" -> throw AccessDeniedException(path, error)
        "EEXIST" -> throw FileAlreadyExistsException(path)
        "ENOTEMPTY" -> throw DirectoryNotEmptyException(path)
        else -> throw FileSystemException(path, "${code ?: "NODE_ERROR"}: ${details?.message ?: error.message ?: "Node filesystem error"}", error)
    }
}

internal val Path.nodeName: String
    get() = pathString.ifEmpty { "." }

internal fun readText(path: Path): String =
    nodeCall(path) { nodeFs().readFileSync(path.nodeName, "utf8") }


internal suspend fun <T : JsAny?> Promise<T>.awaitFile(controller: NodeAbortController): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { controller.abort() }
        then(
            onFulfilled = { value -> continuation.resume(value); null },
            onRejected = { error -> continuation.resumeWithException(error.asJsException()); null }
        )
    }

internal fun NodeBuffer.toByteArray(): ByteArray =
    ByteArray(length) { readUInt8(it).toByte() }

internal fun ByteArray.toNodeBuffer(): NodeBuffer = nodeBuffer().alloc(size).also { buffer ->
    for (index in indices) buffer.writeUInt8(this[index].toInt() and 255, index)
}

internal fun nodeWriteFlags(mode: FileWriteMode, creation: FileCreation): Int {
    val flags = nodeFs().constants
    return flags.O_WRONLY or when (mode) {
        FileWriteMode.FROM_START -> 0
        FileWriteMode.TRUNCATE -> flags.O_TRUNC
        FileWriteMode.APPEND -> flags.O_APPEND
    } or when (creation) {
        FileCreation.OPEN_EXISTING -> 0
        FileCreation.CREATE_IF_MISSING -> flags.O_CREAT
        FileCreation.CREATE_NEW -> flags.O_CREAT or flags.O_EXCL
    }
}
