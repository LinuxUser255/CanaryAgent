package com.canaryagent.annotation

import com.canaryagent.util.JsonSupport
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Turns an HTTP body into parsed JSON roots for key matching, with guards:
 * text-like content types only, a size cap, and lenient handling of JSON, NDJSON, SSE `data:` lines
 * and form-encoded bodies (whose values are parsed as JSON when they look like it).
 */
class BodyParser(private val maxChars: Int = DEFAULT_MAX_CHARS) {

    companion object {
        const val DEFAULT_MAX_CHARS = 2 * 1024 * 1024
        private val TEXT_LIKE = listOf("text/", "json", "x-www-form-urlencoded", "event-stream", "ndjson")
    }

    /** Parsed roots (JSONObject / JSONArray). Empty when the body is too large, binary, or not structured. */
    fun parse(body: String, contentType: String?): List<Any> {
        if (body.isEmpty() || body.length > maxChars) return emptyList()
        if (!isTextLike(contentType)) return emptyList()
        val ct = contentType?.lowercase() ?: ""
        if (ct.contains("x-www-form-urlencoded")) return listOfNotNull(parseForm(body))

        val fragments = JsonSupport.parseFragments(body)
        if (fragments.isNotEmpty()) return fragments
        if (ct.isEmpty() && looksLikeForm(body)) return listOfNotNull(parseForm(body))
        return emptyList()
    }

    /** Unknown content type is allowed through; the body shape decides. */
    fun isTextLike(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return true
        val c = contentType.lowercase()
        return TEXT_LIKE.any { c.contains(it) }
    }

    private fun looksLikeForm(body: String): Boolean {
        val head = body.take(512)
        return head.indexOf('=') > 0 && head.none { it == '<' || it == '{' || it == '\n' }
    }

    /** `a=1&b=%7B...%7D` -> JSONObject {a: "1", b: <parsed>}. Values that parse as JSON are nested. */
    private fun parseForm(body: String): JSONObject? {
        if (body.indexOf('=') < 0) return null
        val obj = JSONObject()
        var pairs = 0
        for (pair in body.split('&')) {
            if (pair.isEmpty()) continue
            if (++pairs > MAX_FORM_PAIRS) break
            val eq = pair.indexOf('=')
            val name = decode(if (eq < 0) pair else pair.substring(0, eq))
            val value = if (eq < 0) "" else decode(pair.substring(eq + 1))
            obj.put(name, JsonSupport.parseLenient(value) ?: value)
        }
        return if (obj.isEmpty) null else obj
    }

    private fun decode(s: String): String =
        try { URLDecoder.decode(s, Charsets.UTF_8) } catch (_: IllegalArgumentException) { s }

    private val MAX_FORM_PAIRS = 500
}
