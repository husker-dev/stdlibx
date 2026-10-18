@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import kotlinx.cinterop.toKString
import platform.posix.fnmatch
import platform.posix.getenv

internal val linuxMimeGlobs: UnixMimeGlobs by lazy {
    val dataHome = unixEnvironmentPath("XDG_DATA_HOME")
        ?: "${nativeHomeDirectory()}/.local/share"
    val dataDirs = getenv("XDG_DATA_DIRS")?.toKString()?.takeIf { it.isNotEmpty() }
        ?.split(':')?.filter { it.startsWith('/') }
        ?: listOf("/usr/local/share", "/usr/share")
    val directories = (listOfNotNull(dataHome) + dataDirs).distinct().asReversed()
    parseUnixMimeGlobs(directories.mapNotNull { readOptionalMimeDatabase("$it/mime/globs2") })
}

internal class UnixMimeGlobs(private val rules: List<UnixMimeGlobRule>) {
    fun find(fileName: String): String? {
        if (fileName.isBlank()) return null
        val lowercaseName = fileName.lowercase()
        return rules.filter { it.matches(fileName, lowercaseName) }
            .maxWithOrNull(compareBy<UnixMimeGlobRule> { it.isLiteral }
                .thenBy { it.weight }
                .thenBy { it.pattern.length })
            ?.mimeType
    }
}

internal data class UnixMimeGlobRule(
    val weight: Int,
    val mimeType: String,
    val pattern: String,
    val caseSensitive: Boolean
) {
    val isLiteral: Boolean = pattern.none { it == '*' || it == '?' || it == '[' }
    private val matchPattern = if (caseSensitive) pattern else pattern.lowercase()

    fun matches(fileName: String, lowercaseName: String): Boolean =
        fnmatch(matchPattern, if (caseSensitive) fileName else lowercaseName, 0) == 0
}

// Databases are supplied in ascending priority, so user rules can override system rules.
// This is filename-based detection; it does not inspect the file's contents.
internal fun parseUnixMimeGlobs(databases: Iterable<String>): UnixMimeGlobs {
    val rules = mutableListOf<UnixMimeGlobRule>()
    for (database in databases) {
        val current = mutableListOf<UnixMimeGlobRule>()
        for (line in database.lineSequence()) {
            if (line.isBlank() || line.startsWith('#')) continue
            val fields = line.split(':')
            if (fields.size < 3) continue
            val mimeType = validMimeType(fields[1]) ?: continue
            val pattern = fields[2]
            if (pattern == "__NOGLOBS__") {
                rules.removeAll { it.mimeType == mimeType }
                current.removeAll { it.mimeType == mimeType }
                continue
            }
            val weight = fields[0].toIntOrNull()?.takeIf { it in 0..100 } ?: continue
            if (pattern.isEmpty()) continue
            val flags = fields.getOrNull(3)?.split(',').orEmpty()
            current += UnixMimeGlobRule(weight, mimeType, pattern, "cs" in flags)
        }
        val overriddenPatterns = current.mapTo(mutableSetOf()) { it.pattern }
        rules.removeAll { it.pattern in overriddenPatterns }
        rules += current
    }
    return UnixMimeGlobs(rules)
}