package com.huskerdev.stdlibx.fs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UnixPathTest {
    private val fileSystem = SyncNioUserUnixFileSystem()

    private fun path(value: String): SyncPath = fileSystem.path(value)

    @Test
    fun constructionCollapsesSeparatorsButPreservesDotComponents() {
        assertEquals("/a/b/./../c", path("///a//b/./../c///").pathString)
        assertEquals("a/b", path("a///b/").pathString)
        assertEquals("/", path("////").pathString)
        assertEquals("a/b/c", fileSystem.path("a", "", "b", "c").pathString)
        assertEquals("a\\b", path("a\\b").pathString)
        assertFailsWith<IllegalArgumentException> { path("a\u0000b") }
    }

    @Test
    fun emptyAndRootPathsHaveDistinctProperties() {
        val empty = path("")
        assertTrue(empty.isEmpty)
        assertFalse(empty.isAbsolute)
        assertEquals(emptyList(), empty.names)
        assertNull(empty.root)
        assertNull(empty.parent)
        val root = path("/")
        assertFalse(root.isEmpty)
        assertTrue(root.isAbsolute)
        assertEquals(emptyList(), root.names)
        assertEquals(root, root.root)
        assertNull(root.parent)
        assertSame(fileSystem, root.root!!.fileSystem)
    }

    @Test
    fun namesAndExtensionsAreLexical() {
        val value = path("/a/./archive.tar.gz")
        assertEquals(listOf("a", ".", "archive.tar.gz"), value.names)
        assertEquals("archive.tar.gz", value.name)
        assertEquals("gz", value.extension)
        assertEquals("archive.tar", value.nameWithoutExtension)
        assertEquals("", path("plain").extension)
        assertEquals("plain", path("plain").nameWithoutExtension)
        assertEquals("profile", path(".profile").extension)
        assertEquals("", path(".profile").nameWithoutExtension)
        assertEquals("", path("/").name)
        assertEquals(value.pathString, value.invariantSeparatorsPath)
        assertEquals(value.pathString, value.toString())
    }

    @Test
    fun parentsPreserveRootAndFileSystem() {
        val value = path("/a/b/c")
        assertEquals(path("/a/b"), value.parent)
        assertEquals(path("/a"), value.parent!!.parent)
        assertEquals(path("/"), value.parent!!.parent!!.parent)
        assertSame(fileSystem, value.parent!!.fileSystem)
        assertEquals(path("a"), path("a/b").parent)
        assertNull(path("a").parent)
        assertNull(path(".").parent)
        assertNull(path("a/b").root)
    }

    @Test
    fun subpathsAreRelativeAndPreserveOwnership() {
        val value = path("/a/b/c")
        assertEquals(path("a/b/c"), value.subpath(0, 3))
        assertEquals(path("b/c"), value.subpath(1, 3))
        assertEquals(path("b"), value.subpath(1, 2))
        assertFalse(value.subpath(0, 3).isAbsolute)
        assertSame(fileSystem, value.subpath(1, 3).fileSystem)
    }

    @Test
    fun invalidSubpathRangesAreRejected() {
        val value = path("a/b/c")
        for ((begin, end) in listOf(-1 to 1, 0 to 0, 2 to 1, 3 to 3, 0 to 4, 0 to -1)) {
            assertFailsWith<IllegalArgumentException> { value.subpath(begin, end) }
        }
        assertFailsWith<IllegalArgumentException> { path("").subpath(0, 1) }
        assertFailsWith<IllegalArgumentException> { path("/").subpath(0, 1) }
    }

    @Test
    fun relativeNormalizationPreservesUnresolvedParents() {
        for ((input, expected) in
            listOf(
                "a/./b/../c" to "a/c",
                "a/.." to "",
                "." to "",
                "" to "",
                "../../a/../b" to "../../b",
                "a/../../b" to "../b",
                "../.." to "../..",
            )) {
            val normalized = path(input).normalize()
            assertEquals(path(expected), normalized, input)
            assertSame(fileSystem, normalized.fileSystem)
            assertEquals(normalized, normalized.normalize())
        }
    }

    @Test
    fun absoluteNormalizationCannotEscapeRoot() {
        for ((input, expected) in
            listOf(
                "/a/./b/../c" to "/a/c",
                "/../../a" to "/a",
                "/a/../../.." to "/",
                "/." to "/",
                "/" to "/",
            )) {
            val normalized = path(input).normalize()
            assertEquals(path(expected), normalized, input)
            assertTrue(normalized.isAbsolute)
            assertSame(fileSystem, normalized.fileSystem)
        }
    }

    @Test
    fun resolveJoinsWithoutNormalizing() {
        assertEquals(path("/a/b/c"), path("/a/b").resolve("c"))
        assertEquals(path("a/b"), path("a").resolve(path("b")))
        assertEquals(path("/a/../b"), path("/a").resolve("../b"))
        assertEquals(path("/child"), path("/").resolve("child"))
        assertSame(fileSystem, path("a").resolve("b").fileSystem)
    }

    @Test
    fun resolveFastPathsPreserveIdentityAndOwnership() {
        val base = path("a/b")
        val empty = path("")
        val absolute = path("/target")
        assertSame(base, base.resolve(empty))
        assertEquals(absolute, base.resolve(absolute))
        assertEquals(base, empty.resolve(base))
        assertSame(empty, empty.resolve(empty))
        assertSame(fileSystem, base.resolve(absolute).fileSystem)
        assertSame(fileSystem, empty.resolve(base).fileSystem)
    }

    @Test
    fun resolveSiblingUsesParentWithoutNormalizing() {
        assertEquals(path("/a/c"), path("/a/b").resolveSibling("c"))
        assertEquals(path("a/../c"), path("a/b").resolveSibling("../c"))
        assertEquals(path("/c"), path("/a").resolveSibling("c"))
        assertEquals(path("a"), path("a/b").resolveSibling(""))
        val absolute = path("/elsewhere")
        assertEquals(absolute, path("a/b").resolveSibling(absolute))
        assertSame(fileSystem, path("a/b").resolveSibling("c").fileSystem)
    }

    @Test
    fun parentlessSiblingReturnsOperandWithItsOwnership() {
        val sibling = path("next")
        for (base in listOf("", "/", "single")) {
            assertEquals(sibling, path(base).resolveSibling(sibling))
            assertSame(fileSystem, path(base).resolveSibling("next").fileSystem)
        }
    }

    @Test
    fun relativeToMatchesKotlinFileTargetToBaseContract() {
        for ((target, base) in
            listOf(
                "/a/b/c" to "/a/b",
                "/a/b" to "/a/b/c",
                "/a/c/d" to "/a/b/e",
                "a/c" to "a/b",
                "a/b" to "a/b",
                "a/b" to "",
                "" to "a/b",
                "/" to "/a/b",
                "/a/b" to "/",
                "a/./b/../c" to "a/d/..",
                "../a" to "../b",
                "../../a" to "../b",
            )) {
            val expected = File(target).relativeTo(File(base)).invariantSeparatorsPath
            val actual = path(target).relativeTo(path(base))
            assertEquals(expected, actual.pathString, "target=$target, base=$base")
            assertSame(fileSystem, actual.fileSystem)
            assertEquals(actual, path(target).relativeTo(base))
        }
    }

    @Test
    fun relativeToRejectsDifferentRootsAndUnresolvedBaseParents() {
        for ((target, base) in
            listOf("/a" to "a", "a" to "/a", "a" to "../b", "../a" to "../../b")) {
            assertFailsWith<IllegalArgumentException> { File(target).relativeTo(File(base)) }
            val value = path(target)
            assertFailsWith<IllegalArgumentException> { value.relativeTo(path(base)) }
            assertNull(value.relativeToOrNull(path(base)))
            assertSame(value, value.relativeToOrSelf(path(base)))
        }
        assertEquals(path("c"), path("a/b/c").relativeToOrNull("a/b"))
        assertEquals(path("c"), path("a/b/c").relativeToOrSelf("a/b"))
    }

    @Test
    fun startsWithComparesWholeComponentsAndRoots() {
        assertTrue(path("/a/b").startsWith("/a"))
        assertTrue(path("/a/b").startsWith("/"))
        assertTrue(path("a/b").startsWith("a"))
        assertTrue(path("a/b").startsWith(""))
        assertTrue(path("").startsWith(""))
        assertFalse(path("/a/b").startsWith("a"))
        assertFalse(path("a/b").startsWith("/a"))
        assertFalse(path("/a/b").startsWith(""))
        assertFalse(path("ab/c").startsWith("a"))
        assertFalse(path("a").startsWith("a/b"))
        assertFalse(path("a/../b").startsWith("b"))
    }

    @Test
    fun endsWithComparesWholeComponentsWithoutNormalization() {
        assertTrue(path("/a/b").endsWith("b"))
        assertTrue(path("/a/b").endsWith("a/b"))
        assertTrue(path("a/b").endsWith(path("b")))
        assertTrue(path("/a/b").endsWith(""))
        assertTrue(path("").endsWith(""))
        assertFalse(path("a/ab").endsWith("b"))
        assertFalse(path("b").endsWith("a/b"))
        assertFalse(path("a/b/..").endsWith("a"))
    }

    @Test
    fun rootedSuffixRequiresEqualityOfTheEntirePath() {
        assertTrue(path("/a/b").endsWith("/a/b"))
        assertTrue(path("/").endsWith("/"))
        assertFalse(path("/a/b").endsWith("/b"))
        assertFalse(path("/a/b").endsWith("/"))
        assertFalse(path("a/b").endsWith("/a/b"))
        assertFalse(path("/a/../b").endsWith("/b"))
    }

    @Test
    fun equalityUsesLexicalPathAndFileSystemIdentity() {
        val value = path("/a/b")
        val equivalent = path("//a///b/")
        assertEquals(value, equivalent)
        assertEquals(equivalent, value)
        assertEquals(value.hashCode(), equivalent.hashCode())
        assertEquals(1, setOf(value, equivalent).size)
        assertNotEquals(value, path("/a/./b"))
        assertNotEquals(value, path("a/b"))
        assertEquals(value, SyncNioUserUnixFileSystem().path("/a/b"))
        assertNotEquals(value, foreignUnixFileSystem().path("/a/b"))
        assertFalse(value.equals(null))
        assertFalse(value.equals("/a/b"))
    }

    @Test
    fun foreignFileSystemPathsAreRejectedBeforeResolveFastPaths() {
        val foreign = foreignUnixFileSystem()
        val value = path("a/b")
        for (other in
            listOf(foreign.path(""), foreign.path("/absolute"), foreign.path("relative"))) {
            assertFailsWith<IllegalArgumentException> { value.startsWith(other) }
            assertFailsWith<IllegalArgumentException> { value.endsWith(other) }
            assertFailsWith<IllegalArgumentException> { value.resolve(other) }
            assertFailsWith<IllegalArgumentException> { path("").resolve(other) }
            assertFailsWith<IllegalArgumentException> { value.relativeTo(other) }
            assertFailsWith<IllegalArgumentException> { value.resolveSibling(other) }
            assertNull(value.relativeToOrNull(other))
            assertSame(value, value.relativeToOrSelf(other))
        }
    }

    @Test
    fun parentlessSiblingRejectsForeignFileSystem() {
        val foreign = foreignUnixFileSystem().path("next")
        for (base in listOf("", "/", "single")) {
            assertFailsWith<IllegalArgumentException>("base=$base") {
                path(base).resolveSibling(foreign)
            }
        }
    }

    @Test
    fun nonUnixPathsAreRejectedEvenWithTheSameFileSystem() {
        val value = path("a/b")
        for (text in listOf("", "/absolute", "relative")) {
            val foreign = object : SyncPath by path(text) {}
            assertSame(fileSystem, foreign.fileSystem)
            assertFailsWith<IllegalArgumentException> { value.startsWith(foreign) }
            assertFailsWith<IllegalArgumentException> { value.endsWith(foreign) }
            assertFailsWith<IllegalArgumentException> { value.resolve(foreign) }
            assertFailsWith<IllegalArgumentException> { path("").resolve(foreign) }
            assertFailsWith<IllegalArgumentException> { value.relativeTo(foreign) }
            assertFailsWith<IllegalArgumentException> { value.resolveSibling(foreign) }
            assertFalse(value.equals(foreign))
        }
    }
}
