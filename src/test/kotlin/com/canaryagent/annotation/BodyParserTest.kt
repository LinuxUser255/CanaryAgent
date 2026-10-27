package com.canaryagent.annotation

import com.canaryagent.Corpus
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BodyParserTest {
    private val parser = BodyParser()

    @Test
    fun `plain json yields one root`() {
        val roots = parser.parse("""{"a":1}""", "application/json; charset=utf-8")
        assertEquals(1, roots.size)
        assertTrue(roots[0] is JSONObject)
    }

    @Test
    fun `sse data lines yield one root per json fragment`() {
        val roots = parser.parse(Corpus.read("openai_sse_tool_calls.txt"), "text/event-stream")
        assertEquals(3, roots.size)
    }

    @Test
    fun `ndjson yields one root per line`() {
        val roots = parser.parse(Corpus.read("ndjson_ollama_stream.txt"), "application/x-ndjson")
        assertEquals(3, roots.size)
    }

    @Test
    fun `form bodies become an object with nested json values parsed`() {
        val roots = parser.parse(Corpus.read("form_encoded_messages.txt").trim(), "application/x-www-form-urlencoded")
        val obj = roots.single() as JSONObject
        assertEquals("42", obj.getString("conversation_id"))
        assertTrue(obj.get("messages") is JSONArray)
    }

    @Test
    fun `form body without a content type is detected by shape`() {
        val roots = parser.parse("a=1&b=2", null)
        assertEquals("1", (roots.single() as JSONObject).getString("a"))
    }

    @Test
    fun `binary content types and oversized bodies are skipped`() {
        assertTrue(parser.parse("""{"a":1}""", "image/png").isEmpty())
        assertTrue(parser.parse("""{"a":1}""", "application/octet-stream").isEmpty())
        assertTrue(BodyParser(maxChars = 5).parse("""{"a":1}""", "application/json").isEmpty())
    }

    @Test
    fun `html and prose are not parsed`() {
        assertTrue(parser.parse(Corpus.read("bait_html.txt"), "text/html").isEmpty())
        assertTrue(parser.parse("just some text with = in it\nand a newline", null).isEmpty())
    }

    @Test
    fun `a precomputed parse is reused instead of parsing again`() {
        val precomputed = listOf<Any>(JSONObject("""{"shared":true}"""))
        val roots = parser.parse("""{"a":1}""", "application/json", precomputed)
        assertEquals(precomputed, roots)
    }

    @Test
    fun `gates still apply with a precomputed parse`() {
        val precomputed = listOf<Any>(JSONObject("""{"shared":true}"""))
        // Content-type gate: a non-text-like type stays empty whatever was precomputed.
        assertTrue(parser.parse("""{"a":1}""", "image/png", precomputed).isEmpty())
        // Form bodies keep their own parse; the precomputed JSON roots do not replace it.
        val form = parser.parse("a=1&b=2", "application/x-www-form-urlencoded", precomputed)
        assertEquals(1, form.size)
        assertEquals("1", (form[0] as JSONObject).getString("a"))
    }
}
