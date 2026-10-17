package com.huskerdev.stdlibx.io.fs

import com.huskerdev.stdlibx.io.fs.unix.UnixFileSystem
import com.huskerdev.stdlibx.io.fs.unix.UnixPath
import com.huskerdev.stdlibx.io.fs.windows.WindowsFileSystem
import com.huskerdev.stdlibx.io.fs.windows.WindowsPath
import kotlin.test.*

class WindowsPathTest {
    // Only lexical operations are tested; no Windows host or Windows IO implementation is required.
    private val fs =
        object : WindowsFileSystem, FileSystem by LexicalFileSystem() {
            override val identity: Any = Any()

            override fun assertFS(path: Path) {
                require(path.fileSystem.identity === identity) {
                    "Path belongs to a different file system"
                }
            }

            override val separator: String = "\\"

            override fun path(vararg path: String): WindowsPath =
                WindowsPath(path.joinToString("\\"), this)
        }

    private fun path(value: String) = WindowsPath(value, fs)

    @Test
    fun distinguishesRootsAndAbsolutePaths() {
        val cases =
            listOf(
                Triple("", null, false),
                Triple("file", null, false),
                Triple("C:", "C:", false),
                Triple("C:file", "C:", false),
                Triple("\\file", "\\", false),
                Triple("C:\\file", "C:\\", true),
                Triple("\\\\server\\share\\file", "\\\\server\\share\\", true),
                Triple("\\\\?\\C:\\file", "\\\\?\\C:\\", true),
                Triple("\\\\?\\UNC\\server\\share\\file", "\\\\?\\UNC\\server\\share\\", true),
                Triple(
                    "\\\\?\\Volume{12345678-1234-1234-1234-123456789abc}\\file",
                    "\\\\?\\Volume{12345678-1234-1234-1234-123456789abc}\\",
                    true,
                ),
            )
        for ((value, root, absolute) in cases) {
            val p = path(value)
            assertEquals(root, p.root?.pathString, value)
            assertEquals(absolute, p.isAbsolute, value)
            assertEquals(value.isEmpty(), p.isEmpty, value)
            p.root?.let { assertNull(it.parent) }
        }
    }

    @Test
    fun canonicalizesOrdinarySeparatorsButNotDotComponents() {
        assertEquals("C:\\dir\\file", path("C://dir///file/").pathString)
        assertEquals("a\\b", path("a\\\\b\\").pathString)
        assertEquals("\\\\server\\share\\file", path("//server/share//file/").pathString)
        assertEquals("\\\\server\\share\\", path("\\\\server\\share").pathString)
        assertEquals("a\\.\\b\\..\\c", path("a/./b/../c").pathString)
        assertEquals("C:/dir/file", path("C:\\dir\\file").invariantSeparatorsPath)
    }

    @Test
    fun namesParentsAndExtensionsExcludeTheRoot() {
        val p = path("\\\\server\\share\\dir\\archive.tar.gz")
        assertEquals(listOf("dir", "archive.tar.gz"), p.names)
        assertEquals("archive.tar.gz", p.name)
        assertEquals("gz", p.extension)
        assertEquals("archive.tar", p.nameWithoutExtension)
        assertEquals(path("\\\\server\\share\\dir"), p.parent)
        assertEquals(p.root, p.parent!!.parent)
        assertEquals("C:", path("C:file").parent?.pathString)
        assertNull(path("file").parent)
        assertEquals("", path("C:\\").name)
        assertEquals("", path("README").extension)
        assertEquals("README", path("README").nameWithoutExtension)
        assertSame(fs, p.parent!!.fileSystem)
        assertEquals(path("dir\\archive.tar.gz"), p.subpath(0, 2))
    }

    @Test
    fun normalizePreservesRelativeParentsAndDoesNotEscapeRoots() {
        val cases =
            mapOf(
                "a\\.\\b\\..\\c" to "a\\c",
                "a\\.." to "",
                "..\\a\\..\\.." to "..\\..",
                "C:..\\a" to "C:..\\a",
                "C:a\\.." to "C:",
                "C:\\..\\a" to "C:\\a",
                "\\..\\a" to "\\a",
                "\\\\server\\share\\..\\a" to "\\\\server\\share\\a",
            )
        for ((input, expected) in cases) assertEquals(
            path(expected),
            path(input).normalize(),
            input,
        )
    }

    @Test
    fun normalizeReusesAlreadyNormalizedPaths() {
        for (value in
            listOf(
                "",
                "file",
                "..\\file",
                "C:..\\file",
                "\\file",
                "C:\\file",
                "\\\\server\\share\\file",
            )) {
            val p = path(value)
            assertSame(p, p.normalize(), value)
        }
    }

