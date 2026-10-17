package com.huskerdev.stdlibx.io.fs

import kotlin.test.*

class UnixMimeTypesTest {
    @Test
    fun parsesMimeTypesFallbackAndIgnoresInlineComments() {
        val types =
            parseUnixMimeTypes(
                """
                # comment
                text/plain txt TEXT # not-an-extension
                application/json json
                invalid bad
                application/pdf
                """
                    .trimIndent()
            )
        assertEquals("text/plain", types["txt"])
        assertEquals("text/plain", types["text"])
        assertEquals("application/json", types["json"])
        assertNull(types["not-an-extension"])
        assertNull(types["bad"])
    }

    @Test
    fun optionalDatabaseReadDoesNotFailForMissingFilesOrDirectories() {
        assertNull(readOptionalMimeDatabase("/os-utils-no-such-mime-database"))
        assertNull(readOptionalMimeDatabase("/"))
    }
}
