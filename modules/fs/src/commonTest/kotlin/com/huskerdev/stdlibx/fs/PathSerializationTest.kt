package com.huskerdev.stdlibx.fs

import com.huskerdev.stdlibx.fs.unix.AsyncUnixPath
import com.huskerdev.stdlibx.fs.unix.SyncUnixPath
import com.huskerdev.stdlibx.fs.unix.UnixPath
import com.huskerdev.stdlibx.fs.windows.AsyncWindowsPath
import com.huskerdev.stdlibx.fs.windows.SyncWindowsPath
import com.huskerdev.stdlibx.fs.windows.WindowsPath
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.*

class PathSerializationTest {
    @Serializable
    private data class Paths(val path: Path, val async: AsyncPath, val optional: List<Path?>)

    @Test
    fun encodesOnlyPathStringWithoutAccessingFilesystem() {
        val fs = LexicalFileSystem()
        for (text in listOf("", "/", "relative/../file", "dir/файл \"quoted\".txt")) {
            val path: Path = fs.path(text)
            assertEquals(Json.encodeToString(path.pathString), Json.encodeToString(path))
            assertEquals(Json.encodeToString(path.pathString), Json.encodeToString<UnixPath>(fs.path(text)))
        }
    }

    @Test
    fun restoresInDefaultFilesystemInsteadOfOriginal() {
        val foreign = LexicalFileSystem()
        val original: Path = foreign.path("relative/file")
        val json = Json.encodeToString(original)
        val fs = FileSystem.default
        if (fs == null) {
            assertFailsWith<SerializationException> { Json.decodeFromString<Path>(json) }
            return
        }
        val restored = Json.decodeFromString<Path>(json)
        assertEquals(original.pathString, restored.pathString)
        assertSame(fs.identity, restored.fileSystem.identity)
        assertNotSame(foreign.identity, restored.fileSystem.identity)
    }

    @Test
    fun allDeclaredPathTypesUseStringSerializers() {
        val fs = FileSystem.default
        val json = Json.encodeToString(fs?.path("relative/file")?.pathString ?: "relative/file")
        if (fs == null) {
            assertFailsWith<SerializationException> { Json.decodeFromString<AsyncPath>(json) }
            assertFailsWith<SerializationException> { Json.decodeFromString<SyncPath>(json) }
            return
        }
        val path = fs.path("relative/file")
        checkType<Path>(path, json)
        checkType<SyncPath>(path.asSync(), json)
        checkType<AsyncPath>(path.asAsync(), json)
        checkType<UnixPath>(path as? UnixPath, json)
        checkType<SyncUnixPath>(path.asSync() as? SyncUnixPath, json)
        checkType<AsyncUnixPath>(path.asAsync() as? AsyncUnixPath, json)
        checkType<WindowsPath>(path as? WindowsPath, json)
        checkType<SyncWindowsPath>(path.asSync() as? SyncWindowsPath, json)
        checkType<AsyncWindowsPath>(path.asAsync() as? AsyncWindowsPath, json)
    }

    @Test
    fun worksInSerializablePropertiesAndNullableCollections() {
        val fs = FileSystem.default ?: return
        val path = fs.path("relative/file")
        val original = Paths(path, path.asAsync(), listOf(path, null))
        val json = Json.encodeToString(original)
        val text = Json.encodeToString(path.pathString)
        assertEquals("""{"path":$text,"async":$text,"optional":[$text,null]}""", json)
        val restored = Json.decodeFromString<Paths>(json)
        assertEquals(path.pathString, restored.path.pathString)
        assertEquals(path.pathString, restored.async.pathString)
        assertEquals(path.pathString, restored.optional[0]?.pathString)
        assertNull(restored.optional[1])
        assertSame(fs.identity, restored.async.fileSystem.identity)
    }

    @Test
    fun rejectsNonStringValuesAndInvalidPaths() {
        for (json in listOf("null", "123", "{}", "[]")) {
            assertFailsWith<SerializationException> { Json.decodeFromString<Path>(json) }
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Path>(Json.encodeToString("invalid\u0000path"))
        }
    }

    private inline fun <reified T : Path> checkType(expected: T?, json: String) {
        val serializer = serializer<T>()
        if (expected == null) {
            assertFailsWith<SerializationException> { Json.decodeFromString(serializer, json) }
        } else {
            assertEquals(json, Json.encodeToString(serializer, expected))
            val restored = Json.decodeFromString(serializer, json)
            assertIs<T>(restored)
            assertEquals(expected.pathString, restored.pathString)
            assertSame(expected.fileSystem.identity, restored.fileSystem.identity)
        }
    }
}