    @Test
    fun componentComparisonsUseTheSameSingleCharacterFoldingAsEquality() {
        val p = path("C:\\σ\\file")
        val other = path("c:\\Σ\\FILE")
        assertEquals(p, other)
        assertEquals(p.hashCode(), other.hashCode())
        assertTrue(p.startsWith(path("c:\\Σ")))
        assertTrue(p.endsWith(path("Σ\\FILE")))
        assertEquals(path("file"), p.relativeTo(path("c:\\Σ")))
        assertFalse(path("C:\\ß\\file").startsWith(path("C:\\SS")))
    }

    @Test
    fun equalityHashAndComponentComparisonsAreCaseInsensitive() {
        val first = path("c:\\Folder\\File.TXT")
        val second = path("C:/folder/file.txt")
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals("c:\\Folder\\File.TXT", first.toString())
        assertTrue(first.startsWith(path("C:\\FOLDER")))
        assertFalse(first.startsWith(path("C:\\Fold")))
        assertFalse(first.startsWith(path("D:\\Folder")))
        assertFalse(first.startsWith(path("C:Folder")))
        assertFalse(first.startsWith(path("Folder")))
        assertTrue(first.endsWith(path("folder\\FILE.txt")))
        assertFalse(first.endsWith(path("\\folder\\file.txt")))
        assertFalse(first.endsWith(path("ile.txt")))
        assertTrue(first.endsWith(second))
        assertTrue(path("a\\b").startsWith(path("")))
        assertFalse(first.startsWith(path("")))
    }

    @Test
    fun resolveDistinguishesDriveRootAndOrdinaryRelativePaths() {
        val cases =
            listOf(
                Triple("C:\\base", "child", "C:\\base\\child"),
                Triple("C:\\base", "D:\\child", "D:\\child"),
                Triple("C:\\base", "\\child", "C:\\child"),
                Triple("C:\\base", "c:child", "C:\\base\\child"),
                Triple("C:\\base", "D:child", "D:child"),
                Triple("C:", "child", "C:child"),
                Triple("C:base", "\\child", "C:\\child"),
                Triple("base", "\\child", "\\child"),
                Triple("\\\\server\\share\\base", "\\child", "\\\\server\\share\\child"),
                Triple("\\\\server\\share\\base", "C:child", "C:child"),
                Triple("", "C:child", "C:child"),
                Triple("\\\\?\\C:\\base", "child", "\\\\?\\C:\\base\\child"),
                Triple("\\\\?\\C:\\base", "\\child", "\\\\?\\C:\\child"),
            )
        for ((base, other, expected) in cases) assertEquals(
            path(expected),
            path(base).resolve(path(other)),
            "$base + $other",
        )
        val base = path("C:\\base")
        assertSame(base, base.resolve(path("")))
        assertEquals(path("C:\\base\\..\\child"), base.resolve(path("..\\child")))
        assertEquals(path("C:\\other"), base.resolveSibling(path("other")))
    }

    @Test
    fun relativeToNormalizesAndRequiresCompatibleRoots() {
        assertEquals(
            path("..\\other\\file"),
            path("C:\\root\\other\\file").relativeTo(path("c:\\ROOT\\base")),
        )
        assertEquals(path("child"), path("a\\b\\child").relativeTo(path("a\\.\\b")))
        assertEquals(path(""), path("C:\\a").relativeTo(path("c:\\A")))
        assertEquals(path("child"), path("C:a\\child").relativeTo(path("c:a")))
        assertEquals(path("file"), path("\\\\s\\share\\file").relativeTo(path("\\\\S\\SHARE\\")))
        for (other in listOf("D:\\a", "C:a", "\\a", "a", "\\\\s\\share\\a")) {
            assertFailsWith<IllegalArgumentException> { path("C:\\a").relativeTo(path(other)) }
        }
        assertFailsWith<IllegalArgumentException> { path("a").relativeTo(path("..\\b")) }
        assertNull(path("C:\\a").relativeToOrNull(path("D:\\a")))
    }

    @Test
    fun extendedPathsKeepLiteralComponentsAndTrailingSeparators() {
        val value = "\\\\?\\C:\\a\\.\\..\\name. \\"
        val p = path(value)
        assertEquals(value, p.pathString)
        assertEquals(listOf("a", ".", "..", "name. "), p.names)
        assertSame(p, p.normalize())
        assertEquals(path("\\\\?\\C:\\a\\.\\.."), p.parent)
        assertEquals(path("\\\\?\\C:\\a\\.\\..\\name. \\child"), p.resolve(path("child")))
        assertNotEquals(path("C:\\a"), path("\\\\?\\C:\\a"))
        assertEquals(path("child"), path("\\\\?\\C:\\a\\child").relativeTo(path("\\\\?\\C:\\a")))
        assertFailsWith<IllegalArgumentException> {
            path("\\\\?\\C:\\other").relativeTo(path("\\\\?\\C:\\a"))
        }
        assertFailsWith<IllegalArgumentException> {
            path("\\\\?\\C:\\a\\.").relativeTo(path("\\\\?\\C:\\a"))
        }
        assertEquals("\\\\?\\UNC\\s\\share\\", path("\\\\?\\UNC\\s\\share").root?.pathString)
    }

