@file:OptIn(ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.io.fs

import kotlin.test.*
import kotlinx.cinterop.*
import platform.posix.mkdtemp

class AppleMimeTypeTest {
    private val fs = SyncKnUnixFileSystem()

    @Test
    fun detectsSystemTypesBeyondTheFallbackTable() {
        assertEquals("image/heic", fs.path("photo.HEIC").mimeType())
        assertEquals("image/tiff", fs.path("photo.tiff").mimeType())
    }

    @Test
    fun detectsExtensionWithoutRequiringAnExistingFile() {
        assertEquals("application/pdf", nativeMimeType(fs, fs.path("/os-utils-no-such-file.PDF")))
        assertNull(nativeMimeType(fs, fs.path("/os-utils-no-such-file.unknown-mime-extension")))
    }

    @Test
    fun detectsMimeFromAnExistingFileUrl() {
        val template =
            "${fs.tmpDir().pathString}/os-utils-mime-XXXXXX".encodeToByteArray() + byteArrayOf(0)
        val directory =
            template.usePinned { mkdtemp(it.addressOf(0))?.toKString() }?.let { fs.path(it) }
                ?: error("Cannot create test directory")
        val file = directory.resolve("image.png")
        try {
            file.createFile()
            assertEquals("image/png", nativeMimeType(fs, file))
        } finally {
            file.deleteIfExists()
            directory.deleteExisting()
        }
    }
}
