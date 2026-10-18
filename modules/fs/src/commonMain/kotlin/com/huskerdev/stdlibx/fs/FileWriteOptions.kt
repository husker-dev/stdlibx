package com.huskerdev.stdlibx.fs

/** Controls the starting position and treatment of existing file contents. */
enum class FileWriteMode {
    /** Writes from the beginning without discarding any remaining existing contents. */
    FROM_START,
    /** Discards existing contents before writing. */
    TRUNCATE,
    /** Adds output after existing contents. */
    APPEND,
}

/** Controls whether opening a file for writing may create it. */
enum class FileCreation {
    /** Requires an existing file. */
    OPEN_EXISTING,
    /** Opens an existing file or creates a new one if missing. */
    CREATE_IF_MISSING,
    /** Creates a new file and fails if an entry already exists. */
    CREATE_NEW
}
