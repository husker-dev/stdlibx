package com.huskerdev.stdlibx.fs.legacy

import android.system.Os
import com.huskerdev.stdlibx.io.AsyncOutputStream
import com.huskerdev.stdlibx.io.SyncOutputStream
import com.huskerdev.stdlibx.io.WrappedAsyncOutputStream
import com.huskerdev.stdlibx.fs.Path
import com.huskerdev.stdlibx.fs.wrap
import java.io.FileDescriptor
import java.io.FileOutputStream

internal class AndroidLegacyFileOutputStream(
    val path: Path,
    val descriptor: FileDescriptor,
    val output: SyncOutputStream = FileOutputStream(descriptor).wrap(path),
): SyncOutputStream by output {
    private var closed = false
    private val async by lazy { WrappedAsyncOutputStream(this) }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            output.close()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (descriptor.valid()) try {
                syscall(path) { Os.close(descriptor) }
            } catch (error: Throwable) {
                if (failure == null) throw error
                if (failure !== error) failure.addSuppressed(error)
            }
        }
    }

    override fun asAsync(): AsyncOutputStream = async
}
