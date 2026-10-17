@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io.fs

import kotlinx.cinterop.*
import platform.windows.*
import kotlin.text.ifEmpty

class KnWindowsFileStore(
    val mountPoint: String
): FileStore {
    override val name: String?
    override val type: String?
    override val isReadonly: Boolean
    override val totalSpace: Long
    override val usableSpace: Long
    override val unallocatedSpace: Long

    init {
        memScoped {
            val label = allocArray<UShortVar>(261)
            val type = allocArray<UShortVar>(261)
            val flags = alloc<UIntVar>()
            checkWindows(null, GetVolumeInformationW(mountPoint, label, 261u, null, null, flags.ptr, type, 261u))
            val usable = alloc<ULARGE_INTEGER>()
            val total = alloc<ULARGE_INTEGER>()
            val free = alloc<ULARGE_INTEGER>()
            checkWindows(null, GetDiskFreeSpaceExW(mountPoint, usable.ptr, total.ptr, free.ptr))

            this@KnWindowsFileStore.name = label.toKStringFromUtf16().ifEmpty { mountPoint }
            this@KnWindowsFileStore.type = type.toKStringFromUtf16()
            isReadonly = flags.value and FILE_READ_ONLY_VOLUME.toUInt() != 0u
            totalSpace = total.QuadPart.toLong()
            usableSpace = usable.QuadPart.toLong()
            unallocatedSpace = free.QuadPart.toLong()
        }
    }
}
