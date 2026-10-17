@file:Suppress("NewApi")

package com.huskerdev.stdlibx.io.fs

import com.huskerdev.stdlibx.io.*
import java.nio.file.*

interface NioFileSystem: FileSystem {
    val nioFileSystem: java.nio.file.FileSystem
}

interface SyncNioFileSystem: NioFileSystem, SyncFileSystem {

    override val identity: Any
        get() = nioFileSystem

    override val separator: String
        get() = nioFileSystem.separator

    override fun rootDirectories(): Iterable<SyncPath> =
        nioCall { nioFileSystem.rootDirectories.map(::fromNio) }

    override fun fileStores(): Iterable<FileStore> =
        nioCall { nioFileSystem.fileStores.map(::fromNio) }

    override fun absolute(path: Path): SyncPath =
        nioCall(path) { fromNio(toNio(path).toAbsolutePath()) }

    override fun realPath(path: Path, followLinks: Boolean): SyncPath =
        nioCall(path) { fromNio(toNio(path).toRealPath(*linkOption(followLinks))) }

    override fun isExecutable(path: Path): Boolean =
        Files.isExecutable(toNio(path))

    override fun isReadable(path: Path): Boolean =
        Files.isReadable(toNio(path))

    override fun isWritable(path: Path): Boolean =
        Files.isWritable(toNio(path))

    override fun readAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot =
        nioCall(path) { readNioAttributes(path, followLinks) }

    override fun writeAttributes(path: Path, attributes: FileAttributesChanges, followLinks: Boolean) =
        nioCall(path) { writeNioAttributes(path, attributes, followLinks) }

    override fun mimeType(path: Path): String =
        nioCall(path) { Files.probeContentType(toNio(path))?.takeIf { it.isNotBlank() }
            ?: fallbackMimeType(path.extension) }

    override fun readBytes(path: Path): ByteArray = nioCall(path) {
        Files.newByteChannel(toNio(path)).use { channel ->
            readWholeFileBytes(path, channel.size()) { bytes, offset, length ->
                channel.read(java.nio.ByteBuffer.wrap(bytes, offset, length)).coerceAtLeast(0)
            }
        }
    }

    override fun writeBytes(
        path: Path,
        bytes: ByteArray,
        mode: FileWriteMode,
        creation: FileCreation
    ): SyncPath = nioCall(path) {
        fromNio(Files.write(toNio(path), bytes, *toNio(mode, creation)))
    }

    override fun streamDirectory(directory: Path): SyncStream<SyncPath> =
        NioDirectoryIterator(this, directory)

    override fun copyTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        copyAttributes: Boolean,
        followLinks: Boolean
    ) = nioCall(target) {
        fromNio(Files.copy(
            toNio(source),
            toNio(target),
            *buildList<CopyOption> {
                if(overwrite)      add(StandardCopyOption.REPLACE_EXISTING)
                if(copyAttributes) add(StandardCopyOption.COPY_ATTRIBUTES)
                if(!followLinks)   add(LinkOption.NOFOLLOW_LINKS)
            }.toTypedArray()
        ))
    }

    override fun moveTo(
        source: Path,
        target: Path,
        overwrite: Boolean,
        atomicMove: Boolean
    ) = nioCall(target) {
        fromNio(Files.move(
            toNio(source),
            toNio(target),
            *buildList<CopyOption> {
                if(overwrite && !atomicMove) add(StandardCopyOption.REPLACE_EXISTING)
                if(atomicMove) add(StandardCopyOption.ATOMIC_MOVE)
            }.toTypedArray()
        ))
    }

    override fun exists(path: Path, followLinks: Boolean): Boolean =
        Files.exists(toNio(path), *linkOption(followLinks))

    override fun notExists(path: Path, followLinks: Boolean): Boolean =
        Files.notExists(toNio(path), *linkOption(followLinks))

    override fun isSameFiles(path: Path, other: Path): Boolean =
        nioCall(path) { Files.isSameFile(toNio(path), toNio(other)) }

    override fun readSymbolicLink(path: Path): SyncPath =
        nioCall(path) { fromNio(Files.readSymbolicLink(toNio(path))) }

    override fun createSymbolicLink(path: Path, target: Path): SyncPath =
        nioCall(path) { fromNio(Files.createSymbolicLink(toNio(path), toNio(target))) }

    override fun deleteExisting(path: Path) =
        nioCall(path) { Files.delete(toNio(path)) }

    override fun deleteIfExists(path: Path): Boolean =
        nioCall(path) { Files.deleteIfExists(toNio(path)) }

    override fun createDirectory(path: Path, permissions: Set<PosixFilePermission>?): SyncPath =
        nioCall(path) { fromNio(Files.createDirectory(toNio(path), *toNio(permissions))) }

    override fun createDirectories(path: Path, permissions: Set<PosixFilePermission>?): SyncPath =
        nioCall(path) { fromNio(Files.createDirectories(toNio(path), *toNio(permissions))) }

    override fun createFile(path: Path, permissions: Set<PosixFilePermission>?): SyncPath =
        nioCall(path) { fromNio(Files.createFile(toNio(path), *toNio(permissions))) }

    override fun fileStore(path: Path): FileStore =
        nioCall(path) { fromNio(Files.getFileStore(toNio(path))) }

    override fun newInputStream(path: Path): SyncInputStream =
        nioCall(path) { Files.newInputStream(toNio(path)).wrap(path) }

    override fun newOutputStream(
        path: Path,
        mode: FileWriteMode,
        creation: FileCreation
    ): SyncOutputStream = nioCall(path) {
        Files.newOutputStream(toNio(path), *toNio(mode, creation)).wrap(path)
    }

    override fun asSync(): SyncNioFileSystem?
    override fun asAsync(): AsyncNioFileSystem
}

interface AsyncNioFileSystem: NioFileSystem, WrappedAsyncFileSystem
