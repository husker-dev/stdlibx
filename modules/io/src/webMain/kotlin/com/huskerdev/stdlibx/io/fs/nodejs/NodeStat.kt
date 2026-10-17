package com.huskerdev.stdlibx.io.fs.nodejs

import kotlin.time.Instant

internal class NodeStat(private val value: NodeStats) {

    val mode: Int
        get() = value.mode.toLong().toInt()

    val size: Long
        get() = value.size.toLong()

    val regular: Boolean
        get() = value.isFile()

    val directory: Boolean
        get() = value.isDirectory()

    val link: Boolean
        get() = value.isSymbolicLink()

    val accessed: Instant
        get() = timestamp(value.atimeNs)

    val modified: Instant
        get() = timestamp(value.mtimeNs)

    val created: Instant
        get() = timestamp(value.birthtimeNs)

    fun sameFile(other: NodeStat): Boolean =
        value.dev.toString(10) == other.value.dev.toString(10) &&
        value.ino.toString(10) == other.value.ino.toString(10)

    private fun timestamp(nanoseconds: NodeBigInt): Instant {
        // A valid Instant can have more nanoseconds than fit in a Kotlin Long (e.g. 1601).
        val text = nanoseconds.toString(10)
        val negative = text.startsWith('-')
        val digits = text.removePrefix("-").padStart(10, '0')
        val seconds = digits.dropLast(9).toLong()
        val fraction = digits.takeLast(9).toLong()
        return Instant.fromEpochSeconds(
            epochSeconds = if (negative) -seconds else seconds,
            nanosecondAdjustment = if (negative) -fraction else fraction
        )
    }
}