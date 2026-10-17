package com.huskerdev.stdlibx.io.android

import com.huskerdev.stdlibx.io.WritableResource

/** A resource addressed by an Android content URI. Access depends on the provider and granted permissions. */
interface AndroidContentPath: WritableResource {
    /** The content URI identifying this resource. */
    val pathString: String
}
