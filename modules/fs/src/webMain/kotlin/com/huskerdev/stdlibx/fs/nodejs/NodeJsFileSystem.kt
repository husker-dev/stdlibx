@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.fs.nodejs

import com.huskerdev.stdlibx.OS
import com.huskerdev.stdlibx.io.*
import com.huskerdev.stdlibx.fs.*
import com.huskerdev.stdlibx.fs.unix.*
import com.huskerdev.stdlibx.fs.windows.*
import kotlin.js.*

interface NodeJsFileSystem: UserFileSystem {
    override fun asSync(): SyncNodeJsFileSystem
    override fun asAsync(): AsyncNodeJsFileSystem
}

/**
 * Node's synchronous filesystem API, shared by JS and Wasm/JS. Async stream views delegate
 * to the same synchronous descriptors (and consequently block the Node event loop).
 * Requires Node 22.3+ (process.getBuiltinModule). ACLs, Windows DOS flags and setting birthtime are not
 * exposed by Node. No process lifecycle handlers are installed by this filesystem.
 * Node rename has no portable NOREPLACE flag: non-overwrite moves check the destination
 * before rename, but cannot prevent another process creating it between those operations.
 */
interface SyncNodeJsFileSystem: NodeJsFileSystem, SyncUserFileSystem {

    private val isWindows: Boolean
        get() = this is WindowsFileSystem

    override val separator: String
        get() = if (isWindows) "\\" else "/"

    override fun workingDir(): SyncPath =
        path(com.huskerdev.stdlibx.workingDir)

    override fun homeDir(): SyncPath =
        path(nodeOs().homedir())

    override fun tmpDir(): SyncPath =
        path(nodeOs().tmpdir())

    override fun documentsDir(): SyncPath? =
        userDirectory("DOCUMENTS", "Documents")

    override fun downloadsDir(): SyncPath? =
        userDirectory("DOWNLOAD", "Downloads")

    override fun picturesDir(): SyncPath? =
        userDirectory("PICTURES", "Pictures")

    override fun videosDir(): SyncPath? =
        userDirectory("VIDEOS", if (OS.isMacOS) "Movies" else "Videos")

    override fun musicDir(): SyncPath? =
        userDirectory("MUSIC", "Music")

    override fun desktopDir(): SyncPath? =
        userDirectory("DESKTOP", "Desktop")

    override fun rootDirectories(): Iterable<SyncPath> =
        if (!isWindows) listOf(path("/"))
        else ('A'..'Z').map { path("$it:\\") }.filter { infoOrNull(it)?.directory == true }

    /** Node has no portable mount enumeration API; enumerate accessible roots and known mounts. */
    override fun fileStores(): Iterable<FileStore> {
        val mounts = rootDirectories().toMutableList()
        when (nodeOs().platform()) {
            "linux" -> {
                val text = try {
                    readText(path("/proc/self/mountinfo"))
                } catch (_: NoSuchFileException) { "" }

                text.lineSequence().forEach { line ->
                    val mount = line.split(' ').getOrNull(4) ?: return@forEach
                    mounts += path(mount
                        .replace("\\040", " ")
                        .replace("\\011", "\t")
                        .replace("\\012", "\n")
                        .replace("\\134", "\\")
                    )
                }
            }
            "darwin" -> if (infoOrNull(path("/Volumes"))?.directory == true)
                mounts += streamDirectory(path("/Volumes")).use { it.asSequence().toList() }
        }
        mounts += workingDir()
        return mounts.distinctBy { it.pathString }.mapNotNull {
            try {
                fileStore(it)
            } catch (_: FileSystemException) { null }
        }
    }

    override fun absolute(path: Path): SyncPath {
        assertFS(path)
        return if (path.isAbsolute) path(path.pathString) else path(nodePath().resolve(path.pathString.ifEmpty { "." }))
    }

    override fun realPath(path: Path, followLinks: Boolean): SyncPath {
        assertFS(path)
        if (followLinks)
            return path(nodeCall(path) { nodeFs().realpathSync(path.nodeName) })
        info(path, false)
        return absolute(path)
    }

    override fun readAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot {
        assertFS(path)
        return readNodeAttributes(path, followLinks, isWindows)
    }

