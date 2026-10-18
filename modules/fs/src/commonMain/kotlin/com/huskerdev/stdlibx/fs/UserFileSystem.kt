package com.huskerdev.stdlibx.fs

/** A file system with a working directory and access to standard user directories. */
interface UserFileSystem: FileSystem {
    companion object {
        /** Namespace used by browser-backed file systems. Set it before initializing the default file system. */
        var namespace: String = "stdlibx-io"
    }

    /** Returns the directory used to resolve relative paths. */
    fun workingDir(): Path

    override fun absolute(path: Path): Path {
        assertFS(path)
        return if (path.isAbsolute) path(path.pathString) else workingDir().resolve(path)
    }

    override fun asSync(): SyncUserFileSystem?
    override fun asAsync(): AsyncUserFileSystem
}

/** Provides suspending access to standard directories; optional directories may be unavailable. */
interface AsyncUserFileSystem: UserFileSystem, AsyncFileSystem {
    override fun workingDir(): AsyncPath

    /** Returns the user's home directory, or null if unavailable. */
    suspend fun homeDir(): AsyncPath?
    /** Returns the directory intended for temporary files. */
    suspend fun tmpDir(): AsyncPath
    /** Returns the user's documents directory, or null if unavailable. */
    suspend fun documentsDir(): AsyncPath?
    /** Returns the user's downloads directory, or null if unavailable. */
    suspend fun downloadsDir(): AsyncPath?
    /** Returns the user's pictures directory, or null if unavailable. */
    suspend fun picturesDir(): AsyncPath?
    /** Returns the user's videos directory, or null if unavailable. */
    suspend fun videosDir(): AsyncPath?
    /** Returns the user's music directory, or null if unavailable. */
    suspend fun musicDir(): AsyncPath?
    /** Returns the user's desktop directory, or null if unavailable. */
    suspend fun desktopDir(): AsyncPath?

    override fun absolute(path: Path): AsyncPath = super.absolute(path) as AsyncPath

    override fun asSync(): SyncUserFileSystem?
    override fun asAsync(): AsyncUserFileSystem
}

/** Provides blocking access to standard directories; optional directories may be unavailable. */
interface SyncUserFileSystem: UserFileSystem, SyncFileSystem {
    override fun workingDir(): SyncPath

    /** Returns the user's home directory, or null if unavailable. */
    fun homeDir(): SyncPath?
    /** Returns the directory intended for temporary files. */
    fun tmpDir(): SyncPath
    /** Returns the user's documents directory, or null if unavailable. */
    fun documentsDir(): SyncPath?
    /** Returns the user's downloads directory, or null if unavailable. */
    fun downloadsDir(): SyncPath?
    /** Returns the user's pictures directory, or null if unavailable. */
    fun picturesDir(): SyncPath?
    /** Returns the user's videos directory, or null if unavailable. */
    fun videosDir(): SyncPath?
    /** Returns the user's music directory, or null if unavailable. */
    fun musicDir(): SyncPath?
    /** Returns the user's desktop directory, or null if unavailable. */
    fun desktopDir(): SyncPath?

    override fun absolute(path: Path): SyncPath = super.absolute(path) as SyncPath

    override fun asSync(): SyncUserFileSystem?
    override fun asAsync(): AsyncUserFileSystem
}
