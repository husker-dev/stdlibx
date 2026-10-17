@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.io.fs.indexeddb

import com.huskerdev.stdlibx.io.fs.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.*

internal fun isIndexedDbSupported(): Boolean = js("typeof indexedDB !== 'undefined'")

internal external interface DbSession : JsAny {
    val database: JsAny
    val closed: Boolean
    fun close()
}

internal external interface DbTransaction : JsAny {
    var oncomplete: (() -> Unit)?
    var onabort: (() -> Unit)?
    var onerror: (() -> Unit)?
    val error: DbError?
    fun objectStore(name: String): DbStore
    fun abort()
}

internal external interface DbRequest : JsAny {
    var onsuccess: (() -> Unit)?
    var onerror: (() -> Unit)?
    val error: DbError?
}

internal external interface DbStore : JsAny {
    fun get(key: String): DbRequest
    fun add(value: JsAny): DbRequest
    fun put(value: JsAny): DbRequest
    fun put(value: JsAny, key: String): DbRequest
    fun delete(key: String): DbRequest
    fun index(name: String): DbIndex
}

internal external interface DbIndex : JsAny {
    fun get(key: JsAny): DbRequest
    fun getAll(key: String): DbRequest
    fun count(key: String): DbRequest
}

internal external interface DbNode : JsAny {
    val id: String
    var parentId: String
    var name: String
    val kind: String
    var size: Double
    var creationTime: String
    var modifiedTime: String
    var accessTime: String
    var hidden: Boolean
    var revision: Double
}
internal external interface DbBlob : JsAny {
    val size: Double
    fun slice(start: Double, end: Double): DbBlob
    fun arrayBuffer(): Promise<JsAny>
}

internal external interface DbBytes : JsAny {
    val length: Int
}

internal external interface DbError {
    val name: String?
    val message: String?
}

internal external interface DbEstimate : JsAny {
    val quota: Double?
    val usage: Double?
}

/** No coroutine suspension occurs in upgrade or IndexedDB request callbacks. */
internal fun openIndexedDb(name: String): Promise<DbSession> = js("""
    new Promise((resolve, reject) => {
        const request = indexedDB.open(name, 1);
        let rejected = false;
        request.onblocked = () => {
            rejected = true;
            reject(new Error('IndexedDB open is blocked by another connection: ' + name));
        };
        request.onerror = () => reject(request.error);
        request.onupgradeneeded = () => {
            if (rejected) { request.transaction.abort(); return; }
            const db = request.result;
            const nodes = db.createObjectStore('nodes', {keyPath: 'id'});
            nodes.createIndex('entry', ['parentId', 'name'], {unique: true});
            nodes.createIndex('children', 'parentId');
            db.createObjectStore('content');
            const now = new Date().toISOString();
            nodes.add({
                id: 'root', 
                parentId: '', 
                name: '', 
                kind: 'directory', 
                size: 0,
                creationTime: now, 
                modifiedTime: now, 
                accessTime: now, 
                hidden: false, 
                revision: 0
            });
        };
        request.onsuccess = () => {
            const db = request.result;
            if (rejected) { db.close(); return; }
            const session = {database: db, closed: false, close() { this.closed = true; db.close(); }};
            db.onversionchange = () => session.close();
            db.onclose = () => { session.closed = true; };
            resolve(session);
        };
    })
""")

internal fun dbBegin(session: DbSession, write: Boolean): DbTransaction =
    js("session.database.transaction(['nodes', 'content'], write ? 'readwrite' : 'readonly')")

internal fun dbResult(request: DbRequest): JsAny? = js("request.result == null ? null : request.result")
internal fun dbCount(request: DbRequest): Int = js("request.result")
internal fun dbNodes(request: DbRequest): JsArray<DbNode> = js("request.result")
internal fun dbKey(parentId: String, name: String): JsAny = js("[parentId, name]")