    override fun writeAttributes(path: Path, attributes: FileAttributesChanges, followLinks: Boolean) {
        assertFS(path)
        writeNodeAttributes(path, attributes, followLinks, isWindows)
    }

    override fun isReadable(path: Path): Boolean {
        assertFS(path)
        return nodeAccessible(path, nodeFs().constants.R_OK)
    }

    override fun isWritable(path: Path): Boolean {
        assertFS(path)
        return nodeAccessible(path, nodeFs().constants.W_OK)
    }

    override fun isExecutable(path: Path): Boolean {
        assertFS(path)
        return nodeAccessible(path, nodeFs().constants.X_OK)
    }

    override fun mimeType(path: Path): String {
        assertFS(path)
        // Node core does not expose a MIME registry/database.
        return fallbackMimeType(path.extension)
    }

    override fun streamDirectory(directory: Path): SyncStream<SyncPath> {
        assertFS(directory)
        val names = nodeCall(directory) {
            nodeFs().readdirSync(directory.nodeName)
        }
        return SyncStream.OfList(List(names.length) {
            path(directory.pathString).resolve(names[it].toString())
        })
    }

    override fun exists(path: Path, followLinks: Boolean): Boolean = try {
        assertFS(path)
        info(path, followLinks)
        true
    } catch (_: NoSuchFileException) { false } catch (_: FileSystemException) { false }

    override fun notExists(path: Path, followLinks: Boolean): Boolean = try {
        assertFS(path)
        info(path, followLinks)
        false
    } catch (_: NoSuchFileException) { true } catch (_: FileSystemException) { false }

    override fun isSameFiles(path: Path, other: Path): Boolean {
        assertFS(path)
        assertFS(other)
        return info(path).sameFile(info(other))
    }

    override fun readSymbolicLink(path: Path): SyncPath {
        assertFS(path)
        return try {
            path(nodeCall(path) { nodeFs().readlinkSync(path.nodeName) })
        } catch (error: FileSystemException) {
            if (nodeFailureCode(error) == "EINVAL") throw NotLinkException(path, error.cause ?: error)
            throw error
        }
    }

    override fun createSymbolicLink(path: Path, target: Path): SyncPath {
        assertFS(path)
        assertFS(target)
        val addressed = if (target.isAbsolute || target.root != null)
            target else absolute(path).parent!!.resolve(target)
        val type = if (isWindows && infoOrNull(addressed)?.directory == true)
            "dir" else "file"
        nodeCall(path) { nodeFs().symlinkSync(target.pathString, path.nodeName, type) }
        return path(path.pathString)
    }

    override fun deleteExisting(path: Path) {
        assertFS(path)
        val directory = info(path, false).directory
        nodeCall(path) {
            if (directory)
                nodeFs().rmdirSync(path.nodeName)
            else nodeFs().unlinkSync(path.nodeName)
        }
    }

    override fun deleteIfExists(path: Path): Boolean = try {
        deleteExisting(path)
        true
    } catch (_: NoSuchFileException) { false }

    override fun createDirectory(path: Path, permissions: Set<PosixFilePermission>?): SyncPath {
        assertFS(path)
        val mode = permissions?.convertPermissions(isWindows) ?: 511
        nodeCall(path) { nodeFs().mkdirSync(path.nodeName, mkdirOptions(mode)) }
        return path(path.pathString)
    }

    override fun createDirectories(path: Path, permissions: Set<PosixFilePermission>?): SyncPath {
        assertFS(path)
        permissions?.convertPermissions(isWindows) ?: 511
        try {
            createDirectory(path, permissions)
        } catch (error: FileAlreadyExistsException) {
            if (!info(path).directory) throw error
        } catch (error: NoSuchFileException) {
            val parent = absolute(path).parent ?: throw error
            createDirectories(parent, permissions)
            try {
                createDirectory(path, permissions)
            } catch (exists: FileAlreadyExistsException) {
                if (!info(path).directory) throw exists
            }
        }
        return path(path.pathString)
    }

    override fun createFile(path: Path, permissions: Set<PosixFilePermission>?): SyncPath {
        assertFS(path)
        val mode = permissions?.convertPermissions(isWindows) ?: 438
        val fd = nodeCall(path) {
            nodeFs().openSync(path.nodeName, "wx", mode)
        }
        nodeCall(path) { nodeFs().closeSync(fd) }
        return path(path.pathString)
    }

