package com.huskerdev.stdlibx.io.fs.opfs

import com.huskerdev.stdlibx.io.fs.FileStore

class OpfsFileStore internal constructor(
    estimate: OpfsStorageEstimate
): FileStore {
    val quota = estimate.quota?.opfsSize()
    val usage = estimate.usage?.opfsSize()

    override val name: String = "OPFS"
    override val type: String = "opfs"
    override val isReadonly: Boolean = false

    override val totalSpace: Long? = quota

    override val usableSpace: Long? =
        if (quota != null && usage != null) (quota - usage).coerceAtLeast(0)
        else null

    override val unallocatedSpace: Long? = null
}