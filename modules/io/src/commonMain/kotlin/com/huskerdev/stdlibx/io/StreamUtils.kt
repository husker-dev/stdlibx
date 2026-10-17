package com.huskerdev.stdlibx.io

import kotlinx.coroutines.CoroutineDispatcher

internal const val DEFAULT_STREAM_BUFFER_SIZE = 8192

internal expect val asyncDispatcher: CoroutineDispatcher

/** Copies remaining bytes to [output] and returns their count. Does not flush or close either stream. */
fun SyncInputStream.copyTo(output: SyncOutputStream, bufferSize: Int = DEFAULT_STREAM_BUFFER_SIZE): Long =
    copyStream(bufferSize, { read(it) }, { bytes, count -> output.write(bytes, 0, count) })

/** Copies remaining bytes to [output] and returns their count. Does not flush or close either stream. */
suspend fun AsyncInputStream.copyTo(output: AsyncOutputStream, bufferSize: Int = DEFAULT_STREAM_BUFFER_SIZE): Long =
    copyStream(bufferSize, { read(it) }, { bytes, count -> output.write(bytes, 0, count) })

private inline fun copyStream(
    bufferSize: Int,
    read: (ByteArray) -> Int,
    write: (ByteArray, Int) -> Unit
): Long {
    require(bufferSize > 0) { "Buffer size must be positive" }
    val buffer = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count == 0) return total
        check(count in 1..buffer.size) { "Invalid read count: $count" }
        write(buffer, count)
        total += count
    }
}

/** Wraps this stream in a buffered reader that also closes this stream on close. */
fun SyncInputStream.buffered(bufferSize: Int = DEFAULT_STREAM_BUFFER_SIZE): BufferedSyncInputStream =
    BufferedSyncInputStream(this, bufferSize)

/** Wraps this stream in a buffered reader that also closes this stream on close. */
fun AsyncInputStream.buffered(bufferSize: Int = DEFAULT_STREAM_BUFFER_SIZE): BufferedAsyncInputStream =
    BufferedAsyncInputStream(this, bufferSize)

/** Wraps this stream in a buffered writer that flushes pending output and closes this stream on close. */
fun SyncOutputStream.buffered(bufferSize: Int = DEFAULT_STREAM_BUFFER_SIZE): BufferedSyncOutputStream =
    BufferedSyncOutputStream(this, bufferSize)

/** Wraps this stream in a buffered writer that flushes pending output and closes this stream on close. */
fun AsyncOutputStream.buffered(bufferSize: Int = DEFAULT_STREAM_BUFFER_SIZE): BufferedAsyncOutputStream =
    BufferedAsyncOutputStream(this, bufferSize)

internal fun checkBounds(bytes: ByteArray, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > bytes.size || length > bytes.size - offset)
        throw IndexOutOfBoundsException()
}
