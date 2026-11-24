package io.github.mam15mon.dsh.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileLinksTest {
    private val base = "/data/media/0/Android/data/com.game/SAVEDATA"
    private fun local(raw: String) = (FileLinks.resolve(raw, base) as LinkTarget.Local).path

    @Test fun relativePathsResolveAgainstTheProject() {
        assertEquals("/storage/emulated/0/Android/data/com.game/SAVEDATA/AGENTS.md", local("AGENTS.md"))
        assertEquals("/storage/emulated/0/Android/data/com.game/SAVEDATA/src/a.py", local("./src/a.py"))
        assertEquals("/storage/emulated/0/Android/data/com.game/b.txt", local("../b.txt"))
    }

    @Test fun absoluteAndFileUrisAndRawRootPaths() {
        assertEquals("/sdcard/x.txt", local("/sdcard/x.txt"))
        assertEquals("/sdcard/x.txt", local("file:///sdcard/x.txt"))
        assertEquals("/storage/emulated/0/a b.md", local("/data/media/0/a%20b.md"))
        assertEquals("/storage/emulated/0/c++.txt", local("/sdcard/../storage/emulated/0/c++.txt"))
    }

    @Test fun lineSuffixesAreDropped() {
        assertEquals("$EMU/main.py", local("main.py:12"))
        assertEquals("$EMU/main.py", local("main.py:12:3"))
        assertEquals("$EMU/main.py", local("main.py#L4-L9"))
        assertEquals("$EMU/main.py", local("<main.py>"))
    }

    @Test fun webAndOtherSchemes() {
        assertEquals(LinkTarget.Web("https://example.com/a?b=1"), FileLinks.resolve("https://example.com/a?b=1", base))
        assertEquals(LinkTarget.Web("https://www.example.com"), FileLinks.resolve("www.example.com", base))
        assertEquals(LinkTarget.External("mailto:a@b.c"), FileLinks.resolve("mailto:a@b.c", base))
        assertNull(FileLinks.resolve("javascript:alert(1)", base))
        assertNull(FileLinks.resolve("intent://x#Intent;end", base))
        assertNull(FileLinks.resolve("ftp://host/x", base))
        assertNull(FileLinks.resolve("#section", base))
        assertNull(FileLinks.resolve("  ", base))
    }

    @Test fun normalizeNeverLeavesRoot() {
        assertEquals("/", FileLinks.normalize("/../.."))
        assertEquals("/a/c", FileLinks.normalize("/a/./b/../c/"))
    }

    @Test fun cappedReadsAndDecoding() {
        val (head, more) = ViewerBytes.readCapped("abcdef".byteInputStream(), 4)
        assertEquals("abcd", String(head)); assertTrue(more)
        val (all, rest) = ViewerBytes.readCapped("abc".byteInputStream(), 4)
        assertEquals("abc", String(all)); assertFalse(rest)

        val zh = "中文".toByteArray(Charsets.UTF_8)
        assertEquals("中", ViewerBytes.decode(zh.copyOf(4), truncated = true).first)
        assertEquals("中文", ViewerBytes.decode("中文".toByteArray(charset("GB18030")), truncated = false).first)
        assertEquals("x", ViewerBytes.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'x'.code.toByte()), false).first)
        assertTrue(ViewerBytes.looksBinary(byteArrayOf(1, 0, 2)))
        assertFalse(ViewerBytes.looksBinary("text".toByteArray()))
    }

    private companion object { const val EMU = "/storage/emulated/0/Android/data/com.game/SAVEDATA" }
}
