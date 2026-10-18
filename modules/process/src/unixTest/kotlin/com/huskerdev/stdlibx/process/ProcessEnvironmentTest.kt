@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.huskerdev.stdlibx.process

import com.huskerdev.stdlibx.env
import kotlin.test.*
import platform.posix.setenv
import platform.posix.unsetenv

class ProcessEnvironmentTest {
    @Test
    fun nullInheritsCurrentEnvironmentButMapsReplaceIt() {
        val name = "STDLIBX_PROCESS_INHERITED_ENV_31875"
        val original = env()[name]
        fun readChild(environment: Map<String, String>?): String {
            val script = "printf '%s' \"\${$name-unset}\""
            return Process.exec(arrayOf("/bin/sh", "-c", script), environment = environment).use {
                process ->
                process.stdin.close()
                val result = process.stdout.readAllBytes().decodeToString()
                assertEquals(0, process.waitFor())
                result
            }
        }
        try {
            assertEquals(0, setenv(name, "parent=Привет😀", 1))
            assertEquals("parent=Привет😀", readChild(null))
            assertEquals("unset", readChild(emptyMap()))
            assertEquals("override", readChild(mapOf(name to "override")))
            assertEquals(0, setenv(name, "changed", 1))
            assertEquals("changed", readChild(null))
        } finally {
            if (original == null) unsetenv(name) else setenv(name, original, 1)
        }
    }

    @Test
    fun signalExitStatusIsPreserved() {
        for (forcibly in listOf(false, true)) {
            Process.exec(arrayOf("/bin/sh", "-c", "exec sleep 60")).use { process ->
                if (forcibly) process.killForcibly() else process.kill()
                val expected = if (forcibly) 137 else 143 // 128 + SIGKILL/SIGTERM.
                assertEquals(expected, process.waitFor())
                assertEquals(expected, process.exitCode)
            }
        }
    }
}
