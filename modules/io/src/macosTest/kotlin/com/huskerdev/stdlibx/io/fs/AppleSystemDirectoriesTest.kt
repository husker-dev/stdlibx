package com.huskerdev.stdlibx.io.fs

import kotlin.test.*
import platform.Foundation.*

class AppleSystemDirectoriesTest {
    @Test
    fun usesFoundationTemporaryDirectory() {
        val fs = SyncKnUnixFileSystem()
        assertEquals(fs.path(NSTemporaryDirectory()), fs.tmpDir())
        assertTrue(fs.tmpDir().isAbsolute)
        assertTrue(fs.tmpDir().isDirectory()!!)
    }

    @Test
    fun usesFoundationHomeDirectory() {
        val fs = SyncKnUnixFileSystem()
        val home = assertNotNull(fs.homeDir())
        assertEquals(fs.path(NSHomeDirectory()), home)
        assertTrue(home.isAbsolute)
        assertTrue(home.isDirectory()!!)
    }

    @Test
    fun workingDirectoryMatchesFoundationWithoutChangingPosixSemantics() {
        val fs = SyncKnUnixFileSystem()
        assertEquals(fs.path(NSFileManager.defaultManager.currentDirectoryPath), fs.workingDir())
    }
}
