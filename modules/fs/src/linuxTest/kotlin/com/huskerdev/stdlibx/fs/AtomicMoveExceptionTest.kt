package com.huskerdev.stdlibx.fs

import kotlin.random.Random
import kotlin.test.*

class AtomicMoveExceptionTest {
    @Test
    fun crossDeviceAtomicMoveDoesNotFallBackToCopy() {
        val fs = checkNotNull(FileSystem.default).asSync()!!
        val temporary = fs.tmpDir()
        val sharedMemory = fs.path("/dev/shm")
        if (
            !sharedMemory.exists() ||
                !sharedMemory.isWritable() ||
                stat(temporary).device == stat(sharedMemory).device
        )
            return
        val name = "stdlibx-atomic-${Random.nextLong().toULong().toString(16)}"
        val source = temporary.resolve(name).writeString("source")
        val target = sharedMemory.resolve(name).writeString("target")
        try {
            val error =
                assertFailsWith<AtomicMoveNotSupportedException> {
                    source.moveTo(target, overwrite = true, atomicMove = true)
                }
            assertEquals(source, error.path)
            assertEquals(target, error.otherPath)
            assertEquals("source", source.readString())
            assertEquals("target", target.readString())
        } finally {
            source.deleteIfExists()
            target.deleteIfExists()
        }
    }
}
