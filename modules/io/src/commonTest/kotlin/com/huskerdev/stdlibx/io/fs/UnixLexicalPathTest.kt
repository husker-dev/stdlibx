package com.huskerdev.stdlibx.io.fs

import kotlin.test.*

class UnixLexicalPathTest {
    private val fs = LexicalFileSystem()

    @Test
    fun normalizationMatrix() {
        for ((input, expected) in
            listOf(
                "" to "",
                "." to "",
                "a/.." to "",
                "a/../../b" to "../b",
                "../../a/../b" to "../../b",
                "/../../a" to "/a",
                "/a/../../.." to "/",
                "///a//./b/../c/" to "/a/c",
                "a\\b/./c" to "a\\b/c",
            )) {
            val normalized = fs.path(input).normalize()
            assertEquals(expected, normalized.pathString, input)
            assertEquals(normalized, normalized.normalize())
            assertSame(fs, normalized.fileSystem)
        }
    }

    @Test
    fun relativePathMatrix() {
        for ((target, base, expected) in
            listOf(
                Triple("/a/b/c", "/a/b", "c"),
                Triple("/a/b", "/a/b/c", ".."),
                Triple("/a/c/d", "/a/b/e", "../../c/d"),
                Triple("a/b", "a/b", ""),
                Triple("a/b", "", "a/b"),
                Triple("", "a/b", "../.."),
                Triple("/", "/a/b", "../.."),
                Triple("/a/b", "/", "a/b"),
                Triple("a/./b/../c", "a/d/..", "c"),
                Triple("../a", "../b", "../a"),
            )) {
            val path = fs.path(target).relativeTo(fs.path(base))
            assertEquals(expected, path.pathString, "$target relative to $base")
            assertSame(fs, path.fileSystem)
        }
    }

    @Test
    fun relativeFailuresAndFallbacks() {
        for ((target, base) in
            listOf("/a" to "a", "a" to "/a", "a" to "../b", "../a" to "../../b")) {
            val path = fs.path(target)
            assertFailsWith<IllegalArgumentException> { path.relativeTo(base) }
            assertNull(path.relativeToOrNull(base))
            assertEquals(path, path.relativeToOrSelf(base))
        }
    }

    @Test
    fun rootsNamesAndParents() {
        val empty = fs.path("")
        assertTrue(empty.isEmpty)
        assertFalse(empty.isAbsolute)
        assertNull(empty.parent)
        assertNull(empty.root)
        val root = fs.path("////")
        assertEquals("/", root.pathString)
        assertTrue(root.isAbsolute)
        assertFalse(root.isEmpty)
        assertNull(root.parent)
        assertEquals(root, root.root)
        val path = fs.path("///a//./archive.tar.gz/")
        assertEquals(listOf("a", ".", "archive.tar.gz"), path.names)
        assertEquals("archive.tar.gz", path.name)
        assertEquals("archive.tar", path.nameWithoutExtension)
        assertEquals("gz", path.extension)
        assertEquals(fs.path("/a/."), path.parent)
        assertFailsWith<IllegalArgumentException> { fs.path("a\u0000b") }
    }

    @Test
    fun componentBoundariesAreNotStringPrefixes() {
        val path = fs.path("/a/b")
        assertTrue(path.startsWith("/a"))
        assertTrue(path.startsWith("/"))
        assertFalse(path.startsWith("a"))
        assertFalse(path.startsWith("/ab"))
        assertTrue(path.endsWith("a/b"))
        assertTrue(path.endsWith("b"))
        assertFalse(path.endsWith("/b"))
        assertFalse(path.endsWith("ab"))
        assertFalse(fs.path("a/../b").startsWith("b"))
        assertFalse(fs.path("a/b/..").endsWith("a"))
    }

    @Test
    fun resolveAndSubpathsPreserveLexicalComponents() {
        val path = fs.path("/a/b")
        assertEquals(fs.path("/a/b/../c"), path.resolve("../c"))
        assertEquals(fs.path("/target"), path.resolve("/target"))
        assertEquals(path, path.resolve(""))
        assertEquals(fs.path("/a/c"), path.resolveSibling("c"))
        assertEquals(fs.path("a/b"), path.subpath(0, 2))
        assertSame(fs, path.subpath(0, 1).fileSystem)
        for ((start, end) in listOf(-1 to 1, 0 to 0, 1 to 0, 0 to 3)) {
            assertFailsWith<IllegalArgumentException> { path.subpath(start, end) }
        }
    }

    @Test
    fun identityAndForeignOperandValidation() {
        val path = fs.path("a/b")
        val sameIdentity = LexicalFileSystem(fs.identity)
        assertEquals<Path>(path, sameIdentity.path("a/b"))
        assertEquals(path.hashCode(), sameIdentity.path("a/b").hashCode())
        assertNotEquals(path, fs.path("a/./b"))
        val foreign = LexicalFileSystem()
        assertNotEquals<Path>(path, foreign.path("a/b"))
        for (text in listOf("", "/absolute", "relative")) {
            val other = foreign.path(text)
            assertFailsWith<IllegalArgumentException> { path.resolve(other) }
            assertFailsWith<IllegalArgumentException> { fs.path("").resolve(other) }
            assertFailsWith<IllegalArgumentException> { path.resolveSibling(other) }
            assertFailsWith<IllegalArgumentException> { path.startsWith(other) }
            assertFailsWith<IllegalArgumentException> { path.endsWith(other) }
            assertFailsWith<IllegalArgumentException> { path.relativeTo(other) }
        }
    }
}
