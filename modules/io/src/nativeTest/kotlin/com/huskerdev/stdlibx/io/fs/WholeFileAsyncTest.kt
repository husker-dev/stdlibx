package com.huskerdev.stdlibx.io.fs

import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class WholeFileAsyncTest {
    private fun contract(block: suspend AsyncFileSystemContract.() -> Unit): Unit = runBlocking {
        AsyncFileSystemContract(checkNotNull(FileSystem.default).asAsync().tmpDir()).block()
    }

    @Test fun creationAndWriteModes() = contract { creationAndWriteModes() }

    @Test fun textAndWholeFiles() = contract { textAndWholeFiles() }

    @Test fun streamsAndBounds() = contract { streamsAndBounds() }

    @Test fun directoriesCopyMoveAndDelete() = contract { directoriesCopyMoveAndDelete() }

    @Test fun snapshotsAndViews() = contract { snapshotsAndViews() }

    @Test fun walkingDepthAndControl() = contract { walkingDepthAndControl() }

    @Test
    fun wholeFileSuspendAdaptersUseNativeDispatcher(): Unit = runBlocking {
        val fs = checkNotNull(FileSystem.default).asAsync()
        val directory =
            fs.tmpDir()
                .resolve("os-utils-native-async-${Random.nextLong().toULong().toString(16)}")
                .createDirectory()
        val file = directory.resolve("file")
        try {
            val bytes = ByteArray(20000) { it.toByte() }
            file.writeBytes(bytes)
            assertContentEquals(bytes, file.readBytes())
            file.writeString("Привет 世界 😀")
            assertEquals("Привет 世界 😀", file.readString())
            file.writeLines(listOf("a", "", "b"))
            assertEquals(listOf("a", "", "b"), file.readLines())
        } finally {
            file.deleteIfExists()
            directory.deleteExisting()
        }
    }
}
