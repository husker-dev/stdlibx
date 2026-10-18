@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.fs

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetMaximumSizeForEncoding
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreServices.UTTypeCopyPreferredTagWithClass
import platform.CoreServices.kUTTagClassMIMEType
import platform.Foundation.*
import platform.UniformTypeIdentifiers.*


internal fun NSURL.mimeTypeFromMetadata(): String? {
    val contentType = resourceValue(NSURLContentTypeKey) as? UTType

    val fromContentType = contentType?.preferredMIMEType
    if (!fromContentType.isNullOrBlank())
        return fromContentType

    val identifier = contentType?.identifier
        ?: resourceValue(NSURLTypeIdentifierKey) as? String

    return identifier?.let(::mimeTypeFromUti)
}

internal fun NSURL.mimeTypeFromExtension(): String? {
    val extension = pathExtension
    if(extension?.isBlank() ?: true) return null
    return UTType.typeWithFilenameExtension(extension)?.preferredMIMEType
}

private fun mimeTypeFromUti(uti: String): String? = memScoped {
    if (uti.isBlank()) return@memScoped null

    val cfUti = CFStringCreateWithCString(
        alloc = kCFAllocatorDefault,
        cStr = uti,
        encoding = kCFStringEncodingUTF8,
    ) ?: return@memScoped null

    val mimeRef = UTTypeCopyPreferredTagWithClass(
        inUTI = cfUti,
        inTagClass = kUTTagClassMIMEType,
    )
    CFRelease(cfUti)

    val mimeValue = cfStringToKString(mimeRef)
    mimeRef?.let { CFRelease(it) }
    mimeValue
}

@OptIn(BetaInteropApi::class)
private fun NSURL.resourceValue(key: NSURLResourceKey): Any? = memScoped {
    val valuePtr = alloc<ObjCObjectVar<Any?>>()
    val success = getResourceValue(value = valuePtr.ptr, forKey = key, error = null)
    if (success) valuePtr.value else null
}

private fun cfStringToKString(cfString: CFStringRef?): String? {
    if (cfString == null) return null

    val maxSize = CFStringGetMaximumSizeForEncoding(
        length = CFStringGetLength(cfString),
        encoding = kCFStringEncodingUTF8,
    ) + 1

    return memScoped {
        val buffer = allocArray<ByteVar>(maxSize)
        if (CFStringGetCString(
                theString = cfString,
                buffer = buffer,
                bufferSize = maxSize,
                encoding = kCFStringEncodingUTF8,
            )) buffer.toKString() else null
    }
}