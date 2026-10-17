package com.huskerdev.stdlibx

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

class ContextProvider: ContentProvider() {
    companion object {
        @Suppress("StaticFieldLeak")
        var applicationContext: Context? = null
    }

    override fun onCreate(): Boolean {
        applicationContext = context?.applicationContext ?: return false
        return true
    }

    override fun query(p0: Uri, p1: Array<out String?>?, p2: String?, p3: Array<out String?>?, p4: String?): Cursor? = null
    override fun getType(p0: Uri): String? = null
    override fun insert(p0: Uri, p1: ContentValues?): Uri? = null
    override fun delete(p0: Uri, p1: String?, p2: Array<out String?>?): Int = 0
    override fun update(p0: Uri, p1: ContentValues?, p2: String?, p3: Array<out String?>?): Int = 0
}