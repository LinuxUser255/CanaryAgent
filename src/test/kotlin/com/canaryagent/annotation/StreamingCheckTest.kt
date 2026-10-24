package com.canaryagent.annotation

import com.canaryagent.Corpus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingCheckTest {
    private val sse = "text/event-stream; charset=utf-8"

    @Test
    fun `complete sse stream is not truncated`() {
        // A real stream ends its last event with a blank line.
        val body = Corpus.read("openai_sse_tool_calls.txt").trimEnd() + "\n\n"
        assertFalse(StreamingCheck.looksTruncated(sse, "chunked", body))
        assertFalse(StreamingCheck.looksTruncated(sse, null, "data: {\"a\":1}\r\n\r\n"))
    }

    @Test
    fun `sse cut mid-event or empty is truncated`() {
        assertTrue(StreamingCheck.looksTruncated(sse, "chunked", "data: {\"choices\":[{\"delta\":{\"content\":\"hel"))
        assertTrue(StreamingCheck.looksTruncated(sse, "chunked", "data: {\"a\":1}\n"))
        assertTrue(StreamingCheck.looksTruncated(sse, null, ""))
    }

    @Test
    fun `empty chunked text bodies are truncated, binary and complete bodies are not`() {
        assertTrue(StreamingCheck.looksTruncated("application/json", "chunked", ""))
        assertTrue(StreamingCheck.looksTruncated(null, "chunked", ""))
        assertFalse(StreamingCheck.looksTruncated("image/png", "chunked", ""))
        assertFalse(StreamingCheck.looksTruncated("application/json", "chunked", "{\"a\":1}"))
        assertFalse(StreamingCheck.looksTruncated("application/json", null, ""))
    }
}
