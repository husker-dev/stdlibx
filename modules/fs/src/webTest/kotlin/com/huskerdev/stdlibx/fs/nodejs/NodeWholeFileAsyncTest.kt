@file:OptIn(ExperimentalWasmJsInterop::class)

package com.huskerdev.stdlibx.fs.nodejs

import com.huskerdev.stdlibx.fs.*
import kotlin.coroutines.*
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.*

class NodeWholeFileAsyncTest {
    @Test
    fun nativePromiseReadsAndWrites(): Promise<JsAny?> = testPromise { complete ->
        val action: suspend () -> Unit = {
            val fs = checkNotNull(FileSystem.default).asAsync()
            val directory =
                fs.tmpDir()
                    .resolve("os-utils-async-whole-${Random.nextLong().toULong().toString(16)}")
                    .createDirectory()
            val file = directory.resolve("file")
            try {
                val bytes = ByteArray(30000) { it.toByte() }
                assertEquals(file, file.writeBytes(bytes))
                assertContentEquals(bytes, file.readBytes())
                file.writeBytes(byteArrayOf(1, 2, 3))
                file.writeBytes(byteArrayOf(4), mode = FileWriteMode.FROM_START)
                assertContentEquals(byteArrayOf(4, 2, 3), file.readBytes())
                file.writeBytes(byteArrayOf(5), mode = FileWriteMode.APPEND)
                assertContentEquals(byteArrayOf(4, 2, 3, 5), file.readBytes())
                assertFails { file.writeBytes(byteArrayOf(9), creation = FileCreation.CREATE_NEW) }
                assertContentEquals(byteArrayOf(4, 2, 3, 5), file.readBytes())
                file.writeString("Привет 世界 😀")
                assertEquals("Привет 世界 😀", file.readString())
                assertContentEquals("Привет 世界 😀".encodeToByteArray(), file.readBytes())
                file.writeLines(listOf("a", "", "b"))
                assertEquals(listOf("a", "", "b"), file.readLines())
                file.writeBytes(ByteArray(0))
                assertEquals("", file.readString())
                file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
                var malformed = false
                try {
                    file.readString()
                } catch (_: Throwable) {
                    malformed = true
                }
                assertTrue(malformed)
                file.deleteExisting()
                var missing = false
                try {
                    file.readBytes()
                } catch (_: NoSuchFileException) {
                    missing = true
                }
                assertTrue(missing)
            } finally {
                file.deleteIfExists()
                directory.deleteExisting()
            }
        }
        action.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Unit>) {
                    complete(result.exceptionOrNull()?.stackTraceToString())
                }
            }
        )
    }
}

internal fun testPromise(action: ((String?) -> Unit) -> Unit): Promise<JsAny?> = js(
    "new Promise((resolve, reject) => action(error => error === null ? resolve(null) : reject(new Error(error))))"
)
