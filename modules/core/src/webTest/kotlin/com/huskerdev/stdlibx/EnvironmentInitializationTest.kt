package com.huskerdev.stdlibx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentInitializationTest {
    @Test
    fun environmentAndOsCanBeInitializedBeforeSeparators() {
        val kind = EnvironmentKind.current
        assertTrue(kind == EnvironmentKind.NODEJS || kind == EnvironmentKind.WEB)
        val windows = kind == EnvironmentKind.NODEJS && OS.isWindows
        assertEquals(if (windows) "\r\n" else "\n", systemLineSeparator)
        assertEquals(if (windows) ";" else ":", systemPathSeparator)
        assertEquals(if (windows) "\\" else "/", systemSeparator)
    }
}
