package com.huskerdev.stdlibx.io.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class TestContentProvider : ContentProvider() {
    companion object {
        val files = mutableMapOf<String, File>()
        var lastMode: String? = null
        var lastUri: Uri? = null
        var lastDescriptor: ParcelFileDescriptor? = null
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        lastMode = mode
        lastUri = uri
        when (uri.getQueryParameter("behavior")) {
            "denied" -> throw SecurityException("Access denied by test provider")
            "null" -> return null
            "pipe" -> {
                if (mode != "r") throw FileNotFoundException("Pipe is read-only")
                val pipe = ParcelFileDescriptor.createPipe()
                Thread {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {
                        it.write(byteArrayOf(1, 2, 3))
                    }
                }
                    .start()
                return pipe[0].also { lastDescriptor = it }
            }
        }
        val file = files[uri.lastPathSegment] ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode)).also {
            lastDescriptor = it
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
