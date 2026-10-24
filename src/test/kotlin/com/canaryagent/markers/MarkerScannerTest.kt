package com.canaryagent.markers

import com.canaryagent.Corpus
import com.canaryagent.model.MarkerEncoding
import com.canaryagent.model.MarkerKind
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkerScannerTest {
    private val token = "cnry7f3a9k2m"
    private val tracker = MarkerTracker().apply { place(token, MarkerKind.TOKEN, null) }
    private val scanner = MarkerScanner(tracker)

    private fun only(text: String): MarkerMatch {
        val matches = scanner.scan(text)
        assertEquals(1, matches.size, "expected exactly one match in: $text -> $matches")
        return matches.single()
    }

    @Test
    fun `raw token is found with raw encoding`() {
        val m = only("""{"text":"please read cnry7f3a9k2m now"}""")
        assertEquals(token, m.token)
        assertEquals(setOf(MarkerEncoding.RAW), m.encodings)
        assertFalse(m.inToolCall)
    }

    @Test
    fun `re-cased token is reported as case-changed`() {
        val m = only("The agent said CNRY7F3A9K2M.")
        assertEquals(setOf(MarkerEncoding.CASE_CHANGED), m.encodings)
    }

    @Test
    fun `token glued to other alphanumerics is still found`() {
        assertEquals(token, only("xcnry7f3a9k2mzz").token)
    }

    @Test
    fun `prefix alone or unknown suffix does not match`() {
        assertTrue(scanner.scan("cnry cnry cnryabcdefgh cnry7f3a9k2").isEmpty())
    }

    @Test
    fun `nothing tracked means nothing scanned`() {
        assertTrue(MarkerScanner(MarkerTracker()).scan("cnry7f3a9k2m").isEmpty())
    }

    @Test
    fun `json unicode escapes are decoded`() {
        // Build the escaped form at runtime so no compiler or shell can resolve the escapes early.
        val escaped = "{\"content\":\"" + "\\u0063" + "\\u006e" + "ry7f3a9k2m\"}"
        assertFalse(escaped.contains("cnry"), "test input must not contain the plain token: $escaped")
        val m = only(escaped)
        assertEquals(setOf(MarkerEncoding.JSON_ESCAPED), m.encodings)
    }

    @Test
    fun `percent encoding is decoded`() {
        val m = only("q=%63%6Ery7f3a9k2m&x=1")
        assertEquals(setOf(MarkerEncoding.URL_ENCODED), m.encodings)
    }

    @Test
    fun `base64 is decoded at every alignment`() {
        for (pad in listOf("", "a", "ab", "abc", "abcd")) {
            val b64 = Base64.getEncoder().encodeToString("$pad the marker is $token ok".toByteArray())
            val m = only("""{"blob":"$b64"}""")
            assertEquals(setOf(MarkerEncoding.BASE64), m.encodings, "pad='$pad'")
        }
    }

    @Test
    fun `url-safe unpadded base64 is decoded`() {
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString("??>>$token>>??".toByteArray())
        assertEquals(setOf(MarkerEncoding.BASE64), only(b64).encodings)
    }

    @Test
    fun `token inside openai tool_calls arguments is a tool call`() {
        val body = """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"c1","type":"function",
            "function":{"name":"search_docs","arguments":"{\"query\":\"cnry7f3a9k2m\"}"}}]}}]}"""
        assertTrue(only(body).inToolCall)
    }

    @Test
    fun `token inside anthropic tool_use input is a tool call`() {
        val body = """{"content":[{"type":"tool_use","id":"t1","name":"fetch","input":{"url":"http://x/cnry7f3a9k2m"}}]}"""
        assertTrue(only(body).inToolCall)
    }

    // ---- tool results (F4): delivery of read content to the model -----------------------------------

    /** The 2026-10-21 run: a forum post reached the model as an OpenAI `role: tool` message. */
    @Test
    fun `token in an openai tool message is a tool result`() {
        val m = only(Corpus.read("openai_request_tool_result.json").replace("TOKEN", token))
        assertTrue(m.inToolResult)
        assertFalse(m.inToolCall)
    }

    @Test
    fun `token in an anthropic tool_result block is a tool result`() {
        val body = """{"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1",
            "content":[{"type":"text","text":"page says cnry7f3a9k2m"}]}]}]}"""
        assertTrue(only(body).inToolResult)
    }

    @Test
    fun `token in a responses-api function_call_output is a tool result`() {
        val body = """{"input":[{"type":"function_call_output","call_id":"c1","output":"{\"post\":\"cnry7f3a9k2m\"}"}]}"""
        val m = only(body)
        assertTrue(m.inToolResult)
    }

    @Test
    fun `token in a functionResponse part is a tool result`() {
        val body = """{"contents":[{"role":"user","parts":[{"functionResponse":{"name":"get_post","response":{"text":"cnry7f3a9k2m"}}}]}]}"""
        assertTrue(only(body).inToolResult)
    }

    /** The user typing the marker into the chat is exactly what a tool result must be told apart from. */
    @Test
    fun `token in a user or assistant message is not a tool result`() {
        val user = """{"messages":[{"role":"user","content":"remember cnry7f3a9k2m"}]}"""
        assertFalse(only(user).inToolResult)
        val assistant = """{"choices":[{"message":{"role":"assistant","content":"I saw cnry7f3a9k2m"}}]}"""
        assertFalse(only(assistant).inToolResult)
    }

    @Test
    fun `token both delivered and acted on is flagged both ways`() {
        val body = """{"messages":[{"role":"tool","tool_call_id":"c1","content":"cnry7f3a9k2m"},
            {"role":"assistant","tool_calls":[{"id":"c2","type":"function","function":{"name":"fetch","arguments":"{\"u\":\"cnry7f3a9k2m\"}"}}]}]}"""
        val m = only(body)
        assertTrue(m.inToolResult)
        assertTrue(m.inToolCall)
    }

    @Test
    fun `token in assistant text content is a response not a tool call`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"I found cnry7f3a9k2m in the doc"}}]}"""
        assertFalse(only(body).inToolCall)
    }

    @Test
    fun `sse fragments are parsed for tool-call context`() {
        val sse = """
            event: message
            data: {"choices":[{"delta":{"role":"assistant"}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{\"q\":\"CNRY7F3A9K2M\"}"}}]}}]}

            data: [DONE]
        """.trimIndent()
        val m = only(sse)
        assertTrue(m.inToolCall)
        assertEquals(setOf(MarkerEncoding.CASE_CHANGED), m.encodings)
    }

    @Test
    fun `base64 token inside arguments is still classified as tool call`() {
        val b64 = Base64.getEncoder().encodeToString("payload $token".toByteArray())
        val body = """{"tool_calls":[{"function":{"name":"x","arguments":"{\"data\":\"$b64\"}"}}]}"""
        val m = only(body)
        assertTrue(m.inToolCall)
        assertEquals(setOf(MarkerEncoding.BASE64), m.encodings)
    }

    @Test
    fun `custom tokens without prefix are matched case-insensitively`() {
        val t = MarkerTracker().apply { place("Needle42", MarkerKind.CUSTOM, null) }
        val m = MarkerScanner(t).scan("found NEEDLE42 here").single()
        assertEquals("needle42", m.token)
        assertEquals(setOf(MarkerEncoding.CASE_CHANGED), m.encodings)
    }

    @Test
    fun `multiple tokens in one message are all reported`() {
        tracker.place("cnryzzzzzzzz", MarkerKind.TOKEN, null)
        val matches = scanner.scan("a cnry7f3a9k2m b cnryzzzzzzzz").map { it.token }.toSet()
        assertEquals(setOf(token, "cnryzzzzzzzz"), matches)
    }

    @Test
    fun `full http message with a separate body is classified from the body`() {
        val body = """{"tool_calls":[{"function":{"name":"x","arguments":"{\"q\":\"cnry7f3a9k2m\"}"}}]}"""
        val message = "POST /post HTTP/1.1\r\nHost: httpbin.org\r\nContent-Type: application/json\r\n\r\n$body"
        val m = scanner.scan(message, body).single()
        assertTrue(m.inToolCall, "tool-call context must be found when the body is supplied separately")
        assertEquals(setOf(MarkerEncoding.RAW), m.encodings)
    }

    @Test
    fun `tool call wins when the first occurrence is outside tool keys`() {
        // httpbin echo: raw body string first, parsed JSON second
        val body = """{"data":"{\"tool_calls\":[{\"function\":{\"name\":\"x\",\"arguments\":\"{\\\"q\\\":\\\"cnry7f3a9k2m\\\"}\"}}]}",
            "json":{"tool_calls":[{"function":{"name":"x","arguments":"{\"q\":\"cnry7f3a9k2m\"}"}}]},"url":"https://httpbin.org/post"}"""
        assertTrue(only(body).inToolCall)
    }

    @Test
    fun `raw and base64 occurrences in one message report both encodings`() {
        val b64 = Base64.getEncoder().encodeToString("the marker is $token ok".toByteArray())
        val m = only("""{"plain":"$token","blob":"$b64"}""")
        assertEquals(setOf(MarkerEncoding.RAW, MarkerEncoding.BASE64), m.encodings)
    }

    @Test
    fun `token followed by extra alphanumerics is still exactly the token`() {
        val m = only("id=cnry7f3a9k2m62")
        assertEquals(token, m.token)
        assertEquals(setOf(MarkerEncoding.RAW), m.encodings)
    }

    @Test
    fun `input beyond the size cap is ignored`() {
        val small = MarkerScanner(tracker, maxChars = 100)
        assertTrue(small.scan("x".repeat(100) + token).isEmpty())
        assertEquals(1, small.scan(token + "x".repeat(200)).size)
    }
}
