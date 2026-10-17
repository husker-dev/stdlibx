package com.huskerdev.stdlibx.io.fs

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Environment
import com.huskerdev.stdlibx.ContextProvider
import com.huskerdev.stdlibx.io.fs.legacy.SyncAndroidLegacyFileSystem
import com.huskerdev.stdlibx.io.fs.unix.*
import java.nio.file.FileSystems

internal actual val currentUserFileSystem: UserFileSystem? =
    if (Build.VERSION.SDK_INT >= 26) SyncNioAndroidFileSystem()
    else SyncAndroidLegacyFileSystem()

interface AndroidFileSystem: UnixFileSystem, UserFileSystem {
    val context: Context
        get() = ContextProvider.applicationContext ?:
        throw IllegalStateException("Android application context is not initialized")

    override fun asSync(): SyncAndroidFileSystem?
    override fun asAsync(): AsyncAndroidFileSystem
}

interface SyncAndroidFileSystem: AndroidFileSystem, SyncUnixFileSystem, SyncUserFileSystem {

    override fun workingDir(): SyncPath =
        path(context.filesDir.absolutePath)

    override fun homeDir(): SyncPath =
        workingDir()

    override fun tmpDir(): SyncPath =
        path(context.cacheDir.absolutePath)

    override fun documentsDir(): SyncPath? =
        externalDirOrNull(context, Environment.DIRECTORY_DOCUMENTS)

    override fun downloadsDir(): SyncPath? =
        externalDirOrNull(context, Environment.DIRECTORY_DOWNLOADS)

    override fun picturesDir(): SyncPath? =
        externalDirOrNull(context, Environment.DIRECTORY_PICTURES)

    override fun videosDir(): SyncPath? =
        externalDirOrNull(context, Environment.DIRECTORY_MOVIES)

    override fun musicDir(): SyncPath? =
        externalDirOrNull(context, Environment.DIRECTORY_MUSIC)

    override fun desktopDir(): SyncPath? = null
}

interface AsyncAndroidFileSystem: AndroidFileSystem, AsyncUnixFileSystem, AsyncUserFileSystem

// Nio

@SuppressLint("NewApi")
interface NioAndroidFileSystem: AndroidFileSystem, NioFileSystem {
    override val nioFileSystem: java.nio.file.FileSystem
        get() = FileSystems.getDefault()

    override fun asSync(): SyncNioAndroidFileSystem?
    override fun asAsync(): AsyncNioAndroidFileSystem
}

class SyncNioAndroidFileSystem:
    NioAndroidFileSystem,
    SyncAndroidFileSystem,
    SyncNioFileSystem
{
    private val async by lazy { AsyncNioAndroidFileSystem(this) }
    override fun absolute(path: Path): SyncPath = super<SyncNioFileSystem>.absolute(path)

    override fun asSync(): SyncNioAndroidFileSystem = this
    override fun asAsync(): AsyncNioAndroidFileSystem = async
}

class AsyncNioAndroidFileSystem(
    override val wrapped: SyncNioAndroidFileSystem
):
    NioAndroidFileSystem,
    AsyncAndroidFileSystem,
    AsyncNioFileSystem,
    WrappedAsyncFileSystem,
    WrappedAsyncUserFileSystem
{
    override fun absolute(path: Path): AsyncPath = super<AsyncNioFileSystem>.absolute(path)

    override fun asSync(): SyncNioAndroidFileSystem = wrapped
    override fun asAsync(): AsyncNioAndroidFileSystem = this
}

// Util

private fun FileSystem.externalDirOrNull(context: Context, type: String): SyncPath? =
    context.getExternalFilesDir(type)?.let { path(it.absolutePath).asSync() }
