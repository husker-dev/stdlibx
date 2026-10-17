@file:Suppress("unused")
@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.io.fs.opfs

import com.huskerdev.stdlibx.io.fs.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.*

internal external interface OpfsHandle : JsAny {
    val name: String
    val kind: String
    fun getDirectoryHandle(name: String, options: OpfsCreateOptions): Promise<OpfsHandle>
    fun getFileHandle(name: String, options: OpfsCreateOptions): Promise<OpfsHandle>
    fun removeEntry(name: String): Promise<JsAny?>
    fun removeEntry(name: String, options: OpfsRemoveOptions): Promise<JsAny?>
    fun values(): OpfsIterator
    fun isSameEntry(other: OpfsHandle): Promise<JsBoolean>
    fun getFile(): Promise<OpfsFile>
    fun createWritable(options: OpfsWriteOptions): Promise<OpfsWriter>
}

internal external interface OpfsFile : JsAny {
    val size: Double
    val lastModified: Double
    val type: String
    fun slice(start: Double, end: Double): OpfsFile
    fun arrayBuffer(): Promise<JsAny>
}

internal external interface OpfsWriter : JsAny {
    fun write(data: JsAny): Promise<JsAny?>
    fun seek(position: Double): Promise<JsAny?>
    fun close(): Promise<JsAny?>
    fun abort(): Promise<JsAny?>
}

internal external interface OpfsIterator : JsAny {
    fun next(): Promise<OpfsStep>
}

internal external interface OpfsStep : JsAny {
    val done: Boolean
    val value: OpfsHandle?
}

internal external interface OpfsCreateOptions {
    val create: Boolean
}

internal external interface OpfsRemoveOptions {
    val recursive: Boolean
}

internal external interface OpfsWriteOptions {
    val keepExistingData: Boolean
    val mode: String
}

internal external interface OpfsStorageEstimate : JsAny {
    val quota: Double?
    val usage: Double?
}

internal external interface OpfsError {
    val name: String?
    val message: String?
}

internal external interface OpfsBytes : JsAny {
    val length: Int
}

internal external interface OpfsLease : JsAny {
    fun release()
}

internal external interface OpfsAbortController : JsAny {
    val signal: JsAny
    fun abort()
}

internal fun opfsRoot(): Promise<OpfsHandle> = js("navigator.storage.getDirectory()")
internal fun opfsEstimate(): Promise<OpfsStorageEstimate> = js("navigator.storage.estimate()")
internal fun opfsCreateOptions(create: Boolean): OpfsCreateOptions = js("({create})")
internal fun opfsRemoveOptions(recursive: Boolean): OpfsRemoveOptions = js("({recursive})")
internal fun opfsWriteOptions(keepExistingData: Boolean): OpfsWriteOptions = js("({keepExistingData, mode: 'exclusive'})")
internal fun opfsError(value: JsAny?): OpfsError? = js("value && typeof value === 'object' ? value : null")
internal fun opfsBytes(buffer: JsAny): OpfsBytes = js("new Uint8Array(buffer)")
internal fun opfsAllocateBytes(size: Int): OpfsBytes = js("new Uint8Array(size)")
internal fun opfsGetByte(bytes: OpfsBytes, index: Int): Int = js("bytes[index]")
internal fun opfsSetByte(bytes: OpfsBytes, index: Int, value: Int): Unit = js("{ bytes[index] = value; }")
internal fun opfsIteratorClose(iterator: OpfsIterator): Promise<JsAny?> = js("typeof iterator.return === 'function' ? iterator.return() : Promise.resolve(null)")
internal fun opfsAbortController(): OpfsAbortController = js("new AbortController()")
internal fun opfsLocksSupported(): Boolean = js("typeof navigator !== 'undefined' && !!navigator.locks")

private fun opfsAcquireLease(signal: JsAny): Promise<OpfsLease> = js("""
    new Promise((resolve, reject) => {
        navigator.locks.request('com.huskerdev.osutils.io.opfs.mutations.v1', {signal},
            () => new Promise(release => resolve({release}))).catch(reject);
    })
""")

internal suspend fun <T : JsAny?> Promise<T>.awaitOpfs(): T = suspendCancellableCoroutine { continuation ->
    then(
        onFulfilled = { value -> continuation.resume(value); null },
        onRejected = { error -> continuation.resumeWithException(error.asJsException()); null }
    )
}

/** Serializes library mutations across instances, workers and tabs of the same origin. */
internal suspend fun <T> withOpfsMutation(block: suspend () -> T): T {
    if (!opfsLocksSupported())
        throw UnsupportedOperationException("OPFS mutations require the Web Locks API")
    val controller = opfsAbortController()
    val lease = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            controller.abort()
        }
        opfsAcquireLease(controller.signal).then(
            onFulfilled = { value ->
                continuation.resume(value) { _, acquired, _ -> acquired.release() }
                null
            },
            onRejected = { error -> continuation.resumeWithException(error.asJsException()); null }
        )
    }
    try {
        return block()
    } finally { lease.release() }
}

internal inline fun <T> opfsCall(path: Path, block: () -> T): T = try {
    block()
} catch (error: com.huskerdev.stdlibx.io.IOException) {
    throw error
} catch (error: RuntimeException) {
    // On Kotlin/JS JsException also matches Kotlin exceptions; do not remap our own failures
    // or coroutine cancellation as DOM errors. Kotlin/Wasm keeps those types distinct.
    throw error
} catch (error: JsException) {
    val details = opfsError(error.thrownValue)
    throw when (details?.name) {
        "NotFoundError" -> NoSuchFileException(path)
        "TypeMismatchError" -> FileSystemException(path, "Not a directory", error)
        "NotAllowedError" -> AccessDeniedException(path, error)
        "SecurityError" -> com.huskerdev.stdlibx.io.SecurityException(details.message ?: error.message, error)
        "InvalidModificationError" -> DirectoryNotEmptyException(path)
        else -> FileSystemException(path, "${details?.name ?: "OPFS"}: ${details?.message ?: error.message}")
    }
}

internal fun Double.opfsSize(): Long {
    require(isFinite() && this >= 0 && this <= 9_007_199_254_740_991.0) {
        "Invalid OPFS size: $this"
    }
    return toLong()
}

internal fun ByteArray.toOpfsBytes(offset: Int = 0, length: Int = size - offset): OpfsBytes =
    opfsAllocateBytes(length).also { result ->
        for (index in 0 until length)
            opfsSetByte(result, index, this[offset + index].toInt() and 255)
    }

internal fun OpfsBytes.toByteArray(): ByteArray = ByteArray(length) {
    opfsGetByte(this, it).toByte()
}