internal fun dbNode(id: String, parentId: String, name: String, kind: String, time: String): DbNode =
    js("({id, parentId, name, kind, size: 0, creationTime: time, modifiedTime: time, accessTime: time, hidden: name.startsWith('.'), revision: 0})")

internal fun dbId(): String = js("""
    typeof crypto.randomUUID === 'function' ? crypto.randomUUID()
        : Array.from(crypto.getRandomValues(new Uint32Array(4)), value => value.toString(16).padStart(8, '0')).join('')
""")

internal fun dbError(error: JsAny?): DbError? = js("error && typeof error === 'object' ? error : null")
internal fun dbEmptyBlob(): DbBlob = js("new Blob([])")
internal fun dbBlob(parts: JsArray<JsAny>): DbBlob = js("new Blob(parts)")
internal fun dbParts(): JsArray<JsAny> = js("[]")
internal fun dbPush(parts: JsArray<JsAny>, part: JsAny): Unit = js("{ parts.push(part); }")

internal fun dbCompose(base: DbBlob, data: DbBlob, mode: FileWriteMode): DbBlob = when (mode) {
    FileWriteMode.FROM_START -> dbOverwrite(base, data)
    FileWriteMode.TRUNCATE -> data
    FileWriteMode.APPEND -> dbAppend(base, data)
}

private fun dbOverwrite(base: DbBlob, data: DbBlob): DbBlob = js("new Blob([data, base.slice(data.size)])")
private fun dbAppend(base: DbBlob, data: DbBlob): DbBlob = js("new Blob([base, data])")
internal fun dbBytes(buffer: JsAny): DbBytes = js("new Uint8Array(buffer)")
internal fun dbAllocate(size: Int): DbBytes = js("new Uint8Array(size)")
internal fun dbByte(bytes: DbBytes, index: Int): Int = js("bytes[index]")
internal fun dbSetByte(bytes: DbBytes, index: Int, value: Int): Unit = js("{ bytes[index] = value; }")

internal fun dbStorageEstimate(): Promise<DbEstimate> =
    js("typeof navigator !== 'undefined' && navigator.storage && navigator.storage.estimate ? navigator.storage.estimate() : Promise.resolve({})")

internal fun ByteArray.toDbBlob(offset: Int = 0, length: Int = size - offset): DbBlob {
    val bytes = dbAllocate(length)
    for (index in 0 until length)
        dbSetByte(bytes, index, this[offset + index].toInt() and 255)
    return dbBlob(dbParts()
        .also { dbPush(it, bytes) })
}

internal fun DbBytes.toByteArray(): ByteArray = ByteArray(length) { dbByte(this, it).toByte() }

internal fun Double.dbSize(): Long {
    require(isFinite() && this >= 0 && this <= 9_007_199_254_740_991.0) { "Invalid IndexedDB Blob size: $this" }
    return toLong()
}

internal suspend fun <T : JsAny?> Promise<T>.awaitDb(): T = suspendCancellableCoroutine { continuation ->
    then(
        onFulfilled = { value ->
            continuation.resume(value)
            null
        },
        onRejected = { error ->
            continuation.resumeWithException(error.asJsException())
            null
        }
    )
}

internal fun indexedDbError(path: Path, error: DbError?): Exception = when (error?.name) {
    "ConstraintError" -> FileAlreadyExistsException(path)
    "SecurityError" -> com.huskerdev.stdlibx.io.SecurityException(error?.message ?: "Database access forbidden by security policy")
    "NotAllowedError" -> AccessDeniedException(path)
    else -> FileSystemException(path, "${error?.name ?: "IndexedDB"}: ${error?.message ?: "Database operation failed"}")
}

internal inline fun <T> indexedDbCall(path: Path, block: () -> T): T = try {
    block()
} catch (error: com.huskerdev.stdlibx.io.IOException) {
    throw error
} catch (error: RuntimeException) {
    throw error
} catch (error: JsException) {
    throw indexedDbError(path, dbError(error.thrownValue))
}
