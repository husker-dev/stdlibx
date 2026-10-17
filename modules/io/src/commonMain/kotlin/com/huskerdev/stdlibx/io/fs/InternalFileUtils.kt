package com.huskerdev.stdlibx.io.fs

import com.huskerdev.stdlibx.getenv
import com.huskerdev.stdlibx.io.AsyncInputStream
import com.huskerdev.stdlibx.io.AsyncStream
import com.huskerdev.stdlibx.io.SyncStream

internal fun decodeFileText(path: Path, bytes: ByteArray): String = try {
    bytes.decodeToString(throwOnInvalidSequence = true)
} catch (error: CharacterCodingException) {
    throw FileSystemException(path, "File is not valid UTF-8", error)
}

internal fun encodeFileText(text: String): ByteArray = try {
    text.encodeToByteArray(throwOnInvalidSequence = true)
} catch (error: CharacterCodingException) {
    throw IllegalArgumentException("Text contains an unpaired surrogate", error)
}

internal fun splitFileLines(text: String): List<String> = buildList {
    var start = 0
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '\r' || char == '\n') {
            add(text.substring(start, index))
            if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
            start = index + 1
        }
        index++
    }
    if (start < text.length) add(text.substring(start))
}

internal fun joinFileLines(lines: Iterable<String>, separator: String): String = buildString {
    for (line in lines) { append(line); append(separator) }
}

/** The descriptor's reported size is only a hint: files may grow/shrink or report zero. */
internal inline fun readWholeFileBytes(path: Path, initialSize: Long, read: (ByteArray, Int, Int) -> Int): ByteArray {
    if (initialSize > Int.MAX_VALUE) throw FileSystemException(path, "File is too large for a ByteArray")
    var buffer = ByteArray(if (initialSize > 0) initialSize.toInt() else 8192)
    var count = 0
    while (true) {
        if (count == buffer.size) {
            // Check EOF before doubling; avoid allocating twice the memory for a stable file.
            val probe = ByteArray(1)
            if (read(probe, 0, 1) == 0) return buffer
            if (count == Int.MAX_VALUE) throw FileSystemException(path, "File is too large for a ByteArray")
            buffer = buffer.copyOf((buffer.size.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            buffer[count++] = probe[0]
            continue
        }
        val result = read(buffer, count, buffer.size - count)
        check(result in 0..(buffer.size - count)) { "Invalid native read result" }
        if (result == 0) return if (count == buffer.size) buffer else buffer.copyOf(count)
        count += result
    }
}

internal fun parseXdgConfigFile(kind: String): String? {
    val home = FileSystem.default?.asSync()!!.homeDir()
        ?.absolute()?.pathString
        ?: return null

    val configHome = getenv("XDG_CONFIG_HOME")
        ?: "$home/.config"

    val text = Path("$configHome/user-dirs.dirs").asSync()!!.readString()

    val assignment = Regex("^\\s*XDG_${kind}_DIR=\"((?:\\\\.|[^\"])*)\"\\s*(?:#.*)?$")
    val raw = text.lineSequence()
        .mapNotNull { assignment.matchEntire(it)?.groupValues?.get(1) }
        .lastOrNull()
        ?: return null

    val expanded = when {
        raw == $$"$HOME" || raw.startsWith($$"$HOME/") -> home + raw.removePrefix($$"$HOME")
        raw == $$"${HOME}" || raw.startsWith($$"${HOME}/") -> home + raw.removePrefix($$"${HOME}")
        raw.startsWith('/') -> raw
        else -> return null
    }
    return Regex($$"\\\\([\\\\\"$`])").replace(expanded) { it.groupValues[1] }
}

/** Last-resort filename-based MIME detection; platform metadata and databases take precedence. */
internal fun fallbackMimeType(extension: String): String = fallbackMimeTypes[extension.lowercase()] ?: ""

private val fallbackMimeTypes = mapOf(
    "txt" to "text/plain",
    "html" to "text/html",
    "htm" to "text/html",
    "css" to "text/css",
    "csv" to "text/csv",
    "json" to "application/json",
    "xml" to "application/xml",
    "pdf" to "application/pdf",
    "zip" to "application/zip",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "svg" to "image/svg+xml",
    "webp" to "image/webp",
    "mp3" to "audio/mpeg",
    "mp4" to "video/mp4",
    "wav" to "audio/wav"
)

internal class LinesSyncStream(
    val fs: SyncFileSystem,
    val path: Path
): SyncStream<String> {
    val buffer = ByteArray(8192)
    val input = fs.newInputStream(path)

    private var position = 0
    private var limit = 0
    private var skipLf = false
    private var ended = false
    private var closed = false
    private var pending: String? = null

    override fun hasNext(): Boolean {
        if (closed) return false
        if (pending != null) return true
        if (ended) return false
        var bytes = ByteArray(128)
        var size = 0
        while (true) {
            if (position == limit) {
                limit = input.read(buffer)
                position = 0
                if (limit == 0) {
                    ended = true
                    if (size == 0) return false
                    break
                }
            }
            val byte = buffer[position++]
            if (skipLf) {
                skipLf = false
                if (byte == 10.toByte()) continue
            }
            if (byte == 10.toByte() || byte == 13.toByte()) {
                skipLf = byte == 13.toByte()
                break
            }
            if (size == bytes.size) {
                if (size == Int.MAX_VALUE) throw FileSystemException(path, "Line is too long")
                bytes = bytes.copyOf((size.toLong() * 2)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            bytes[size++] = byte
        }
        pending = decodeFileText(path, bytes.copyOf(size))
        return true
    }

    override fun next(): String {
        check(!closed) { "Stream is closed" }
        if (!hasNext()) throw NoSuchElementException()
        return checkNotNull(pending).also { pending = null }
    }

    override fun close() {
        if (closed) return
        closed = true
        pending = null
        input.close()
    }
}

internal class LinesAsyncStream(
    val fs: AsyncFileSystem,
    val path: Path,
    val input: AsyncInputStream
): AsyncStream<String> {
    val buffer = ByteArray(8192)

    private var position = 0
    private var limit = 0
    private var skipLf = false
    private var ended = false
    private var closed = false
    private var pending: String? = null

    override suspend fun hasNext(): Boolean {
        if (closed) return false
        if (pending != null) return true
        if (ended) return false
        var bytes = ByteArray(128)
        var size = 0
        while (true) {
            if (position == limit) {
                limit = input.read(buffer)
                position = 0
                if (limit == 0) {
                    ended = true
                    if (size == 0) return false
                    break
                }
            }
            val byte = buffer[position++]
            if (skipLf) {
                skipLf = false
                if (byte == 10.toByte()) continue
            }
            if (byte == 10.toByte() || byte == 13.toByte()) {
                skipLf = byte == 13.toByte()
                break
            }
            if (size == bytes.size) {
                if (size == Int.MAX_VALUE) throw FileSystemException(path, "Line is too long")
                bytes = bytes.copyOf((size.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            bytes[size++] = byte
        }
        pending = decodeFileText(path, bytes.copyOf(size))
        return true
    }

    override suspend fun next(): String {
        check(!closed) { "Stream is closed" }
        if (!hasNext()) throw NoSuchElementException()
        return checkNotNull(pending).also { pending = null }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        pending = null
        input.close()
    }
}
