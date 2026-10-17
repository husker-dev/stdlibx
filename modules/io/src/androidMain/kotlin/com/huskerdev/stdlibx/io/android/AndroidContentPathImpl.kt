package com.huskerdev.stdlibx.io.android

import android.net.Uri
import com.huskerdev.stdlibx.ContextProvider
import com.huskerdev.stdlibx.io.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

class AndroidContentPathImpl(
    override val pathString: String
): AndroidContentPath, SyncWritableResource {
    private val async by lazy { AsyncAndroidContentPathImpl(this) }

    private val uri = Uri.parse(pathString).normalizeScheme()

    private val resolver = ContextProvider.applicationContext?.contentResolver
        ?: throw IllegalStateException("Android application context is not initialized")
    
    init {
        require(uri.scheme == "content" && !uri.authority.isNullOrEmpty()) {
            "Expected a content URI with an authority: $pathString"
        }
    }

    override fun mimeType(): String = resolver.getType(uri) ?: ""

    override fun newInputStream(): SyncInputStream =
        resolver.openInputStream(uri)?.wrap()
            ?: throw IOException("Content provider did not open an input stream: $uri")

    override fun newOutputStream(): SyncOutputStream =
        resolver.openOutputStream(uri, "wt")?.wrap()
            ?: throw IOException("Content provider did not open an output stream: $uri")

    override fun asSync(): AndroidContentPathImpl = this
    override fun asAsync(): AsyncAndroidContentPathImpl = async
}

class AsyncAndroidContentPathImpl internal constructor(
    private val wrapped: AndroidContentPathImpl
): AndroidContentPath, AsyncWritableResource {
    override val pathString: String = wrapped.pathString

    override suspend fun mimeType(): String = withContext(asyncDispatcher) {
        wrapped.mimeType()
    }

    override suspend fun newInputStream(): AsyncInputStream = acquire {
        wrapped.newInputStream()
    }.asAsync()

    override suspend fun newOutputStream(): AsyncOutputStream = acquire {
        wrapped.newOutputStream()
    }.asAsync()

    private suspend fun <T : AutoCloseable> acquire(open: () -> T): T {
        var resource: T? = null
        try {
            return withContext(asyncDispatcher) { open().also { resource = it } }
        } catch (error: Throwable) {
            withContext(NonCancellable + asyncDispatcher) {
                try { resource?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    override fun asSync(): AndroidContentPathImpl = wrapped
    override fun asAsync(): AsyncAndroidContentPathImpl = this
}
