package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.fs.unix.AsyncUnixPath
import com.huskerdev.stdlibx.fs.unix.SyncUnixPath
import com.huskerdev.stdlibx.fs.unix.UnixPath
import com.huskerdev.stdlibx.fs.windows.AsyncWindowsPath
import com.huskerdev.stdlibx.fs.windows.SyncWindowsPath
import com.huskerdev.stdlibx.fs.windows.WindowsPath
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Stores only [Path.pathString]; restores the path in [FileSystem.default], not its original filesystem. */
object PathSerializer : KSerializer<Path> by pathSerializer("com.huskerdev.stdlibx.fs.Path")

/** Restores a blocking path; fails if the default filesystem has no synchronous view. */
object SyncPathSerializer : KSerializer<SyncPath> by pathSerializer("com.huskerdev.stdlibx.fs.SyncPath", { it.asSync() })

/** Restores an asynchronous path in the default filesystem. */
object AsyncPathSerializer : KSerializer<AsyncPath> by pathSerializer("com.huskerdev.stdlibx.fs.AsyncPath", { it.asAsync() })

/** Restores a Unix path; fails if the default filesystem uses another path implementation. */
object UnixPathSerializer : KSerializer<UnixPath> by pathSerializer("com.huskerdev.stdlibx.fs.unix.UnixPath")

/** Restores a blocking Unix path in the default filesystem. */
object SyncUnixPathSerializer : KSerializer<SyncUnixPath> by pathSerializer("com.huskerdev.stdlibx.fs.unix.SyncUnixPath", { it.asSync() })

/** Restores an asynchronous Unix path in the default filesystem. */
object AsyncUnixPathSerializer : KSerializer<AsyncUnixPath> by pathSerializer("com.huskerdev.stdlibx.fs.unix.AsyncUnixPath", { it.asAsync() })

/** Restores a Windows path; fails if the default filesystem uses another path implementation. */
object WindowsPathSerializer : KSerializer<WindowsPath> by pathSerializer("com.huskerdev.stdlibx.fs.windows.WindowsPath")

/** Restores a blocking Windows path in the default filesystem. */
object SyncWindowsPathSerializer : KSerializer<SyncWindowsPath> by pathSerializer("com.huskerdev.stdlibx.fs.windows.SyncWindowsPath", { it.asSync() })

/** Restores an asynchronous Windows path in the default filesystem. */
object AsyncWindowsPathSerializer : KSerializer<AsyncWindowsPath> by pathSerializer("com.huskerdev.stdlibx.fs.windows.AsyncWindowsPath", { it.asAsync() })

private inline fun <reified T : Path> pathSerializer(
    serialName: String,
    crossinline view: (Path) -> Path? = { it }
): KSerializer<T> = object : KSerializer<T> {
    override val descriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.pathString)

    override fun deserialize(decoder: Decoder): T {
        val text = decoder.decodeString()
        val fileSystem = FileSystem.default
            ?: throw SerializationException("No default filesystem is available")
        val path = try {
            view(fileSystem.path(text))
        } catch (error: IllegalArgumentException) {
            throw SerializationException("Invalid path for the default filesystem", error)
        }
        return path as? T
            ?: throw SerializationException("Default filesystem cannot provide $serialName")
    }
}
