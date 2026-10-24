package com.canaryagent.contextmenu

import com.canaryagent.contextmenu.MarkerInserter.CaretRegion
import com.canaryagent.contextmenu.MarkerInserter.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Caret classification on raw bytes; no Burp needed. */
class MarkerInserterTest {
    private val request = "POST /api/chat HTTP/1.1\r\nHost: app.local\r\nX-Note: hello world\r\n\r\n{\"content\":\"hi\"}"
    private val raw = request.toByteArray(Charsets.ISO_8859_1)
    private val bodyOffset = request.indexOf("\r\n\r\n") + 4

    private fun at(text: String, within: String = request): Int = within.indexOf(text).also { check(it >= 0) { text } }

    private fun plan(offset: Int, end: Int = offset, isRequest: Boolean = true) =
        MarkerInserter.plan(raw, bodyOffset, offset, end, isRequest)

    @Test
    fun `offset 0 is the request line and is refused`() {
        assertEquals(CaretRegion.StartLine, MarkerInserter.classify(raw, bodyOffset, 0))
        assertEquals(Plan.Refuse(MarkerInserter.REFUSE_REQUEST), plan(0))
        // Anywhere on the first line, including its end, is still the request line.
        assertEquals(Plan.Refuse(MarkerInserter.REFUSE_REQUEST), plan(at("HTTP/1.1") + 8))
    }

    @Test
    fun `inside a header name is refused`() {
        val inHost = at("Host:") + 2
        assertEquals(CaretRegion.HeaderName("Host"), MarkerInserter.classify(raw, bodyOffset, inHost))
        assertEquals(Plan.Refuse(MarkerInserter.REFUSE_REQUEST), plan(inHost))
        // Right before the colon inserts into the name too.
        assertEquals(Plan.Refuse(MarkerInserter.REFUSE_REQUEST), plan(at("Host:") + 4))
    }

    @Test
    fun `inside a header value inserts and records header Name`() {
        val inValue = at("app.local") + 3
        assertEquals(CaretRegion.HeaderValue("Host"), MarkerInserter.classify(raw, bodyOffset, inValue))
        val p = assertIs<Plan.Insert>(plan(inValue))
        assertEquals("header Host", p.location)
        assertEquals(false, p.inBody)

        // Just after the colon (before the space) and at the very end of the value both count as the value.
        assertEquals("header X-Note", assertIs<Plan.Insert>(plan(at("X-Note:") + 7)).location)
        assertEquals("header X-Note", assertIs<Plan.Insert>(plan(at("hello world") + 11)).location)
    }

    @Test
    fun `inside the body inserts and records body`() {
        val inBody = at("\"hi\"") + 1
        assertEquals(CaretRegion.Body, MarkerInserter.classify(raw, bodyOffset, inBody))
        val p = assertIs<Plan.Insert>(plan(inBody))
        assertEquals("body", p.location)
        assertEquals(true, p.inBody)
        // The first body byte and the end of the message are body positions as well.
        assertEquals("body", assertIs<Plan.Insert>(plan(bodyOffset)).location)
        assertEquals("body", assertIs<Plan.Insert>(plan(raw.size)).location)
    }

    @Test
    fun `line breaks and the blank line are refused`() {
        val crlfAfterHost = at("app.local") + 9 // caret before CR: still the value
        assertEquals(CaretRegion.HeaderValue("Host"), MarkerInserter.classify(raw, bodyOffset, crlfAfterHost))
        assertEquals(CaretRegion.Separator, MarkerInserter.classify(raw, bodyOffset, crlfAfterHost + 1)) // between CR and LF
        assertEquals(CaretRegion.Separator, MarkerInserter.classify(raw, bodyOffset, bodyOffset - 2)) // the blank line
        assertEquals(CaretRegion.Separator, MarkerInserter.classify(raw, bodyOffset, bodyOffset - 1))
        assertEquals(Plan.Refuse(MarkerInserter.REFUSE_REQUEST), plan(bodyOffset - 2))
    }

    @Test
    fun `a selection must stay inside one region`() {
        val s = at("app.local")
        assertEquals("header Host", assertIs<Plan.Insert>(plan(s, s + 9)).location)
        // Selecting through the line break would eat the CR.
        assertEquals(Plan.Refuse(MarkerInserter.REFUSE_REQUEST), plan(s, s + 10))
        assertEquals("body", assertIs<Plan.Insert>(plan(bodyOffset, raw.size)).location)
        assertEquals(Plan.Refuse(MarkerInserter.REFUSE_REQUEST), plan(s, raw.size))
    }

    @Test
    fun `responses accept the body only`() {
        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}"
        val bytes = response.toByteArray(Charsets.ISO_8859_1)
        val body = response.indexOf("\r\n\r\n") + 4
        assertEquals(Plan.Refuse(MarkerInserter.REFUSE_RESPONSE), MarkerInserter.plan(bytes, body, 0, isRequest = false))
        val inValue = response.indexOf("application") + 2
        assertEquals(Plan.Refuse(MarkerInserter.REFUSE_RESPONSE), MarkerInserter.plan(bytes, body, inValue, isRequest = false))
        assertEquals("body", assertIs<Plan.Insert>(MarkerInserter.plan(bytes, body, body + 1, isRequest = false)).location)
    }

    @Test
    fun `bare LF line endings and a header without a colon are handled`() {
        val lf = "GET / HTTP/1.1\nHost: h\nBroken header line\n\nbody"
        val bytes = lf.toByteArray(Charsets.ISO_8859_1)
        val body = lf.indexOf("\n\n") + 2
        assertEquals(CaretRegion.HeaderValue("Host"), MarkerInserter.classify(bytes, body, lf.indexOf("h\n")))
        assertEquals(CaretRegion.HeaderName("Broken header line"), MarkerInserter.classify(bytes, body, lf.indexOf("Broken") + 3))
        assertEquals(CaretRegion.Body, MarkerInserter.classify(bytes, body, body))
    }
}
