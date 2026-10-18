package com.huskerdev.stdlibx.fs

import kotlin.test.*

class MimeTypesTest {
    @Test
    fun fallbackRecognizesCommonExtensionsAndIgnoresCase() {
        val types =
            mapOf(
                "txt" to "text/plain",
                "html" to "text/html",
                "htm" to "text/html",
                "css" to "text/css",
                "csv" to "text/csv",
                "json" to "application/json",
                "xml" to "application/xml",
                "pdf" to "application/pdf",
                "zip" to "application/zip",
                "png" to "image/png",
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "gif" to "image/gif",
                "svg" to "image/svg+xml",
                "webp" to "image/webp",
                "mp3" to "audio/mpeg",
                "mp4" to "video/mp4",
                "wav" to "audio/wav",
            )
        for ((extension, mimeType) in types) {
            assertEquals(mimeType, fallbackMimeType(extension), extension)
            assertEquals(mimeType, fallbackMimeType(extension.uppercase()), extension)
        }
    }

    @Test
    fun fallbackDoesNotGuessUnknownTypesOrAcceptPathsInsteadOfExtensions() {
        for (extension in
            listOf("", "unknown-os-utils-extension", ".txt", "file.txt", "dir/file.txt")) {
            assertEquals("", fallbackMimeType(extension), extension)
        }
    }
}
