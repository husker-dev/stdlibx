package com.huskerdev.stdlibx.io.fs.nodejs

import com.huskerdev.stdlibx.io.fs.FileStore
import com.huskerdev.stdlibx.io.fs.Path

class NodeFileStore(
    path: Path
): FileStore {
    private val info: NodeStatFs = nodeCall(path) { nodeFs().statfsSync(path.nodeName, statOptions()) }

    override val name: String = path.pathString

    override val type: String = when (val magic = info.type.toLong()) {
        // Linux
        0xef53L -> "ext"
        0x9123683eL -> "btrfs"
        0x58465342L -> "xfs"
        0x2FC12FC1L -> "zfs"
        0x01021994L -> "tmpfs"
        0x9FA0L -> "proc"
        0x6969L -> "nfs"
        0xFF534D42L -> "cifs/smb"
        0x794c7630L -> "overlay"

        // Windows
        0x65735546L -> "ntfs"
        0x4444L -> "fat32"

        // macOS
        0x7461636fL -> "apfs"
        0x17022011L -> "hfs+"
        else -> "0x${magic.toString(16)}"
    }

    override val isReadonly: Boolean = false
    override val totalSpace: Long = multiplyBigInts(info.blocks, info.bsize).toLong()
    override val usableSpace: Long = multiplyBigInts(info.bavail, info.bsize).toLong()
    override val unallocatedSpace: Long = multiplyBigInts(info.bfree, info.bsize).toLong()
}