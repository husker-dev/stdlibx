package com.huskerdev.stdlibx.io

import kotlin.test.*

class ProcessWindowsQuotingTest {
    @Test
    fun escapesCrtArguments() {
        assertEquals("plain", quoteProcessWindowsArgument("plain"))
        assertEquals("\"\"", quoteProcessWindowsArgument(""))
        assertEquals("\"with spaces\"", quoteProcessWindowsArgument("with spaces"))
        assertEquals("\"with\ttab\"", quoteProcessWindowsArgument("with\ttab"))
        assertEquals("\"a\\\"b\"", quoteProcessWindowsArgument("a\"b"))
        assertEquals("\"trailing space\\\\\"", quoteProcessWindowsArgument("trailing space\\"))
        assertEquals("C:\\directory\\", quoteProcessWindowsArgument("C:\\directory\\"))
    }
}
