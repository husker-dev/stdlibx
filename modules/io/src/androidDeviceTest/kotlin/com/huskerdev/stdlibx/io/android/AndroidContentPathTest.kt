package com.huskerdev.stdlibx.io.android

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huskerdev.stdlibx.ContextProvider
import com.huskerdev.stdlibx.io.Resource
import com.huskerdev.stdlibx.io.use
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidContentPathTest {
    private lateinit var root: File
    private lateinit var uri: Uri
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ContextProvider.applicationContext = context
        root = File(context.cacheDir, "io-content-${System.nanoTime()}")
        check(root.mkdir())
        file = File(root, "data")
        val id = "opaque/id with spaces"
        uri =
            Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.io-test-content")
                .appendPath("document")
                .appendPath(id)
                .appendQueryParameter("version", "1")
                .build()
        TestContentProvider.files.clear()
        TestContentProvider.files[id] = file
        TestContentProvider.lastDescriptor = null
        TestContentProvider.lastMode = null
        TestContentProvider.lastUri = null
    }

    @After
    fun tearDown() {
        TestContentProvider.lastDescriptor?.close()
        TestContentProvider.files.clear()
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun path(behavior: String? = null): AndroidContentPathImpl =
        AndroidContentPathImpl(
            uri.buildUpon()
                .apply {
                    if (behavior != null) appendQueryParameter("behavior", behavior)
                }
                .build()
                .toString()
        )

    @Test
    fun readsOpaqueUriAndPreservesStreamSemantics() {
        val bytes = ByteArray(65_537) { it.toByte() }
        file.writeBytes(bytes)
        val path = assertIs<AndroidContentPath>(Resource(uri.toString()))
        path.asSync()!!.newInputStream().use { input ->
            assertTrue(input.hasNext())
            assertTrue(input.hasNext())
            assertEquals(0, input.read(ByteArray(0)))
            assertContentEquals(bytes, input.readAllBytes())
            assertFalse(input.hasNext())
            assertEquals(0L, input.availableBytes())
            assertEquals(0, input.read(ByteArray(1)))
        }
        assertEquals(uri, TestContentProvider.lastUri)
        assertEquals("r", TestContentProvider.lastMode)
    }

    @Test
    fun writesTruncateAndCanCreateDocumentContent() {
        val path = path()
        path.newOutputStream().use { it.write(byteArrayOf(1, 2, 3, 4, 5)) }
        assertEquals("wt", TestContentProvider.lastMode)
        path.newOutputStream().use { it.write((-1).toByte()) }
        assertContentEquals(byteArrayOf(-1), file.readBytes())
        path.newOutputStream().use {}
        assertEquals(0L, file.length())
    }

    @Test
    fun asyncViewReadsAndWrites(): Unit = runBlocking {
        val sync = path()
        val async = sync.asAsync()
        assertTrue(async.asSync() === sync)
        assertTrue(async.asAsync() === async)
        async.newOutputStream().use { it.write(byteArrayOf(7, 8, 9)) }
        async.newInputStream().use { assertContentEquals(byteArrayOf(7, 8, 9), it.readAllBytes()) }
        assertEquals(sync.mimeType(), async.mimeType())
    }

    @Test
    fun inputStreamClosesProviderDescriptor() {
        file.writeBytes(byteArrayOf(1))
        val input = path().newInputStream()
        val descriptor = checkNotNull(TestContentProvider.lastDescriptor).fileDescriptor
        assertTrue(descriptor.valid())
        input.close()
        input.close()
        assertFalse(descriptor.valid())
        assertFailsWith<IllegalStateException> { input.hasNext() }
    }

    @Test
    fun outputStreamClosesProviderDescriptor() {
        val output = path().newOutputStream()
        val descriptor = checkNotNull(TestContentProvider.lastDescriptor).fileDescriptor
        output.write(byteArrayOf(1))
        output.close()
        assertFalse(descriptor.valid())
    }

    @Test
    fun supportsNonSeekableProviderStreams() {
        path("pipe").newInputStream().use {
            assertContentEquals(byteArrayOf(1, 2, 3), it.readAllBytes())
        }
    }

    @Test
    fun missingDocumentPropagatesFileNotFound() {
        assertFailsWith<FileNotFoundException> { path().newInputStream() }
    }

    @Test
    fun permissionErrorsAreNotSwallowed() {
        assertFailsWith<SecurityException> { path("denied").newInputStream() }
        assertFailsWith<SecurityException> { path("denied").newOutputStream() }
    }

    @Test
    fun nullProviderResultThrowsIOException() {
        assertFailsWith<IOException> { path("null").newInputStream() }
        assertFailsWith<IOException> { path("null").newOutputStream() }
    }

    @Test
    fun missingContextFailsExplicitly() {
        val context = ContextProvider.applicationContext
        ContextProvider.applicationContext = null
        try {
            assertFailsWith<IllegalStateException> { path().newInputStream() }
            assertFailsWith<IllegalStateException> { path().newOutputStream() }
        } finally {
            ContextProvider.applicationContext = context
        }
    }

    @Test
    fun validatesUriAndNormalizesSchemeForResolver() {
        for (invalid in
            listOf(
                "/tmp/file",
                "file:///tmp/file",
                "content://",
                "content:relative",
            )) assertFailsWith<IllegalArgumentException> { AndroidContentPathImpl(invalid) }
        file.writeBytes(byteArrayOf(1))
        val value = uri.toString().replaceFirst("content://", "CONTENT://")
        val path = assertIs<AndroidContentPath>(Resource(value))
        assertEquals(value, path.pathString)
        path.asSync()!!.newInputStream().use {
            assertContentEquals(byteArrayOf(1), it.readAllBytes())
        }
        assertEquals("content", TestContentProvider.lastUri?.scheme)
    }
}
