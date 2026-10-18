package com.huskerdev.stdlibx.fs

import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class WholeFileAsyncTest {
    @Test
    fun wholeFileSuspendAdapters(): Unit = runBlocking {
        val fs = checkNotNull(FileSystem.default).asAsync()
        val directory =
            fs.tmpDir()
                .resolve("os-utils-jvm-async-${Random.nextLong().toULong().toString(16)}")
                .createDirectory()
        val file = directory.resolve("file")
        try {
            val bytes = ByteArray(20000) { it.toByte() }
            assertEquals(file, file.writeBytes(bytes))
            assertContentEquals(bytes, file.readBytes())
            assertEquals(file, file.writeString("Привет 世界 😀"))
            assertEquals("Привет 世界 😀", file.readString())
            assertEquals(file, file.writeLines(listOf("a", "", "b")))
            assertEquals(listOf("a", "", "b"), file.readLines())
            file.writeBytes(ByteArray(0))
            assertEquals("", file.readString())
            assertEquals(emptyList(), file.readLines())
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertFails { file.readString() }
        } finally {
            file.deleteIfExists()
            directory.deleteExisting()
        }
    }
}
