package com.huskerdev.stdlibx.fs

internal class KnUnixFileStore(
    override val name: String,
    override val type: String,
    val mountPoint: String
): FileStore {
    override var isReadonly: Boolean? = null
    override var totalSpace: Long? = null
    override var usableSpace: Long? = null
    override var unallocatedSpace: Long? = null

    init {
        try {
            val info = nativeSpace(Path(mountPoint), mountPoint)
            isReadonly = info.readonly
            totalSpace = info.total
            usableSpace = info.usable
            unallocatedSpace = info.unallocated
        } catch (_: FileSystemException) { }
    }
}
