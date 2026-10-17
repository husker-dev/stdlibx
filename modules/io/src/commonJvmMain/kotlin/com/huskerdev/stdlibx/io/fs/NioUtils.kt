@file:Suppress("NewApi")

package com.huskerdev.stdlibx.io.fs

import com.huskerdev.stdlibx.OS
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant


internal fun linkOption(followLinks: Boolean): Array<LinkOption> =
    if(followLinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)

internal fun FileSystem.fromNio(path: java.nio.file.Path) =
    path(path.toString()) as SyncPath

internal fun NioFileSystem.toNio(path: Path): java.nio.file.Path {
    assertFS(path)
    return nioFileSystem.getPath(path.pathString)
}

internal fun toNio(permissions: Set<PosixFilePermission>?): Array<FileAttribute<*>> {
    return if (permissions != null) {
        if (OS.isWindows) throw UnsupportedOperationException("POSIX permissions are not supported on Windows")
        arrayOf(PosixFilePermissions.asFileAttribute(
            permissions.map { java.nio.file.attribute.PosixFilePermission.entries[it.ordinal] }.toSet()
        ))
    } else emptyArray()
}

internal fun fromNio(nio: java.nio.file.FileStore): FileStore = object: FileStore {
    override val name: String = nioCall { nio.name() }
    override val type: String? = nioCall { nio.type() }
    override val isReadonly: Boolean = nioCall { nio.isReadOnly }
    override val totalSpace: Long = nioCall { nio.totalSpace }
    override val usableSpace: Long = nioCall { nio.usableSpace }
    override val unallocatedSpace: Long = nioCall { nio.unallocatedSpace }
}

internal fun toNio(mode: FileWriteMode, creation: FileCreation) = buildList {
    add(StandardOpenOption.WRITE)

    when (creation) {
        FileCreation.OPEN_EXISTING -> Unit
        FileCreation.CREATE_IF_MISSING -> add(StandardOpenOption.CREATE)
        FileCreation.CREATE_NEW -> add(StandardOpenOption.CREATE_NEW)
    }
    when (mode) {
        FileWriteMode.FROM_START -> Unit
        FileWriteMode.TRUNCATE -> add(StandardOpenOption.TRUNCATE_EXISTING)
        FileWriteMode.APPEND -> add(StandardOpenOption.APPEND)
    }
}.toTypedArray()

internal fun NioFileSystem.readNioAttributes(path: Path, followLinks: Boolean): FileAttributesSnapshot {
    val nioPath = toNio(path)
    val linkOptions = linkOption(followLinks)

    val dosAttributes = try {
        Files.readAttributes(nioPath, DosFileAttributes::class.java, *linkOptions)
    } catch (_: UnsupportedOperationException) { null }

    val posixAttributes = try {
        Files.readAttributes(nioPath, PosixFileAttributes::class.java, *linkOptions)
    } catch (_: UnsupportedOperationException) { null }
    val posixPermissions = posixAttributes?.permissions()

    val basicAttributes: BasicFileAttributes = dosAttributes ?: posixAttributes
        ?: Files.readAttributes(nioPath, BasicFileAttributes::class.java, *linkOptions)

    return FileAttributesSnapshot(
        isRegularFile = basicAttributes.isRegularFile,
        isDirectory = basicAttributes.isDirectory,
        isSymbolicLink = basicAttributes.isSymbolicLink,
        size = basicAttributes.size(),
        isHidden = when {
            dosAttributes != null -> dosAttributes.isHidden
            posixPermissions != null -> path.name.startsWith(".")
            else -> null
        },
        lastModifiedTime = basicAttributes.lastModifiedTime()?.toInstant()?.toKotlinInstant(),
        lastAccessTime = basicAttributes.lastAccessTime()?.toInstant()?.toKotlinInstant(),
        creationTime = basicAttributes.creationTime()?.toInstant()?.toKotlinInstant(),
        dosIsReadOnly = when {
            dosAttributes != null -> dosAttributes.isReadOnly
            else -> null
        },
        posixPermissions = when {
            posixPermissions != null -> posixPermissions.map { PosixFilePermission.entries[it.ordinal] }.toSet()
            else -> null
        }
    )
}

internal fun NioFileSystem.writeNioAttributes(path: Path, attributes: FileAttributesChanges, followLinks: Boolean) {
    val linkOptions = linkOption(followLinks)
    val nioPath = toNio(path)

    val basicAttributes = Files.getFileAttributeView(nioPath, BasicFileAttributeView::class.java, *linkOptions)
    val posixAttributes = Files.getFileAttributeView(nioPath, PosixFileAttributeView::class.java, *linkOptions)
    val dosAttributes = Files.getFileAttributeView(nioPath, DosFileAttributeView::class.java, *linkOptions)

    if((attributes.dosIsReadOnly != null || attributes.isHidden != null) && dosAttributes == null)
        throw UnsupportedOperationException()

    if(attributes.posixPermissions != null && posixAttributes == null)
        throw UnsupportedOperationException()

    if(attributes.isHidden != null)
        dosAttributes!!.setHidden(attributes.isHidden)

    if(attributes.dosIsReadOnly != null)
        dosAttributes!!.setReadOnly(attributes.dosIsReadOnly)

    if(attributes.lastModifiedTime != null ||
        attributes.lastAccessTime != null ||
        attributes.creationTime != null
    ) basicAttributes.setTimes(
        attributes.lastModifiedTime?.let { FileTime.from(it.toJavaInstant()) },
        attributes.lastAccessTime?.let { FileTime.from(it.toJavaInstant()) },
        attributes.creationTime?.let { FileTime.from(it.toJavaInstant()) }
    )

    if(attributes.posixPermissions != null) {
        posixAttributes!!.setPermissions(attributes.posixPermissions.map {
            java.nio.file.attribute.PosixFilePermission.entries[it.ordinal]
        }.toSet())
    }
}