    @Test
    fun rejectsInvalidCharactersAndUnsupportedNamespaces() {
        val invalid =
            listOf(
                "a\u0000b",
                "a\u001fb",
                "a<b",
                "a>b",
                "a\"b",
                "a|b",
                "a?b",
                "a*b",
                "1:foo",
                "file:stream",
                "C:\\file:stream",
                "dir \\file",
                "file ",
                "\\\\server",
                "\\\\server\\",
                "\\\\.\\PhysicalDrive0",
                "\\\\?\\C:relative",
                "\\\\?\\C:/file",
                "//?/C:/file",
                "\\\\?\\C:\\\\file",
                "\\\\?\\C:\\a\\\\b",
                "\\\\?\\C:\\\\",
                "\\\\?\\UNC\\server",
                "\\\\?\\UNC\\server\\",
                "\\\\?\\Volume{not-a-guid}\\file",
                "\\\\?\\GLOBALROOT\\Device\\file",
            )
        for (value in invalid) assertFailsWith<IllegalArgumentException>(value) { path(value) }
    }

    @Test
    fun structuredExtendedRootsPreserveSpellingAndCompareComponents() {
        val uncRoot = "\\\\?\\uNc\\Server\\Share\\"
        val unc = path(uncRoot + "dir\\file")
        assertEquals(uncRoot, unc.root!!.pathString)
        assertEquals(path(uncRoot + "child"), unc.resolve(path("\\child")))
        assertEquals(path("dir\\file"), unc.relativeTo(path("\\\\?\\UNC\\server\\share\\")))
        assertFalse(unc.startsWith(path("\\\\server\\share\\")))
        assertFalse(unc.startsWith(path("\\\\?\\UNC\\server\\other\\")))

        val volumeRoot = "\\\\?\\vOlUmE{12345678-1234-1234-ABCD-123456789ABC}\\"
        val volume = path(volumeRoot + "dir\\file")
        assertEquals(volumeRoot, volume.root!!.pathString)
        assertEquals(path(volumeRoot + "child"), volume.resolve(path("\\child")))
        assertEquals(path("dir\\file"), volume.relativeTo(path(volumeRoot.lowercase())))
        assertFalse(
            volume.startsWith(path("\\\\?\\Volume{12345678-1234-1234-abcd-123456789abd}\\"))
        )
    }

    @Test
    fun driveRelativeResolutionUsesTheStructuredDriveLetter() {
        assertEquals(
            path("\\\\?\\c:\\base\\child"),
            path("\\\\?\\c:\\base").resolve(path("C:child")),
        )
        assertEquals(path("D:child"), path("\\\\?\\c:\\base").resolve(path("D:child")))
        assertEquals(path("c:base\\child"), path("c:base").resolve(path("C:child")))
        assertEquals(
            path("\\\\?\\UNC\\server\\share\\child"),
            path("\\\\?\\UNC\\server\\share\\base").resolve(path("\\child")),
        )
    }

    @Test
    fun doesNotApplyFileSystemRestrictionsOrMaxPathToLexicalPaths() {
        for (value in
            listOf(
                "CON",
                "NUL.txt",
                "COM1.log",
                "name.",
                "\\\\?\\C:\\CON.txt",
                "\\\\?\\C:\\name ",
            )) assertEquals(value, path(value).pathString)
        assertEquals(1000, path("a".repeat(1000)).name.length)
    }

    @Test
    fun rejectsForeignPathsAndPreservesOwnership() {
        val p = path("C:\\a")
        val otherFs =
            object : WindowsFileSystem, FileSystem by fs {
                override val identity: Any = Any()

                override fun path(vararg path: String) = WindowsPath(path.joinToString("\\"), this)
            }
        val foreign = WindowsPath("C:\\a", otherFs)
        assertNotEquals(p, foreign)
        val unixFs =
            object : UnixFileSystem, FileSystem by fs {
                override val identity: Any = Any()

                override fun path(vararg path: String) = UnixPath(path.joinToString("/"), this)
            }
        for (other in listOf(foreign, UnixPath("a", unixFs))) {
            assertFailsWith<IllegalArgumentException> { p.startsWith(other) }
            assertFailsWith<IllegalArgumentException> { p.endsWith(other) }
            assertFailsWith<IllegalArgumentException> { p.resolve(other) }
            assertFailsWith<IllegalArgumentException> { p.relativeTo(other) }
        }
        assertSame(fs, p.normalize().fileSystem)
        assertSame(fs, p.resolve(path("child")).fileSystem)
        assertSame(fs, p.relativeTo(path("C:\\")).fileSystem)
    }
}
