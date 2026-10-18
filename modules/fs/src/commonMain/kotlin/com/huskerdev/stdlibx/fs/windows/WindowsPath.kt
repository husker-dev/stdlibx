package com.huskerdev.stdlibx.fs.windows

import com.huskerdev.stdlibx.fs.*
import kotlinx.serialization.Serializable

/**
 * A Windows-style path independent of the host OS. Ordinary paths accept either separator;
 * extended paths retain their original spelling. Device paths and alternate data streams are unsupported.
 * Path comparisons ignore case and do not check whether entries refer to the same file.
 */
@Serializable(with = WindowsPathSerializer::class)
open class WindowsPath(
    path: String,
    override val fileSystem: WindowsFileSystem
): Path {
    private sealed interface Root {
        val text: String
        val absolute: Boolean
        val extended: Boolean get() = false
        val driveLetter: Char? get() = null
        val anchored: Boolean get() = absolute || this === CurrentDrive

        data object Relative : Root {
            override val text: String = ""
            override val absolute: Boolean = false
        }

        data object CurrentDrive : Root {
            override val text: String = "\\"
            override val absolute: Boolean = false
        }

        data class Drive(override val driveLetter: Char, override val absolute: Boolean) : Root {
            override val text: String = "$driveLetter:" + if (absolute) "\\" else ""
        }

        data class ExtendedDrive(override val driveLetter: Char) : Root {
            override val text: String = "\\\\?\\$driveLetter:\\"
            override val absolute: Boolean = true
            override val extended: Boolean = true
        }

        data class Unc(val server: String, val share: String, val extendedPrefix: String? = null) : Root {
            override val text: String = "${extendedPrefix ?: "\\\\"}$server\\$share\\"
            override val absolute: Boolean = true
            override val extended: Boolean = extendedPrefix != null
        }

        data class Volume(val guid: String, val prefix: String) : Root {
            override val text: String = "\\\\?\\$prefix{$guid}\\"
            override val absolute: Boolean = true
            override val extended: Boolean = true
        }

        fun rooted(): Root = when (this) {
            Relative -> CurrentDrive
            is Drive -> if (absolute) this else copy(absolute = true)
            else -> this
        }

        fun sameAs(other: Root): Boolean = when (this) {
            Relative, CurrentDrive -> this === other
            is Drive -> other is Drive && absolute == other.absolute &&
                driveLetter.uppercaseChar() == other.driveLetter.uppercaseChar()
            is ExtendedDrive -> other is ExtendedDrive &&
                driveLetter.uppercaseChar() == other.driveLetter.uppercaseChar()
            is Unc -> other is Unc && extended == other.extended &&
                equalName(server, other.server) && equalName(share, other.share)
            is Volume -> other is Volume && equalName(guid, other.guid)
        }
    }

    private val pathRoot: Root
    override lateinit var pathString: String
    override lateinit var names: List<String>

    init {
        require('\u0000' !in path) { "Path contains NUL" }
        val canonical = path.replace('/', '\\')
        require(!canonical.startsWith("\\\\.\\")) { "Windows device paths are unsupported" }

        if (canonical.startsWith("\\\\?\\")) {
            require(path.startsWith("\\\\?\\") && '/' !in path) {
                "Extended paths require backslash separators"
            }
            pathRoot = when {
                isDrive(path, 4) && path.getOrNull(6) == '\\' -> Root.ExtendedDrive(path[4])
                path.regionMatches(4, "UNC\\", 0, 4, ignoreCase = true) -> {
                    val serverEnd = path.indexOf('\\', 8)
                    require(serverEnd > 8) {
                        "Extended UNC paths require a server and a share"
                    }
                    val shareEnd = path.indexOf('\\', serverEnd + 1)
                        .let { if (it < 0) path.length else it }
                    val server = path.substring(8, serverEnd)
                    val share = path.substring(serverEnd + 1, shareEnd)
                    validateUncRoot(server, share, true)
                    Root.Unc(server, share, extendedPrefix = path.substring(0, 8))
                }
                else -> {
                    val end = path.indexOf('\\', 4)
                    require(end > 4 && volumeName.matches(path.substring(4, end))) {
                        "Unsupported extended Windows path"
                    }
                    val volume = path.substring(4, end)
                    Root.Volume(volume.substring(7, volume.lastIndex), volume.substring(0, 6))
                }
            }
            val rootLength = pathRoot.text.length
            val tail = if (path.length < rootLength)
                "" else path.substring(rootLength)
            require(!tail.startsWith('\\')) {
                "Repeated separators in extended paths are unsupported"
            }
            names = tail.removeSuffix("\\")
                .takeIf { it.isNotEmpty() }
                ?.split('\\')
                ?: emptyList()
            require(names.none { it.isEmpty() }) {
                "Repeated separators in extended paths are unsupported"
            }
        } else if (canonical.startsWith("\\\\")) {
            val parts = canonical.substring(2)
                .split('\\')
                .filter { it.isNotEmpty() }
            require(parts.size >= 2) {
                "UNC paths require a server and a share"
            }
            validateUncRoot(parts[0], parts[1], false)
            pathRoot = Root.Unc(parts[0], parts[1])
            names = parts.drop(2)
        } else {
            var start = 0
            pathRoot = when {
                isDrive(canonical) -> {
                    val absolute = canonical.getOrNull(2) == '\\'
                    start = if (absolute) 3 else 2
                    Root.Drive(canonical[0], absolute)
                }
                canonical.startsWith('\\') -> {
                    start = 1
                    Root.CurrentDrive
                }
                else -> Root.Relative
            }
            names = canonical.substring(start)
                .split('\\')
                .filter { it.isNotEmpty() }
        }
        names.forEach {
            validateName(it, pathRoot.extended)
        }
        pathString = if (pathRoot.extended) {
            if (path.length < pathRoot.text.length)
                pathRoot.text else path
        } else pathRoot.text + names.joinToString("\\")
    }

    private val comparisonText = fold(pathString)
    override val isAbsolute: Boolean = pathRoot.absolute

    override val invariantSeparatorsPath: String
        get() = pathString.replace('\\', '/')

    override val name: String
        get() = names.lastOrNull() ?: ""

    override val extension: String
        get() = name.substringAfterLast('.', "")

    override val nameWithoutExtension: String
        get() = name.substringBeforeLast('.')

    override val isEmpty: Boolean
        get() = pathString.isEmpty()

    override val root: WindowsPath?
        get() = if(pathRoot.text.isNotEmpty())
            fileSystem.path(pathRoot.text) else null

    override val parent: WindowsPath?
        get() = when {
            names.isEmpty() -> null
            names.size == 1 -> root
            else -> fromNames(names.dropLast(1))
        }

    private fun fromNames(names: List<String>): WindowsPath =
        fileSystem.path(pathRoot.text + names.joinToString("\\"))

    override fun normalize(): WindowsPath {
        if (pathRoot.extended) return this
        val result = mutableListOf<String>()
        for (name in names) {
            when (name) {
                "." -> Unit
                ".." -> when {
                    result.isNotEmpty() && result.last() != ".." -> result.removeLast()
                    !pathRoot.anchored -> result.add(name)
                    else -> Unit
                }
                else -> result.add(name)
            }
        }
        return if (result == names) this else fromNames(result)
    }

    override fun startsWith(other: Path): Boolean {
        val other = checked(other)
        if (!sameRoot(other) || other.names.size > names.size) return false
        return other.names.indices.all { equalName(names[it], other.names[it]) }
    }

    override fun endsWith(other: Path): Boolean {
        val other = checked(other)
        if (other.pathRoot !== Root.Relative) return this == other
        if (other.names.size > names.size) return false
        val start = names.size - other.names.size
        return other.names.indices.all { equalName(names[start + it], other.names[it]) }
    }

    override fun resolve(other: Path): WindowsPath {
        val other = checked(other)
        if (other.isEmpty) return this
        if (other.isAbsolute || isEmpty) return fileSystem.path(other.pathString)
        return when (val otherRoot = other.pathRoot) {
            Root.Relative -> append(other.names)
            Root.CurrentDrive ->
                fileSystem.path(pathRoot.rooted().text + other.names.joinToString("\\"))
            is Root.Drive -> {
                val drive = pathRoot.driveLetter
                if (drive != null && drive.uppercaseChar() == otherRoot.driveLetter.uppercaseChar())
                    append(other.names)
                else fileSystem.path(other.pathString)
            }
            else -> fileSystem.path(other.pathString)
        }
    }

    private fun append(names: List<String>): WindowsPath {
        if (names.isEmpty()) return this
        val separator = if (pathString.endsWith('\\') || pathString == pathRoot.text) "" else "\\"
        return fileSystem.path(pathString + separator + names.joinToString("\\"))
    }

    override fun relativeTo(other: Path): WindowsPath {
        val base = checked(other).normalize()
        val target = normalize()
        require(target.sameRoot(base)) { "Paths have different roots" }
        var common = 0
        while (
            common < minOf(base.names.size, target.names.size) &&
            equalName(base.names[common], target.names[common])
        ) common++
        require(base.names.drop(common).none { it == ".." }) {
            "Base has unresolved parent components"
        }
        require(!pathRoot.extended || common == base.names.size) {
            "Extended paths cannot be relativized using parent components"
        }
        val result = List(base.names.size - common) { ".." } + target.names.drop(common)
        // A relative Windows path cannot represent literal extended components such as trailing spaces.
        val relative = fileSystem.path(result.joinToString("\\"))
        require(!pathRoot.extended || relative.names.none { it == "." || it == ".." }) {
            "Extended dot components cannot be represented by an ordinary relative path"
        }
        return relative
    }

    private fun sameRoot(other: WindowsPath): Boolean = pathRoot.sameAs(other.pathRoot)

    private fun checked(other: Path): WindowsPath {
        require(other is WindowsPath) { "Expected a Windows path" }
        require(other.fileSystem.identity === fileSystem.identity) { "Different file systems" }
        return other
    }

    override fun asSync(): SyncWindowsPath? =
        fileSystem.asSync()?.path(pathString) as SyncWindowsPath?

    override fun asAsync(): AsyncWindowsPath =
        fileSystem.asAsync().path(pathString) as AsyncWindowsPath

    override fun equals(other: Any?): Boolean =
        other is WindowsPath && fileSystem.identity === other.fileSystem.identity && comparisonText == other.comparisonText

    override fun hashCode(): Int =
        31 * fileSystem.identity.hashCode() + comparisonText.hashCode()

    override fun toString(): String = pathString
}

