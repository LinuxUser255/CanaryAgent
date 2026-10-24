package com.canaryagent.markers

import com.canaryagent.model.Attribution
import com.canaryagent.model.MarkerEncoding
import com.canaryagent.model.MarkerHit
import com.canaryagent.model.MarkerKind
import com.canaryagent.model.MarkerOrigin
import com.canaryagent.model.MarkerStatus
import com.canaryagent.model.TrafficChannel
import com.canaryagent.model.TrafficRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkerHitLoggerTest {
    private val time = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} ")
    private val origin = MarkerOrigin("Repeater", "POST", "app.local", "/api/docs", "json param \"content\"")
    private val placed = MarkerHit("cnry7f3a9k2m", "cnry7f3a9k2m", MarkerKind.TOKEN, origin, placedAt = 1_000L)

    private fun hit(channel: TrafficChannel, status: MarkerStatus, enc: Set<MarkerEncoding>, method: String, url: String, id: String) =
        placed.copy(
            status = status,
            encodings = enc + MarkerEncoding.RAW,
            lastEncodings = enc,
            hitCount = 3,
            lastHitAt = 2_000L,
            lastHit = TrafficRef(channel, "Proxy", method, url, 2_000L, messageId = id),
        )

    private fun body(line: String): String {
        assertTrue(time.containsMatchIn(line), "missing timestamp: $line")
        return line.replace(time, "")
    }

    @Test
    fun `request hit`() {
        val h = hit(TrafficChannel.HTTP_REQUEST, MarkerStatus.SEEN_IN_REQUEST, setOf(MarkerEncoding.RAW), "POST", "https://api.openai.com/v1/chat/completions", "#17")
        assertEquals(
            "HIT [seen in request] in request POST https://api.openai.com/v1/chat/completions (raw) tool=Proxy id=#17",
            body(MarkerHitLogger.format(h)),
        )
    }

    @Test
    fun `tool-call hit shows only this hit's encoding`() {
        val h = hit(TrafficChannel.HTTP_RESPONSE, MarkerStatus.SEEN_IN_TOOL_CALL, setOf(MarkerEncoding.BASE64), "POST", "https://httpbin.org/post", "#18")
        assertEquals(
            "HIT [seen in tool call] in response POST https://httpbin.org/post (base64) tool=Proxy id=#18",
            body(MarkerHitLogger.format(h)),
        )
    }

    @Test
    fun `inbound websocket hit has no method`() {
        val h = hit(TrafficChannel.WS_IN, MarkerStatus.SEEN_IN_RESPONSE, setOf(MarkerEncoding.CASE_CHANGED), "", "wss://echo.websocket.org/.ws", "sha:0a1b2c3d")
        assertEquals(
            "HIT [seen in response] in ws<- wss://echo.websocket.org/.ws (case-changed) tool=Proxy id=sha:0a1b2c3d",
            body(MarkerHitLogger.format(h)),
        )
    }

    @Test
    fun `collaborator interaction has no encoding group`() {
        val h = hit(TrafficChannel.COLLABORATOR, MarkerStatus.OOB_INTERACTION, emptySet(), "DNS", "from 10.0.0.5:53", "k3j2h1")
            .copy(lastHit = TrafficRef(TrafficChannel.COLLABORATOR, "Collaborator", "DNS", "from 10.0.0.5:53", 2_000L, messageId = "k3j2h1"))
        assertEquals(
            "HIT [collaborator interaction] in oob DNS from 10.0.0.5:53 tool=Collaborator id=k3j2h1",
            body(MarkerHitLogger.format(h)),
        )
    }

    @Test
    fun `collaborator interaction with attribution appends the label and signals`() {
        val h = hit(TrafficChannel.COLLABORATOR, MarkerStatus.OOB_INTERACTION, emptySet(), "HTTP", "from 203.0.113.9:80", "k3j2h1")
            .copy(lastHit = TrafficRef(
                TrafficChannel.COLLABORATOR, "Collaborator", "HTTP", "from 203.0.113.9:80", 2_000L, messageId = "k3j2h1",
                attribution = Attribution.AGENT, attributionSignals = listOf("prior status: seen in tool call", "User-Agent: python-requests/2.32"),
            ))
        assertEquals(
            "HIT [collaborator interaction] in oob HTTP from 203.0.113.9:80 tool=Collaborator id=k3j2h1 attribution=agent-attributed [prior status: seen in tool call, User-Agent: python-requests/2.32]",
            body(MarkerHitLogger.format(h)),
        )
    }

    @Test
    fun `placement line`() {
        assertEquals(
            "PLACED cnry7f3a9k2m at POST app.local/api/docs (json param \"content\") tool=Repeater",
            body(MarkerHitLogger.format(placed)),
        )
    }
}
