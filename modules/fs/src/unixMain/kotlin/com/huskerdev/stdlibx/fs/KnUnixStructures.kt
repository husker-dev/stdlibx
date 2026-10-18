package com.huskerdev.stdlibx.fs

import kotlin.time.Instant

internal data class UnixStat(
    val device: ULong,
    val inode: ULong,
    val mode: Int,
    val size: Long,
    val accessTime: Instant,
    val modifiedTime: Instant,
    val birthTime: Instant?
) {
    val isRegularFile: Boolean = mode and 0xF000 == 0x8000
    val isDirectory: Boolean = mode and 0xF000 == 0x4000
    val isSymbolicLink: Boolean = mode and 0xF000 == 0xA000

    fun sameFile(other: UnixStat): Boolean =
        device == other.device && inode == other.inode
}

internal data class UnixSpace(
    val readonly: Boolean,
    val total: Long,
    val usable: Long,
    val unallocated: Long
)