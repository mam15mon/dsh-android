package io.github.jackwener.dsh.feature.viewer

import android.content.Context
import io.github.jackwener.dsh.core.capability.tools.PrivateStorage
import io.github.jackwener.dsh.nativeextra.NativeRoot
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Where a tapped Markdown link leads: a web page, another app's URI, or a file on this phone. */
sealed interface LinkTarget {
    data class Web(val url: String) : LinkTarget
    data class External(val uri: String) : LinkTarget
    data class Local(val path: String) : LinkTarget
}

/**
 * Resolves link targets in replies. Models link files the way they name them: `AGENTS.md`,
 * `src/a.py:12`, `/data/media/0/…` (a Root project), `file:///sdcard/x.txt`. Pure, unit-tested.
 */
object FileLinks {
    private val WEB = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    /** Schemes handed to other apps; anything else with a scheme (javascript:, intent:, …) is ignored. */
    private val EXTERNAL = setOf("mailto", "tel", "sms", "geo", "market", "content")
    private val LINE_SUFFIX = Regex(":\\d+(?::\\d+)?$")
    private val LINE_FRAGMENT = Regex("#L\\d+(?:-L?\\d+)?$", RegexOption.IGNORE_CASE)

    /** The target of [raw]; relative paths are resolved against [base]. Null when there is nothing to open. */
    fun resolve(raw: String, base: String): LinkTarget? {
        val s = raw.trim().removeSurrounding("<", ">")
        if (s.isEmpty() || s.startsWith("#")) return null
        if (WEB.containsMatchIn(s)) return LinkTarget.Web(s)
        if (s.startsWith("www.", ignoreCase = true)) return LinkTarget.Web("https://$s")
        val scheme = SCHEME.find(s)?.groupValues?.get(1)?.lowercase()
        var p = when {
            scheme == "file" -> s.substring(5).let { if (it.startsWith("//")) it.removePrefix("//").removePrefix("localhost") else it }
            scheme in EXTERNAL -> return LinkTarget.External(s)
            // "AGENTS.md:12" also starts like a scheme; a real one is followed by "//" or is a known word.
            scheme != null && (s.startsWith("$scheme://") || scheme in setOf("javascript", "intent", "data", "about", "blob")) -> return null
            else -> s
        }
        p = decode(p).replace(LINE_FRAGMENT, "").replace(LINE_SUFFIX, "")
        if (p.isEmpty()) return null
        val absolute = if (p.startsWith("/")) p else "${base.trimEnd('/')}/$p"
        return LinkTarget.Local(PrivateStorage.emulated(normalize(absolute)))
    }

    /** `/a/./b/../c` → `/a/c`; never above `/`. */
    fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        for (seg in path.split('/')) when (seg) {
            "", "." -> {}
            ".." -> out.removeLastOrNull()
            else -> out.addLast(seg)
        }
        return "/" + out.joinToString("/")
    }

    private fun decode(s: String): String =
        if ('%' !in s) s else runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** The folder relative links start from: the Root project when one is in use, else the engine's home. */
    fun workspace(context: Context): String {
        val selected = NativeRoot.selected(context)
        return if (selected.isNotEmpty() && NativeRoot.enabled(context)) selected else File(context.filesDir, "home").path
    }
}

/** Byte helpers of the file viewer. Pure, unit-tested. */
object ViewerBytes {
    /** At most [cap] bytes of [input] (closed afterwards), and whether more were left. */
    fun readCapped(input: InputStream, cap: Int): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        var more = false
        input.use { s ->
            val buf = ByteArray(64 * 1024)
            var n = s.read(buf)
            while (n >= 0) {
                if (out.size() + n > cap) { out.write(buf, 0, cap - out.size()); more = true; break }
                out.write(buf, 0, n)
                n = s.read(buf)
            }
        }
        return out.toByteArray() to more
    }

    fun looksBinary(bytes: ByteArray): Boolean = (0 until minOf(bytes.size, 8192)).any { bytes[it] == 0.toByte() }

    /** UTF-8 when it decodes cleanly (a character cut by [truncated] is allowed), else GB18030. */
    fun decode(bytes: ByteArray, truncated: Boolean): Pair<String, Charset> {
        fun strict(len: Int) = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, len)).toString()
        }.getOrNull()
        val utf8 = strict(bytes.size) ?: if (truncated) (1..3).firstNotNullOfOrNull { cut -> if (bytes.size > cut) strict(bytes.size - cut) else null } else null
        if (utf8 != null) return utf8.removePrefix("﻿") to Charsets.UTF_8
        val gb = Charset.forName("GB18030")
        return String(bytes, gb) to gb
    }
}
