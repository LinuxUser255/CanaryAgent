package com.canaryagent.ui

import com.canaryagent.model.AnnotatedTrafficItem
import com.canaryagent.model.Attribution
import com.canaryagent.model.AnnotationSource
import com.canaryagent.model.MarkerEncoding
import com.canaryagent.model.MarkerHit
import com.canaryagent.model.MarkerKind
import com.canaryagent.model.MarkerOrigin
import com.canaryagent.model.MarkerStatus
import com.canaryagent.model.ToolEntry
import com.canaryagent.model.TrafficChannel
import com.canaryagent.model.TrafficRef
import com.canaryagent.util.Timestamps
import kotlin.test.Test
import kotlin.test.assertEquals

class ColumnsTest {
    private fun <T> values(columns: List<Column<T>>, row: T): Map<String, Any?> = columns.associate { it.name to it.value(row) }

    @Test
    fun `tool columns`() {
        val v = values(Columns.tools, ToolEntry("search_docs", sortedSetOf("limit", "query"), 3, 1_000L, 2_000L, "https://api/x"))
        assertEquals("search_docs", v["Tool"])
        assertEquals("limit, query", v["Parameters"])
        assertEquals(3, v["Messages"])
        assertEquals(Timestamps.hms(1_000L), v["First seen"])
        assertEquals("https://api/x", v["Source"])
    }

    @Test
    fun `marker columns before and after a hit`() {
        val origin = MarkerOrigin("Repeater", "POST", "app.local", "/api/docs", "body")
        val placed = MarkerHit("cnry7f3a9k2m", "cnry7f3a9k2m", MarkerKind.TOKEN, origin, 1_000L)
        val p = values(Columns.markers, placed)
        assertEquals("placed", p["Status"])
        assertEquals("POST app.local/api/docs (body)", p["Placed at"])
        assertEquals("", p["Last hit"])
        assertEquals(0, p["Hits"])

        val hit = placed.copy(
            status = MarkerStatus.SEEN_IN_TOOL_CALL,
            encodings = setOf(MarkerEncoding.BASE64, MarkerEncoding.RAW),
            hitCount = 2,
            lastHitAt = 2_000L,
            lastHit = TrafficRef(TrafficChannel.HTTP_RESPONSE, "Proxy", "POST", "https://h/p", 2_000L, messageId = "#1"),
        )
        val h = values(Columns.markers, hit)
        assertEquals("seen in tool call", h["Status"])
        assertEquals("raw, base64", h["Encodings"]) // declaration order, whatever the insertion order
        assertEquals("${Timestamps.hms(2_000L)} response POST https://h/p", h["Last hit"])

        val ws = hit.copy(lastHit = TrafficRef(TrafficChannel.WS_IN, "Proxy", "", "wss://h/.ws", 2_000L))
        assertEquals("${Timestamps.hms(2_000L)} ws<- wss://h/.ws", values(Columns.markers, ws)["Last hit"])
    }

    @Test
    fun `attribution column shows the labelled interaction's label and signals`() {
        val placed = MarkerHit("k3j2h1", "k3j2h1.oastify.com", MarkerKind.COLLABORATOR, null, 1_000L)
        assertEquals("", values(Columns.markers, placed)["Attribution"])
        val inBand = placed.copy(lastHit = TrafficRef(TrafficChannel.HTTP_RESPONSE, "Proxy", "POST", "https://h/p", 2_000L))
        assertEquals("", values(Columns.markers, inBand)["Attribution"])
        val fetch = TrafficRef(
            TrafficChannel.COLLABORATOR, "Collaborator", "HTTP", "from 203.0.113.9:80", 3_000L,
            attribution = Attribution.BROWSER, attributionSignals = listOf("Sec-Fetch-Mode: navigate", "path /favicon.ico"),
        )
        val oob = placed.copy(status = MarkerStatus.OOB_INTERACTION, lastHit = fetch, labelledInteraction = fetch)
        assertEquals("browser-attributed [Sec-Fetch-Mode: navigate, path /favicon.ico]", values(Columns.markers, oob)["Attribution"])
        val dns = TrafficRef(TrafficChannel.COLLABORATOR, "Collaborator", "DNS", "from 10.0.0.5:53", 3_000L, attribution = Attribution.UNATTRIBUTED)
        assertEquals("unattributed", values(Columns.markers, placed.copy(lastHit = dns, labelledInteraction = dns))["Attribution"])
    }

    /** 2026-10-21 checklist 10: a forum page listing the payload replaced the last hit and blanked the cell. */
    @Test
    fun `attribution column survives a later in-band hit`() {
        val fetch = TrafficRef(
            TrafficChannel.COLLABORATOR, "Collaborator", "HTTP", "from 203.0.113.9:80", 3_000L,
            attribution = Attribution.BROWSER, attributionSignals = listOf("Sec-Fetch-Mode: navigate"),
        )
        val pageView = TrafficRef(TrafficChannel.HTTP_RESPONSE, "Proxy", "GET", "https://app/community/posts/recent", 4_000L)
        val row = MarkerHit(
            "k3j2h1", "k3j2h1.oastify.com", MarkerKind.COLLABORATOR, null, 1_000L,
            status = MarkerStatus.OOB_INTERACTION, lastHit = pageView, labelledInteraction = fetch,
        )
        assertEquals("browser-attributed [Sec-Fetch-Mode: navigate]", values(Columns.markers, row)["Attribution"])
    }

    @Test
    fun `placed at says clipboard until a clipboard marker is first seen`() {
        val copied = MarkerHit("cnry7f3a9k2m", "cnry7f3a9k2m", MarkerKind.TOKEN, origin = null, placedAt = 1_000L)
        assertEquals("clipboard", values(Columns.markers, copied)["Placed at"])
        val ref = TrafficRef(TrafficChannel.HTTP_RESPONSE, "Proxy", "POST", "https://app.local/api/chat?x=1", 2_000L, messageId = "#7")
        val seen = copied.copy(origin = MarkerOrigin.firstSeenIn(ref), hitCount = 1, lastHit = ref)
        assertEquals("POST app.local/api/chat (clipboard, first seen in response #7)", values(Columns.markers, seen)["Placed at"])
    }

    @Test
    fun `traffic columns`() {
        val item = AnnotatedTrafficItem(7, 3_000L, "Proxy", "POST", "https://h/chat", "CanaryAgent: tools present: x [tool keys]", AnnotationSource.AUTO, "ab12cd34")
        val v = values(Columns.traffic, item)
        assertEquals(Timestamps.hms(3_000L), v["Time"])
        assertEquals("auto", v["Source"])
        assertEquals("ab12cd34", v["Session"])
        assertEquals("https://h/chat", v["URL"])
    }
}
