package com.canaryagent.persistence

import com.canaryagent.model.Attribution
import com.canaryagent.model.MarkerEncoding
import com.canaryagent.model.MarkerHit
import com.canaryagent.model.MarkerKind
import com.canaryagent.model.MarkerOrigin
import com.canaryagent.model.MarkerStatus
import com.canaryagent.model.ToolEntry
import com.canaryagent.model.TrafficChannel
import com.canaryagent.model.TrafficRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodecTest {
    private val origin = MarkerOrigin("Repeater", "POST", "app.local", "/api/docs", "json param \"content\"", "abc123")

    @Test
    fun `marker round trip with origin and last hit`() {
        for (status in MarkerStatus.entries) for (kind in MarkerKind.entries) for (channel in TrafficChannel.entries) {
            val hit = MarkerHit(
                token = "cnry7f3a9k2m", label = "CNRY7F3A9K2M", kind = kind, origin = origin, placedAt = 1_000L,
                status = status, encodings = setOf(MarkerEncoding.RAW, MarkerEncoding.BASE64),
                lastEncodings = setOf(MarkerEncoding.BASE64), hitCount = 3, lastHitAt = 2_000L,
                lastHit = TrafficRef(channel, "Proxy", "POST", "https://h/p", 1_999L, excerpt = "ex", messageId = "#9"),
            )
            val node = MemoryNode()
            MarkerCodec.write(node, hit)
            assertEquals(hit, MarkerCodec.read(node), "$status/$kind/$channel")
        }
    }

    /** 7. Attribution and its signals survive the project store; older rows without them read back as null. */
    @Test
    fun `collaborator attribution round trips and is absent on older rows`() {
        for (attribution in Attribution.entries) {
            val hit = MarkerHit(
                token = "k3j2h1", label = "k3j2h1.oastify.com", kind = MarkerKind.COLLABORATOR, origin = null, placedAt = 1L,
                status = MarkerStatus.OOB_INTERACTION, agentPathStatus = MarkerStatus.SEEN_IN_TOOL_CALL, hitCount = 1, lastHitAt = 2L,
                lastHit = TrafficRef(
                    TrafficChannel.COLLABORATOR, "Collaborator", "HTTP", "from 203.0.113.9:80", 2L, messageId = "k3j2h1",
                    attribution = attribution,
                    attributionSignals = listOf("Sec-Fetch-Mode: navigate", "path /favicon.ico", "User-Agent: Mozilla/5.0, like Gecko"),
                ),
            )
            val node = MemoryNode()
            MarkerCodec.write(node, hit)
            assertEquals(hit, MarkerCodec.read(node), attribution.name)
        }
        val legacy = MemoryNode()
        MarkerCodec.write(legacy, MarkerHit("k3j2h1", "k3j2h1", MarkerKind.COLLABORATOR, null, 1L,
            lastHit = TrafficRef(TrafficChannel.COLLABORATOR, "Collaborator", "DNS", "from 10.0.0.5:53", 2L)))
        legacy.strings.remove("hit.attribution")
        legacy.strings.remove("hit.attributionSignals")
        // Rows written before 2026-10-21 carry "inBandStatus" (the broader rule); it is never trusted.
        legacy.strings["inBandStatus"] = MarkerStatus.SEEN_IN_RESPONSE.name
        val back = MarkerCodec.read(legacy)!!
        assertNull(back.lastHit?.attribution)
        assertEquals(emptyList(), back.lastHit?.attributionSignals)
        assertNull(back.agentPathStatus)
        MarkerCodec.write(legacy, back)
        assertNull(legacy.strings["inBandStatus"], "the old key is dropped on the next write")
    }

    /** The labelled interaction round trips without request copies; older rows fall back to an interaction last hit. */
    @Test
    fun `labelled interaction round trips and older rows fall back to their last interaction`() {
        val fetch = TrafficRef(
            TrafficChannel.COLLABORATOR, "Collaborator", "HTTP", "from 203.0.113.9:80", 3L, messageId = "k3j2h1",
            attribution = Attribution.BROWSER, attributionSignals = listOf("Sec-Fetch-Mode: navigate", "path /favicon.ico"),
        )
        val pageView = TrafficRef(TrafficChannel.HTTP_RESPONSE, "Proxy", "GET", "https://app/posts", 4L, messageId = "#9")
        val hit = MarkerHit(
            "k3j2h1", "k3j2h1.oastify.com", MarkerKind.COLLABORATOR, null, 1L,
            status = MarkerStatus.OOB_INTERACTION, hitCount = 2, lastHitAt = 4L, lastHit = pageView, labelledInteraction = fetch,
        )
        val node = MemoryNode()
        MarkerCodec.write(node, hit)
        assertEquals(hit, MarkerCodec.read(node))
        MarkerCodec.write(node, hit.copy(labelledInteraction = null))
        assertNull(MarkerCodec.read(node)!!.labelledInteraction, "cleared on rewrite")

        val older = MemoryNode()
        MarkerCodec.write(older, hit.copy(lastHit = fetch, labelledInteraction = null))
        older.strings.keys.removeAll { it.startsWith("interaction.") }
        assertEquals(fetch, MarkerCodec.read(older)!!.labelledInteraction)
    }

    @Test
    fun `marker round trip without origin or hit`() {
        val hit = MarkerHit("needle", "Needle", MarkerKind.CUSTOM, null, 5L)
        val node = MemoryNode()
        MarkerCodec.write(node, hit)
        assertEquals(hit, MarkerCodec.read(node))
    }

    @Test
    fun `rewriting a marker drops stale origin and hit fields`() {
        val node = MemoryNode()
        val withHit = MarkerHit("cnry7f3a9k2m", "cnry7f3a9k2m", MarkerKind.TOKEN, origin, 1L,
            lastHit = TrafficRef(TrafficChannel.HTTP_RESPONSE, "Proxy", "POST", "https://h", 2L), hitCount = 1)
        MarkerCodec.write(node, withHit)
        val bare = MarkerHit("cnry7f3a9k2m", "cnry7f3a9k2m", MarkerKind.TOKEN, null, 1L)
        MarkerCodec.write(node, bare)
        assertEquals(bare, MarkerCodec.read(node))
    }

    @Test
    fun `unknown enum names or missing fields yield null`() {
        val node = MemoryNode()
        MarkerCodec.write(node, MarkerHit("cnry7f3a9k2m", "x", MarkerKind.TOKEN, null, 1L))
        node.setString("status", "SEEN_IN_FUTURE")
        assertNull(MarkerCodec.read(node))
        assertNull(MarkerCodec.read(MemoryNode()))
    }

    @Test
    fun `tool round trip keeps sorted keys`() {
        val entry = ToolEntry("search_docs", sortedSetOf("limit", "query"), 4, 10L, 20L, "https://api/x")
        val node = MemoryNode()
        ToolCodec.write(node, entry)
        assertEquals(entry, ToolCodec.read(node))
        assertNull(ToolCodec.read(MemoryNode()))
    }
}
