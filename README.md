# stdlibx

Zero-dependency extension for the Kotlin Multiplatform standard library.

Originally created for personal use, but it might prove useful to others.

Currently, includes the following modules:
- `core` - Common utils
- `io` - I/O operations
- `fs` - File systems and paths
- `process` - Child processes (desktop targets only)

## Usage

```kotlin
implementation("com.huskerdev:stdlibx:2.0.0")
implementation("com.huskerdev:stdlibx-io:2.0.0")
implementation("com.huskerdev:stdlibx-fs:2.0.0")
implementation("com.huskerdev:stdlibx-process:2.0.0")
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
Provides data streams and resource interfaces (`com.huskerdev.stdlibx.io`).

Includes:
- `InputStream`/`OutputStream`
- Standard `stdin`/`stdout`/`stderr` streams
- Android content URI resources

`Resource` is an interface without a string-path factory. Use `Path(...)` from
`com.huskerdev.stdlibx.fs` for file-system paths or `AndroidContentPathImpl(...)`
for Android content URIs.

### fs
Provides tools for working with files (`com.huskerdev.stdlibx.fs`). Depends on `io`.

The API is a hybrid of the JVM’s `nio` and `io` modules.
It supports both asynchronous and synchronous operations.

Includes:
- `Path` + `FileSystem`

File handling is quite advanced, supporting optional symbolic link following, 
link creation, and the ability to write metadata (creation date, modification date, etc.). 

For the Web, it utilizes OPFS or an IndexedDB-based file system.

### process
Provides child processes, pipe streams and output capture (`com.huskerdev.stdlibx.process`). 

Available on desktop JVM, Linux, macOS and Windows.
Depends on `fs`, which transitively includes `io` and `core`.
