@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.fs.indexeddb

import com.huskerdev.stdlibx.io.*
import com.huskerdev.stdlibx.fs.*
import com.huskerdev.stdlibx.fs.unix.AsyncUnixFileSystem
import kotlin.js.Promise
import kotlin.time.Clock
import kotlin.time.Instant

private val databaseIdentities = mutableMapOf<String, Any>()
internal data class DbFileSnapshot(val node: DbNode, val blob: DbBlob)

/**
 * An origin-private filesystem backed by a dedicated IndexedDB database (schema version 1).
 * Each file's content is stored as one Blob, without persistent chunks. Paths are rooted at /
 * and normalized lexically. Instances using the same database name share filesystem identity.
 *
 * Creation, whole-file writes, shallow copies and moves (including directory subtrees) are
 * transactional across tabs/workers. Streams buffer writes until close; concurrent content
 * changes cause close to fail rather than silently overwrite another writer's work.
 *
 * Timestamps and hidden status are virtual metadata. File reads/opens update access time.
 * POSIX/DOS permissions and symbolic links are not supported. Storage is subject to browser
 * quota/eviction. Call close when this instance is no longer needed; it does not delete data.
 */
class IndexedDbFileSystem(
    val databaseName: String = UserFileSystem.namespace
): AsyncUnixFileSystem, AsyncUserFileSystem, AsyncCloseable {
    override val identity: Any = databaseIdentities.getOrPut(databaseName) { Any() }

    private var session: DbSession? = null
    private var opening: Promise<DbSession>? = null
    private var closed = false

    override val separator: String = "/"

    override fun workingDir(): AsyncPath = path("/")

    override suspend fun homeDir(): AsyncPath? = null
    override suspend fun tmpDir(): AsyncPath = createDirectories(path("/tmp"), null)
    override suspend fun documentsDir(): AsyncPath? = null
    override suspend fun downloadsDir(): AsyncPath? = null
    override suspend fun picturesDir(): AsyncPath? = null
    override suspend fun videosDir(): AsyncPath? = null
    override suspend fun musicDir(): AsyncPath? = null
    override suspend fun desktopDir(): AsyncPath? = null
    override suspend fun rootDirectories(): Iterable<AsyncPath> = listOf(path("/"))

    private fun normalized(path: Path): AsyncPath = absolute(path).normalize()
    private fun path(path: Path): AsyncPath = this.path(path.pathString)

    private suspend fun database(path: Path): DbSession {
        check(!closed) { "IndexedDB filesystem is closed" }
        if (!isIndexedDbSupported()) throw UnsupportedOperationException("IndexedDB is not available in this context")

        if(session != null && !session!!.closed)
            return session!!

        val pending = opening ?: indexedDbCall(path) {
            openIndexedDb(databaseName)
        }.also { promise ->
            opening = promise
            // Cache even if the caller is canceled while the non-abortable open request runs.
            promise.then(
                onFulfilled = { value ->
                    opening = null
                    if (closed) value.close() else session = value
                    null
                },
                onRejected = {
                    opening = null
                    null
                }
            )
        }
        val result = indexedDbCall(path) { pending.awaitDb() }
        check(!closed) { "IndexedDB filesystem is closed" }
        return result
    }

    private suspend fun <T> transact(
        path: Path,
        write: Boolean = false,
        block: DbScope<T>.() -> Unit
    ): T {
        val target = normalized(path)
        return dbTransaction(database(target), target, write, block)
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        session?.close()
        session = null
        // The open callback above closes any connection that finishes opening after close().
    }

    override suspend fun realPath(path: Path, followLinks: Boolean): AsyncPath = transact(path) {
        resolve(this.path) { finish(this.path) }
    }

    override suspend fun exists(path: Path, followLinks: Boolean): Boolean = try {
        assertFS(path)
        realPath(path, followLinks)
        true
    } catch (_: NoSuchFileException) { false } catch (_: FileSystemException) { false }

    override suspend fun notExists(path: Path, followLinks: Boolean): Boolean = try {
        assertFS(path)
        realPath(path, followLinks)
        false
    } catch (_: NoSuchFileException) { true } catch (_: FileSystemException) { false }

    override suspend fun isReadable(path: Path): Boolean = exists(path, true)
    override suspend fun isWritable(path: Path): Boolean = exists(path, true)

    override suspend fun isExecutable(path: Path): Boolean {
        assertFS(path)
        return false
    }

    override suspend fun mimeType(path: Path): String = transact(path) {
        resolve(this.path) {
            finish(if (it.kind == "file") fallbackMimeType(this.path.extension) else "")
        }
    }

    override suspend fun readAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot = transact(path) {
        resolve(this.path) {
            finish(FileAttributesSnapshot(
                isRegularFile = it.kind == "file",
                isDirectory = it.kind == "directory",
                isSymbolicLink = false,
                size = if (it.kind == "file") it.size.dbSize() else null,
                isHidden = it.hidden,
                creationTime = Instant.parse(it.creationTime),
                lastModifiedTime = Instant.parse(it.modifiedTime),
                lastAccessTime = Instant.parse(it.accessTime)
            ))
        }
    }

    override suspend fun writeAttributes(path: Path, attributes: FileAttributesChanges, followLinks: Boolean) {
        assertFS(path)
        if (attributes.posixPermissions != null || attributes.dosIsReadOnly != null)
            throw UnsupportedOperationException("IndexedDB filesystem has no POSIX/DOS permissions")

        transact(path, true) {
            resolve(this.path) { node ->
                attributes.creationTime?.let { node.creationTime = it.toString() }
                attributes.lastModifiedTime?.let { node.modifiedTime = it.toString() }
                attributes.lastAccessTime?.let { node.accessTime = it.toString() }
                attributes.isHidden?.let { node.hidden = it }
                nodes.put(node)
                finish(Unit)
            }
        }
    }

    override suspend fun streamDirectory(directory: Path): AsyncStream<AsyncPath> {
        val names: List<String> = transact(directory) {
            resolve(path) { node ->
                if (node.kind != "directory")
                    throw FileSystemException(directory, "Not a directory")
                val request = nodes.index("children").getAll(node.id)
                request(request) {
                    val children = dbNodes(request)
                    finish(List(children.length) { checkNotNull(children[it]).name })
                }
            }
        }
        val base = path(directory)
        return AsyncStream.OfList(names.map { base.resolve(it) })
    }

    override suspend fun isSameFiles(path: Path, other: Path): Boolean {
        val second = normalized(other)
        return transact(path) {
            resolve(this.path) { first ->
                resolve(second) {
                    finish(first.id == it.id)
                }
            }
        }
    }

    override suspend fun readSymbolicLink(path: Path): AsyncPath {
        assertFS(path)
        throw UnsupportedOperationException("IndexedDB filesystem has no symbolic links")
    }

    override suspend fun createSymbolicLink(path: Path, target: Path): AsyncPath {
        assertFS(path); assertFS(target)
        throw UnsupportedOperationException("IndexedDB filesystem has no symbolic links")
    }

    override suspend fun deleteExisting(path: Path) = transact(path, true) {
        resolve(this.path) { node ->
            remove(node) {
                node(node.parentId) { parent ->
                    touch(parent)
                    finish(Unit)
                }
            }
        }
    }

    override suspend fun deleteIfExists(path: Path): Boolean = try {
        deleteExisting(path)
        true
    } catch (_: NoSuchFileException) { false }

    private fun requirePermissions(permissions: Set<PosixFilePermission>?) {
        if (permissions != null)
            throw UnsupportedOperationException("IndexedDB filesystem has no POSIX permissions")
    }

    override suspend fun createDirectory(path: Path, permissions: Set<PosixFilePermission>?): AsyncPath {
        normalized(path)
        requirePermissions(permissions)
        transact(path, true) {
            if (this.path.parent == null)
                throw FileAlreadyExistsException(path)
            parent(this.path) { parent ->
                child(parent, this.path.name) { existing ->
                    if (existing != null)
                        throw FileAlreadyExistsException(path)
                    create(parent, this.path.name, "directory")
                    finish(Unit)
                }
            }
        }
        return path(path)
    }

    override suspend fun createDirectories(path: Path, permissions: Set<PosixFilePermission>?): AsyncPath {
        normalized(path)
        requirePermissions(permissions)
        transact(path, true) {
            val names = this.path.names

            fun step(current: DbNode, index: Int) {
                if (index == names.size) {
                    finish(Unit)
                    return
                }
                child(current, names[index]) {
                    if (it != null && it.kind != "directory")
                        throw FileAlreadyExistsException(path)
                    step(it ?: create(current, names[index], "directory"), index + 1)
                }
            }
            node("root") { step(it, 0) }
        }
        return path(path)
    }

    override suspend fun createFile(path: Path, permissions: Set<PosixFilePermission>?): AsyncPath {
        normalized(path)
        requirePermissions(permissions)
        transact(path, true) {
            openFile(this.path, FileCreation.CREATE_NEW) {
                finish(Unit)
            }
        }
        return path(path)
    }

    private suspend fun readFile(path: Path): DbFileSnapshot = transact(path, true) {
        resolve(this.path) { node ->
            blob(node) { data ->
                node.accessTime = Clock.System.now().toString()
                nodes.put(node)
                finish(DbFileSnapshot(node, data))
            }
        }
    }

    override suspend fun newInputStream(path: Path): AsyncInputStream =
        IndexedDbInputStream(path, readFile(path).blob)

    override suspend fun readBytes(path: Path): ByteArray {
        val data = readFile(path).blob
        if (data.size > Int.MAX_VALUE)
            throw FileSystemException(path, "File is too large for a ByteArray")
        return indexedDbCall(path) {
            dbBytes(data.arrayBuffer().awaitDb()).toByteArray()
        }
    }

    override suspend fun writeBytes(
        path: Path,
        bytes: ByteArray,
        mode: FileWriteMode,
        creation: FileCreation
    ): AsyncPath {
        val target = normalized(path)
        val data = indexedDbCall(path) { bytes.toDbBlob() }
        transact(target, true) {
            openFile(this.path, creation) { node ->
                if (mode == FileWriteMode.TRUNCATE) {
                    publish(node, data)
                    finish(Unit)
                }
                else blob(node) { base ->
                    publish(node, dbCompose(base, data, mode))
                    finish(Unit)
                }
            }
        }
        return path(path)
    }

    override suspend fun newOutputStream(
        path: Path,
        mode: FileWriteMode,
        creation: FileCreation
    ): AsyncOutputStream {
        val snapshot: DbFileSnapshot = transact(path, true) {
            openFile(this.path, creation) { node ->
                if (mode == FileWriteMode.TRUNCATE)
                    finish(DbFileSnapshot(node, dbEmptyBlob()))
                else blob(node) {
                    finish(DbFileSnapshot(node, it))
                }
            }
        }

        val id = snapshot.node.id
        val revision = snapshot.node.revision
        return IndexedDbOutputStream(path, snapshot.blob, mode) { data ->
            transact(path, true) {
                node(id) { current ->
                    if (current.revision != revision)
                        throw FileSystemException(path, "File content changed while the output stream was open")
                    publish(current, data)
                    finish(Unit)
                }
            }
        }
    }

    /** Validates and removes only an empty directory or a file, within the caller's transaction. */
    private fun <T> DbScope<T>.replace(existing: DbNode?, target: Path, overwrite: Boolean, block: () -> Unit) {
        if (existing == null) block()
        else {
            if (!overwrite) throw FileAlreadyExistsException(target)
            remove(existing, block)
        }
    }

    override suspend fun copyTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        copyAttributes: Boolean,
        followLinks: Boolean
    ): AsyncPath {
        val to = normalized(target)
        transact(source, true) {
            resolve(path) { sourceNode ->
                if (path == to) {
                    finish(Unit)
                    return@resolve
                }
                parent(to) { parent ->
                    child(parent, to.name) { existing ->
                        fun copy(data: DbBlob?) {
                            replace(existing, to, overwrite) {
                                val copied = create(parent, to.name, sourceNode.kind)
                                if (data != null) {
                                    copied.size = data.size
                                    content.put(data, copied.id)
                                }
                                if (copyAttributes) {
                                    copied.creationTime = sourceNode.creationTime
                                    copied.modifiedTime = sourceNode.modifiedTime
                                    copied.accessTime = sourceNode.accessTime
                                    copied.hidden = sourceNode.hidden
                                }
                                nodes.put(copied)
                                finish(Unit)
                            }
                        }
                        if (sourceNode.kind == "file")
                            blob(sourceNode) { copy(it) }
                        else copy(null)
                    }
                }
            }
        }
        return path(target)
    }

    override suspend fun moveTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        atomicMove: Boolean
    ): AsyncPath {
        val to = normalized(target)
        transact(source, true) {
            resolve(path) { moved ->
                if (path == to) {
                    finish(Unit)
                    return@resolve
                }
                if (moved.id == "root")
                    throw FileSystemException(source, "Cannot move the database root")
                parent(to) { newParent ->
                    child(newParent, to.name) { existing ->
                        fun rename() {
                            replace(existing, to, overwrite) {
                                node(moved.parentId) { oldParent ->
                                    moved.parentId = newParent.id
                                    moved.name = to.name
                                    nodes.put(moved)
                                    touch(oldParent)
                                    if (oldParent.id != newParent.id) touch(newParent)
                                    finish(Unit)
                                }
                            }
                        }
                        fun checkAncestor(current: DbNode) {
                            require(current.id != moved.id) { "Cannot move a directory into its own subtree" }
                            if (current.id == "root")
                                rename()
                            else node(current.parentId) { checkAncestor(it) }
                        }
                        if (moved.kind == "directory")
                            checkAncestor(newParent)
                        else rename()
                    }
                }
            }
        }
        return path(target)
    }

    override suspend fun fileStore(path: Path): FileStore {
        realPath(path, true)
        val estimate = indexedDbCall(path) { dbStorageEstimate().awaitDb() }
        val quota = estimate.quota?.takeIf { it.isFinite() && it >= 0 }?.toLong()
        val usage = estimate.usage?.takeIf { it.isFinite() && it >= 0 }?.toLong()
        return object : FileStore {
            override val name: String = databaseName
            override val type: String = "indexeddb"
            override val isReadonly: Boolean = false
            override val totalSpace: Long? = quota
            override val usableSpace: Long? =
                if (quota != null && usage != null) (quota - usage).coerceAtLeast(0)
                else null
            override val unallocatedSpace: Long? = null
        }
    }

    override suspend fun fileStores(): Iterable<FileStore> = listOf(fileStore(path("/")))

    override fun asSync(): SyncUserFileSystem? = null
    override fun asAsync(): IndexedDbFileSystem = this
}