    override fun copyTo(source: Path, target: Path, overwrite: Boolean, copyAttributes: Boolean, followLinks: Boolean): SyncPath {
        assertFS(source)
        assertFS(target)
        val sourceInfo = info(source, followLinks)
        val targetInfo = infoOrNull(target, false)

        if (targetInfo != null) {
            if (sourceInfo.sameFile(targetInfo)) return path(target.pathString)
            if (!overwrite) throw FileAlreadyExistsException(target)
        }
        if (!sourceInfo.directory && !sourceInfo.regular && !sourceInfo.link)
            throw UnsupportedOperationException("Copying special files is not supported")

        // Unlink first, so overwrite never follows a destination symlink.
        if (targetInfo != null) deleteExisting(target)
        when {
            sourceInfo.link -> {
                val link = readSymbolicLink(source)
                val type = if (isWindows && infoOrNull(source)?.directory == true) "dir" else "file"
                nodeCall(target) { nodeFs().symlinkSync(link.pathString, target.nodeName, type) }
            }
            sourceInfo.directory -> createDirectory(target, null)
            else -> nodeCall(target) {
                val fs = nodeFs()
                fs.copyFileSync(source.nodeName, target.nodeName, fs.constants.COPYFILE_EXCL)
            }
        }
        if (copyAttributes) {
            if (!isWindows && !sourceInfo.link)
                nodeCall(target) { nodeFs().chmodSync(target.nodeName, sourceInfo.mode and 4095) }
            setTimes(target, !sourceInfo.link, sourceInfo.accessed, sourceInfo.modified)
            // Node cannot set creationTime/birthtime.
        }
        return path(target.pathString)
    }

    override fun moveTo(source: Path, target: Path, overwrite: Boolean, atomicMove: Boolean): SyncPath {
        assertFS(source)
        assertFS(target)
        val sourceInfo = info(source, false)
        val targetInfo = infoOrNull(target, false)

        if (targetInfo != null) {
            if (sourceInfo.sameFile(targetInfo)) return path(target.pathString)
            if (!overwrite && !atomicMove) throw FileAlreadyExistsException(target)
            if (targetInfo.directory && streamDirectory(target).use { it.hasNext() })
                throw DirectoryNotEmptyException(target)
            if (!atomicMove && (sourceInfo.directory != targetInfo.directory || isWindows && sourceInfo.directory))
                deleteExisting(target)
        }

        try {
            nodeCall(source) { nodeFs().renameSync(source.nodeName, target.nodeName) }
        } catch (error: FileSystemException) {
            val crossDevice = nodeFailureCode(error) == "EXDEV"
            if (atomicMove && crossDevice)
                throw AtomicMoveNotSupportedException(source, target, error.reason, error.cause ?: error)
            if (atomicMove || !crossDevice) throw error
            if (sourceInfo.directory && streamDirectory(source).use { it.hasNext() })
                throw FileSystemException(source, target, "Cannot move a non-empty directory across filesystems")
            copyTo(source, target, overwrite, copyAttributes = true, followLinks = false)
            deleteExisting(source)
        }
        return path(target.pathString)
    }

    override fun fileStore(path: Path): FileStore {
        assertFS(path)
        return NodeFileStore(realPath(path, true))
    }

    override fun newInputStream(path: Path): SyncInputStream {
        assertFS(path)
        return NodeInputStream(path, nodeCall(path) { nodeFs().openSync(path.nodeName, "r") })
    }

    override fun readBytes(path: Path): ByteArray {
        assertFS(path)
        return nodeCall(path) { nodeFs().readFileSync(path.nodeName) }.toByteArray()
    }

    override fun writeBytes(path: Path, bytes: ByteArray, mode: FileWriteMode, creation: FileCreation): SyncPath {
        assertFS(path)
        val data = bytes.toNodeBuffer()
        nodeCall(path) {
            nodeFs().writeFileSync(path.nodeName, data, nodeWriteOptions(nodeWriteFlags(mode, creation)))
        }
        return path(path.pathString)
    }

    override fun newOutputStream(path: Path, mode: FileWriteMode, creation: FileCreation): SyncOutputStream {
        assertFS(path)
        return NodeOutputStream(path, nodeCall(path) {
            nodeFs().openSync(path.nodeName, nodeWriteFlags(mode, creation))
        })
    }
}

