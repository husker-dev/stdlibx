# stdlibx

Zero-dependency extension for the Kotlin Multiplatform standard library.

Originally created for personal use, but it might prove useful to others.

Currently, includes the following modules:
- `core` - Common utils
- `io` - I/O operations

## Usage

```kotlin
implementation("com.huskerdev:stdlibx:2.0.0")
implementation("com.huskerdev:stdlibx-io:2.0.0")
```

## Modules

### core
Provides access to the following information:
- Current OS
- Architecture
- Environment variables
- Current working directory

Also:
- Printing to the `stderr` output stream
- Executing code upon process termination (addShutdownHook)

### io
Provides tools for working with files and data streams. 

The API is a hybrid of the JVM’s `nio` and `io` modules.
It supports both asynchronous and synchronous operations.

Includes:
- `Path` + `FileSystem`
- `InputStream`/`OutputStream`
- Standard `stdin`/`stdout`/`stderr` streams
- Spawning child processes (desktop targets only)

File handling is quite advanced, supporting optional symbolic link following, 
link creation, and the ability to write metadata (creation date, modification date, etc.). 

For the Web, it utilizes OPFS or an IndexedDB-based file system.