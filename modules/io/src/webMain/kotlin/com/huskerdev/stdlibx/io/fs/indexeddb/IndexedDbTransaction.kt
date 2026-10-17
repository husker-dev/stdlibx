@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.io.fs.indexeddb

import com.huskerdev.stdlibx.io.fs.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.*
import kotlin.time.Clock

/** Callback-only request chains keep every request inside an active IDB event callback. */
internal class DbScope<T>(val transaction: DbTransaction, val path: AsyncPath) {
    val nodes = transaction.objectStore("nodes")
    val content = transaction.objectStore("content")

    private var completed = false
    private var value: T? = null

    var failure: Throwable? = null
        private set

    fun finish(result: T) {
        value = result
        completed = true
    }

    @Suppress("UNCHECKED_CAST")
    fun result(): T {
        check(completed) { "IndexedDB transaction completed without a result" }
        return value as T
    }

    fun guard(block: () -> Unit) {
        if (failure != null) return
        try {
            block()
        } catch (error: Throwable) {
            abort(error)
        }
    }

    fun abort(error: Throwable) {
        if (failure == null) failure = error
        try {
            transaction.abort()
        } catch (_: Throwable) { /* Already committed or aborted. */ }
    }

    fun request(request: DbRequest, block: () -> Unit) {
        request.onsuccess = { guard(block) }
        request.onerror = {
            if (failure == null)
                failure = indexedDbError(path, request.error)
        }
    }

    fun node(id: String, block: (DbNode) -> Unit) {
        val request = nodes.get(id)
        request(request) {
            block(dbResult(request) as DbNode?
                ?: throw NoSuchFileException(path))
        }
    }

    fun child(parent: DbNode, name: String, block: (DbNode?) -> Unit) {
        if (parent.kind != "directory")
            throw FileSystemException(path, "Not a directory")
        val request = nodes.index("entry").get(dbKey(parent.id, name))
        request(request) {
            block(dbResult(request) as DbNode?)
        }
    }

    fun resolve(target: AsyncPath, block: (DbNode) -> Unit) {
        val names = target.names
        fun step(current: DbNode, index: Int) {
            if (index == names.size)
                block(current)
            else child(current, names[index]) {
                step(it ?: throw NoSuchFileException(target), index + 1)
            }
        }
        node("root") { step(it, 0) }
    }

    fun parent(target: AsyncPath, block: (DbNode) -> Unit) {
        val parent = target.parent
            ?: throw FileSystemException(target, "Operation is not permitted on the database root")
        resolve(parent) {
            if (it.kind != "directory") throw FileSystemException(target, "Not a directory")
            block(it)
        }
    }

    fun blob(node: DbNode, block: (DbBlob) -> Unit) {
        if (node.kind != "file") throw FileSystemException(path, "Not a regular file")
        val request = content.get(node.id)
        request(request) {
            block(dbResult(request) as DbBlob? ?: throw FileSystemException(path, "Missing file content"))
        }
    }

    fun create(parent: DbNode, name: String, kind: String): DbNode {
        val node = dbNode(dbId(), parent.id, name, kind, Clock.System.now().toString())
        nodes.add(node)
        if (kind == "file")
            content.put(dbEmptyBlob(), node.id)
        touch(parent)
        return node
    }

    fun touch(node: DbNode) {
        node.modifiedTime = Clock.System.now().toString()
        nodes.put(node)
    }

    fun openFile(target: AsyncPath, creation: FileCreation, block: (DbNode) -> Unit) {
        if (target.parent == null) throw FileAlreadyExistsException(target)
        parent(target) { parent ->
            child(parent, target.name) { existing ->
                if (existing != null && creation == FileCreation.CREATE_NEW)
                    throw FileAlreadyExistsException(target)
                val node = existing ?: if (creation == FileCreation.OPEN_EXISTING) throw NoSuchFileException(target)
                    else create(parent, target.name, "file")

                if (node.kind != "file")
                    throw FileSystemException(target, "Not a regular file")
                block(node)
            }
        }
    }

    fun publish(node: DbNode, data: DbBlob) {
        node.size = data.size
        node.revision++
        touch(node)
        content.put(data, node.id)
    }

    fun remove(node: DbNode, block: () -> Unit) {
        if (node.id == "root")
            throw FileSystemException(path, "Cannot remove the database root")
        fun delete() {
            nodes.delete(node.id)
            content.delete(node.id)
            block()
        }
        if (node.kind != "directory") delete()
        else {
            val request = nodes.index("children").count(node.id)
            request(request) {
                if (dbCount(request) != 0)
                    throw DirectoryNotEmptyException(path)
                delete()
            }
        }
    }
}

internal suspend fun <T> dbTransaction(
    session: DbSession,
    path: AsyncPath,
    write: Boolean,
    block: DbScope<T>.() -> Unit
): T = indexedDbCall(path) {
    suspendCancellableCoroutine { continuation ->
        if (!continuation.isActive) return@suspendCancellableCoroutine

        val transaction = dbBegin(session, write)
        val scope = DbScope<T>(transaction, path)
        transaction.oncomplete = {
            try {
                continuation.resume(scope.result())
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
        transaction.onabort = {
            continuation.resumeWithException(scope.failure ?: indexedDbError(path, transaction.error))
        }
        transaction.onerror = { /* A failed request aborts the transaction and triggers onabort. */ }
        continuation.invokeOnCancellation {
            scope.abort(it ?: kotlinx.coroutines.CancellationException("Cancelled"))
        }
        scope.guard { scope.block() }
    }
}