interface AsyncNodeJsFileSystem: NodeJsFileSystem, AsyncUserFileSystem {
    override suspend fun readBytes(path: Path): ByteArray {
        assertFS(path)
        val controller = nodeAbortController()
        return nodeCall(path) {
            nodeFs().promises.readFile(path.nodeName, nodeFileOptions(controller.signal)).awaitFile(controller)
        }.toByteArray()
    }

    override suspend fun writeBytes(
        path: Path,
        bytes: ByteArray,
        mode: FileWriteMode,
        creation: FileCreation
    ): AsyncPath {
        assertFS(path)
        val data = bytes.toNodeBuffer()
        val controller = nodeAbortController()
        nodeCall(path) {
            nodeFs().promises.writeFile(path.nodeName, data,
                nodeFileOptions(controller.signal, nodeWriteFlags(mode, creation))).awaitFile(controller)
        }
        return path(path.pathString)
    }
}

// Windows

interface WindowsNodeJsFileSystem: NodeJsFileSystem, WindowsFileSystem {
    override fun asSync(): SyncWindowsNodeJsFileSystem
    override fun asAsync(): AsyncWindowsNodeJsFileSystem
}

class SyncWindowsNodeJsFileSystem: WindowsNodeJsFileSystem, SyncNodeJsFileSystem, SyncWindowsFileSystem {
    private val async by lazy { AsyncWindowsNodeJsFileSystem(this) }
    override val identity: Any = Any()

    override fun asSync(): SyncWindowsNodeJsFileSystem = this
    override fun asAsync(): AsyncWindowsNodeJsFileSystem = async
}

class AsyncWindowsNodeJsFileSystem(
    override val wrapped: SyncWindowsNodeJsFileSystem
):
    WindowsNodeJsFileSystem,
    AsyncNodeJsFileSystem,
    AsyncWindowsFileSystem,
    WrappedAsyncFileSystem,
    WrappedAsyncUserFileSystem
{
    override suspend fun readBytes(path: Path): ByteArray = super<AsyncNodeJsFileSystem>.readBytes(path)
    override suspend fun writeBytes(
        path: Path,
        bytes: ByteArray,
        mode: FileWriteMode,
        creation: FileCreation
    ): AsyncPath = super<AsyncNodeJsFileSystem>.writeBytes(path, bytes, mode, creation)

    override fun absolute(path: Path): AsyncPath = super<WrappedAsyncFileSystem>.absolute(path)

    override fun asSync(): SyncWindowsNodeJsFileSystem = wrapped
    override fun asAsync(): AsyncWindowsNodeJsFileSystem = this
}

// Unix

interface UnixNodeJsFileSystem: NodeJsFileSystem, UnixFileSystem {
    override fun asSync(): SyncUnixNodeJsFileSystem
    override fun asAsync(): AsyncUnixNodeJsFileSystem
}

class SyncUnixNodeJsFileSystem: UnixNodeJsFileSystem, SyncNodeJsFileSystem, SyncUnixFileSystem {
    private val async by lazy { AsyncUnixNodeJsFileSystem(this) }
    override val identity: Any = Any()

    override fun asSync(): SyncUnixNodeJsFileSystem = this
    override fun asAsync(): AsyncUnixNodeJsFileSystem = async
}

class AsyncUnixNodeJsFileSystem(
    override val wrapped: SyncUnixNodeJsFileSystem
):
    UnixNodeJsFileSystem,
    AsyncNodeJsFileSystem,
    AsyncUnixFileSystem,
    WrappedAsyncFileSystem,
    WrappedAsyncUserFileSystem
{
    override suspend fun readBytes(path: Path): ByteArray = super<AsyncNodeJsFileSystem>.readBytes(path)
    override suspend fun writeBytes(
        path: Path,
        bytes: ByteArray,
        mode: FileWriteMode,
        creation: FileCreation
    ): AsyncPath = super<AsyncNodeJsFileSystem>.writeBytes(path, bytes, mode, creation)

    override fun absolute(path: Path): AsyncPath = super<WrappedAsyncFileSystem>.absolute(path)

    override fun asSync(): SyncUnixNodeJsFileSystem = wrapped
    override fun asAsync(): AsyncUnixNodeJsFileSystem = this
}