/** A Windows-style path with blocking file operations through [fileSystem]. */
@Serializable(with = SyncWindowsPathSerializer::class)
class SyncWindowsPath(
    path: String,
    override val fileSystem: SyncWindowsFileSystem
): WindowsPath(path, fileSystem), SyncPath {
    override fun asSync(): SyncWindowsPath = this

    override val parent: SyncWindowsPath?
        get() = super.parent as SyncWindowsPath?

    override val root: SyncWindowsPath?
        get() = super.root as SyncWindowsPath?

    override fun relativeTo(other: Path): SyncWindowsPath = super<WindowsPath>.relativeTo(other) as SyncWindowsPath
    override fun normalize(): SyncWindowsPath = super.normalize() as SyncWindowsPath
    override fun resolve(other: Path): SyncWindowsPath = super<WindowsPath>.resolve(other) as SyncWindowsPath
}

/** A Windows-style path with suspending file operations through [fileSystem]. */
@Serializable(with = AsyncWindowsPathSerializer::class)
class AsyncWindowsPath(
    path: String,
    override val fileSystem: AsyncWindowsFileSystem
): WindowsPath(path, fileSystem), AsyncPath {
    override fun asAsync(): AsyncWindowsPath = this

    override val parent: AsyncWindowsPath?
        get() = super.parent as AsyncWindowsPath?

    override val root: AsyncWindowsPath?
        get() = super.root as AsyncWindowsPath?

    override fun relativeTo(other: Path): AsyncWindowsPath = super<WindowsPath>.relativeTo(other) as AsyncWindowsPath
    override fun normalize(): AsyncWindowsPath = super.normalize() as AsyncWindowsPath
    override fun resolve(other: Path): AsyncWindowsPath = super<WindowsPath>.resolve(other) as AsyncWindowsPath
}

// Utils

private val volumeName = Regex("Volume\\{[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\}", RegexOption.IGNORE_CASE)

private fun fold(value: String): String = buildString(value.length) {
    value.forEach { append(it.uppercaseChar()) }
}

private fun equalName(first: String, second: String): Boolean =
    first.length == second.length && first.indices.all {
        first[it].uppercaseChar() == second[it].uppercaseChar()
    }

private fun isDrive(value: String, offset: Int = 0): Boolean = value.length >= offset + 2 &&
    (value[offset] in 'A'..'Z' || value[offset] in 'a'..'z') && value[offset + 1] == ':'

private fun validateName(name: String, extended: Boolean) {
    require(name.none { it < ' ' || it in "<>:\"|?*/\\" }) { "Invalid Windows path component: $name" }
    require(extended || !name.endsWith(' ')) { "Windows path components cannot end with a space: $name" }
}

private fun validateUncRoot(server: String, share: String, extended: Boolean) {
    validateName(server, extended)
    validateName(share, extended)
    require(server.isNotEmpty() && share.isNotEmpty() &&
        server != "." && server != ".." && share != "." && share != "..") {
        "UNC paths require a server and a share"
    }
}
