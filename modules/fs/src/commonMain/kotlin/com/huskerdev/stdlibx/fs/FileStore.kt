package com.huskerdev.stdlibx.fs

/**
 * Information about a storage volume. Null values indicate unavailable information.
 * Failures while querying a volume use [FileSystemException].
 */
interface FileStore {
    /** The volume's name, if reported. */
    val name: String?
    /** The file system type, if reported. */
    val type: String?
    /** Whether the volume is read-only, if known. */
    val isReadonly: Boolean?

    /** The total capacity in bytes. */
    val totalSpace: Long?
    /** The space available to the current user in bytes. */
    val usableSpace: Long?
    /** The unallocated space in bytes, which may exceed [usableSpace]. */
    val unallocatedSpace: Long?
}

internal fun unescapeMount(value: String): String = value
    .replace("\\040", " ")
    .replace("\\011", "\t")
    .replace("\\012", "\n")
    .replace("\\134", "\\")
