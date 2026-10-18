package com.huskerdev.stdlibx.fs.unix

import com.huskerdev.stdlibx.fs.*
import kotlinx.serialization.Serializable

/** A Unix-style path independent of the host OS. Path comparisons are case-sensitive and do not access files. */
@Serializable(with = UnixPathSerializer::class)
open class UnixPath(
    path: String,
    override val fileSystem: UnixFileSystem
): Path {
    init {
        if(path.contains('\u0000'))
            throw IllegalArgumentException()
    }
    
    override val pathString: String = when (path) {
        "/", "" -> path
        else -> buildString {
            path.split("/").forEachIndexed { index, it ->
                if (it.isEmpty())
                    return@forEachIndexed

                if (index != 0 || path[0] == '/')
                    append("/")
                append(it)
            }
            if(isEmpty() && path[0] == '/') // example: ///
                append("/")
        }
    }

    override val names: List<String> = when {
        pathString.isEmpty() || pathString == "/" -> emptyList()
        pathString[0] == '/' -> pathString.substring(1).split("/")
        else -> pathString.split("/")
    }

    override val isAbsolute: Boolean
        get() = pathString.isNotEmpty() && pathString[0] == '/'

    override val invariantSeparatorsPath: String = pathString

    override val name: String
        get() = names.lastOrNull() ?: ""

    override val extension: String
        get() = name.substringAfterLast(".", missingDelimiterValue = "")

    override val nameWithoutExtension: String
        get() = name.substringBeforeLast(".")

    override val parent: UnixPath?
        get() = when {
            names.isEmpty() -> null
            names.size == 1 -> when {
                pathString[0] == '/' -> fileSystem.path("/")
                else -> null
            }
            else -> fileSystem.path(pathString.substring(0, pathString.length - names.last().length))
        }

    override val root: UnixPath?
        get() = if(isAbsolute) fileSystem.path("/") else null

    override val isEmpty: Boolean
        get() = pathString.isEmpty()

    override fun normalize(): UnixPath {
        val normalizedPath = buildList<String>(names.size) {
            names.forEach {
                when (it) {
                    "." -> Unit
                    ".." -> when {
                        isAbsolute && isEmpty() -> Unit
                        isNotEmpty() && last() != ".." -> removeLast()
                        else -> add(it)
                    }
                    else -> add(it)
                }
            }
        }.joinToString(prefix = if(isAbsolute) "/" else "", separator = "/")
        return fileSystem.path(normalizedPath)
    }

    override fun startsWith(other: Path): Boolean {
        other.asUnixPath()

        // true: a/b <-> ''
        if(other.isEmpty && !isAbsolute)
            return true

        // false: /a <-> a | a <-> /a
        if(isAbsolute != other.isAbsolute)
            return false

        // false: a <-> a/b
        if(other.names.size > names.size)
            return false

        // Compare names
        other.names.forEachIndexed { index, it ->
            if(names[index] != it)
                return false
        }
        return true
    }

    override fun endsWith(other: Path): Boolean {
        other.asUnixPath()
        if (other.isAbsolute) return this == other

        // true: a/b <-> ''
        if(other.isEmpty)
            return true

        // false: a <-> /a
        if(!isAbsolute && other.isAbsolute)
            return false

        // false: a <-> a/b
        if(other.names.size > names.size)
            return false

        // Compare names
        val relIndex = names.size - other.names.size
        other.names.forEachIndexed { index, it ->
            if(names[relIndex + index] != it)
                return false
        }
        return true
    }

    override fun resolve(other: Path): UnixPath {
        val other = other.asUnixPath()
        val otherPathString = other.pathString
        return when {
            otherPathString.isEmpty() -> this
            otherPathString[0] == '/' -> fileSystem.path(other.pathString)
            pathString.isEmpty() -> fileSystem.path(other.pathString)
            else -> fileSystem.path("$pathString/${otherPathString}")
        }
    }

    override fun relativeTo(other: Path): UnixPath {
        val base = other.asUnixPath().normalize()
        val target = normalize()

        require(base.isAbsolute == target.isAbsolute) {
            "Paths have different roots"
        }

        var common = 0
        while (
            common < minOf(base.names.size, target.names.size) &&
            base.names[common] == target.names[common]
        ) common++

        require(base.names.drop(common).none { it == ".." }) {
            "Base has unresolved parent components"
        }

        return fileSystem.path(buildList {
            repeat(base.names.size - common) { add("..") }
            addAll(target.names.drop(common))
        }.joinToString("/"))
    }

    override fun asSync(): SyncUnixPath? =
        fileSystem.asSync()?.path(pathString) as SyncUnixPath?

    override fun asAsync(): AsyncUnixPath =
        fileSystem.asAsync().path(pathString) as AsyncUnixPath

    private fun Path?.asUnixPath(): UnixPath {
        if(this == null)
            throw NullPointerException()
        this as? UnixPath ?: throw IllegalArgumentException()
        require(fileSystem.identity === this@UnixPath.fileSystem.identity) {
            "Different file systems"
        }
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnixPath) return false
        if (fileSystem.identity !== other.fileSystem.identity) return false
        if (pathString != other.pathString) return false
        return true
    }

    override fun hashCode(): Int {
        var result = fileSystem.identity.hashCode()
        result = 31 * result + pathString.hashCode()
        return result
    }

    override fun toString(): String = pathString
}

/** A Unix-style path with blocking file operations through [fileSystem]. */
@Serializable(with = SyncUnixPathSerializer::class)
class SyncUnixPath(
    path: String,
    override val fileSystem: SyncUnixFileSystem
): UnixPath(path, fileSystem), SyncPath {
    override fun asSync(): SyncUnixPath = this

    override val parent: SyncUnixPath?
        get() = super.parent as SyncUnixPath?

    override val root: SyncUnixPath?
        get() = super.root as SyncUnixPath?

    override fun relativeTo(other: Path): SyncUnixPath = super<UnixPath>.relativeTo(other) as SyncUnixPath
    override fun normalize(): SyncUnixPath = super.normalize() as SyncUnixPath
    override fun resolve(other: Path): SyncUnixPath = super<UnixPath>.resolve(other) as SyncUnixPath
}

/** A Unix-style path with suspending file operations through [fileSystem]. */
@Serializable(with = AsyncUnixPathSerializer::class)
class AsyncUnixPath(
    path: String,
    override val fileSystem: AsyncUnixFileSystem
): UnixPath(path, fileSystem), AsyncPath {
    override fun asAsync(): AsyncUnixPath = this

    override val parent: AsyncUnixPath?
        get() = super.parent as AsyncUnixPath?

    override val root: AsyncUnixPath?
        get() = super.root as AsyncUnixPath?

    override fun relativeTo(other: Path): AsyncUnixPath = super<UnixPath>.relativeTo(other) as AsyncUnixPath
    override fun normalize(): AsyncUnixPath = super.normalize() as AsyncUnixPath
    override fun resolve(other: Path): AsyncUnixPath = super<UnixPath>.resolve(other) as AsyncUnixPath
}
