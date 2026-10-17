package com.huskerdev.stdlibx.io.fs.legacy

import android.system.ErrnoException
import android.system.Os
import com.huskerdev.stdlibx.io.fs.FileStore
import com.huskerdev.stdlibx.io.fs.unescapeMount

private const val STAT_RDONLY = 1L

class AndroidFileStore(
    fields: List<String>
): FileStore {
    override val name = unescapeMount(fields[0])
    val mountPoint = unescapeMount(fields[1])
    override val type = fields[2]

    override var isReadonly: Boolean? = null
    override var totalSpace: Long? = null
    override var usableSpace: Long? = null
    override var unallocatedSpace: Long? = null

    init {
        try {
            val info = Os.statvfs(mountPoint)

            isReadonly = info.f_flag and STAT_RDONLY != 0L
            totalSpace = info.f_blocks * info.f_frsize
            usableSpace = info.f_bavail * info.f_frsize
            unallocatedSpace = info.f_bfree * info.f_frsize
        } catch (_: ErrnoException) {}
    }
}