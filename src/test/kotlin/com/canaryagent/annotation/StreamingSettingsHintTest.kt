package com.canaryagent.annotation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingSettingsHintTest {
    private fun options(eventStream: Boolean, prefixes: String = "[]") = """
        {"project_options":{"http":{
          "redirections":{"understand_which_redirections":true},
          "streaming_responses":{
            "scope":{"advanced_mode":false,"include":$prefixes},
            "store":true,
            "strip_chunked_encoding_metadata":true,
            "treat_event_stream_as_streaming":$eventStream
          }
        }}}
    """.trimIndent()

    /** Exact shape exported by Burp Suite Professional 2026.9.2 (self-test E1, 2026-10-13). */
    private val burpReal = """
        {"project_options":{"http":{"streaming_responses":{
            "scope_advanced_mode":false,
            "store":true,
            "strip_chunked_encoding_metadata":true,
            "urls":[{"enabled":true,"include_subdomains":false,"prefix":"echo.websocket.org/"}],
            "use_text_event_stream_header":true
        }}}}
    """.trimIndent()

    @Test
    fun `real burp export is parsed`() {
        val f = StreamingSettingsHint.analyze(burpReal)!!
        assertEquals("project_options.http.streaming_responses", f.sectionKey)
        assertTrue(f.eventStreamAsStreaming)
        assertEquals(listOf("echo.websocket.org/"), f.prefixes)
        assertTrue(f.hidesSseResponses)
    }

    @Test
    fun `real burp export with everything off is quiet`() {
        val off = burpReal.replace("\"use_text_event_stream_header\":true", "\"use_text_event_stream_header\":false")
            .replace("\"enabled\":true", "\"enabled\":false")
        val f = StreamingSettingsHint.analyze(off)!!
        assertFalse(f.hidesSseResponses)
    }

    @Test
    fun `default burp settings hide sse responses`() {
        val f = StreamingSettingsHint.analyze(options(eventStream = true))!!
        assertTrue(f.eventStreamAsStreaming)
        assertTrue(f.prefixes.isEmpty())
        assertTrue(f.hidesSseResponses)
        assertEquals("project_options.http.streaming_responses", f.sectionKey)
        assertTrue(f.message().contains("text/event-stream"))
    }

    @Test
    fun `enabled prefixes are listed and disabled ones ignored`() {
        val json = options(eventStream = false, prefixes = """[{"enabled":true,"prefix":"echo.websocket.org/"},{"enabled":false,"prefix":"api.openai.com/"},{"enabled":true,"prefix":"api.anthropic.com/v1/messages"}]""")
        val f = StreamingSettingsHint.analyze(json)!!
        assertFalse(f.eventStreamAsStreaming)
        assertEquals(listOf("echo.websocket.org/", "api.anthropic.com/v1/messages"), f.prefixes)
        assertTrue(f.hidesSseResponses)
        assertTrue(f.message().contains("api.anthropic.com/v1/messages"))
    }

    @Test
    fun `nothing configured means nothing hidden`() {
        val f = StreamingSettingsHint.analyze(options(eventStream = false))!!
        assertFalse(f.hidesSseResponses)
    }

    @Test
    fun `plain string prefixes and differently named flags are tolerated`() {
        val json = """{"project_options":{"http":{"streaming":{"event_stream_mime_type":true,"urls":["llm.local/"]}}}}"""
        val f = StreamingSettingsHint.analyze(json)!!
        assertTrue(f.eventStreamAsStreaming)
        assertEquals(listOf("llm.local/"), f.prefixes)
    }

    @Test
    fun `missing section or bad json yields null`() {
        assertNull(StreamingSettingsHint.analyze("""{"project_options":{"http":{"redirections":{}}}}"""))
        assertNull(StreamingSettingsHint.analyze("not json"))
        assertNull(StreamingSettingsHint.analyze(""))
    }
}
