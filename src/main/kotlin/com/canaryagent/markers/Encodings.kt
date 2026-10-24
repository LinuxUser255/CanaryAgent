package com.canaryagent.markers

import java.util.Base64

/** Decoders used by [MarkerScanner]. Lenient: malformed input is returned unchanged or skipped. */
internal object Encodings {

    /** Resolves JSON string escapes (`\uXXXX`, `\"`, `\\`, `\/`, `\n`, ...) anywhere in [s]. */
    fun unescapeJson(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            when (val n = s[i + 1]) {
                'u' -> {
                    if (i + 6 <= s.length) {
                        val hex = s.substring(i + 2, i + 6)
                        val cp = hex.toIntOrNull(16)
                        if (cp != null) { sb.append(cp.toChar()); i += 6; continue }
                    }
                    sb.append(c); i++
                }
                '"', '\\', '/' -> { sb.append(n); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** Decodes `%XX` sequences to single (latin-1) chars. `+` is left alone. */
    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 3 <= s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { sb.append(v.toChar()); i += 3; continue }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /**
     * Decodes a run of base64 characters (standard or URL-safe, padded or not) to a latin-1 string.
     * Trailing characters that cannot form a full quantum are dropped. Returns null if undecodable.
     */
    fun decodeBase64Run(run: String): String? {
        var r = run.trimEnd('=')
        if (r.length % 4 == 1) r = r.dropLast(1)
        if (r.length < 4) return null
        val decoder = if (r.indexOf('-') >= 0 || r.indexOf('_') >= 0) Base64.getUrlDecoder() else Base64.getDecoder()
        return try {
            String(decoder.decode(r), Charsets.ISO_8859_1)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
