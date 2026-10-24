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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkerTrackerTest {
    private val origin = MarkerOrigin("Repeater", "POST", "app.local", "/api/docs", "json param \"content\"")

    @Test
    fun `place stores lowercase key and keeps display label`() {
        val tracker = MarkerTracker()
        val hit = tracker.place("CNRY7F3A9K2M", MarkerKind.TOKEN, origin, now = 1L)
        assertEquals("cnry7f3a9k2m", hit.token)
        assertEquals("CNRY7F3A9K2M", hit.label)
        assertEquals(MarkerStatus.PLACED, hit.status)
        assertTrue(tracker.contains("cnry7f3a9k2m"))
        assertEquals(setOf(12), tracker.prefixedTokenLengths())
        assertTrue(tracker.customTokens().isEmpty())
    }

    @Test
    fun `custom tokens are indexed separately`() {
        val tracker = MarkerTracker()
        tracker.place("Needle Text", MarkerKind.CUSTOM, null)
        assertEquals(listOf("needle text"), tracker.customTokens().toList())
        assertTrue(tracker.prefixedTokenLengths().isEmpty())
    }

    @Test
    fun `status moves to response then tool call and never back`() {
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, origin, now = 1L)

        val a = tracker.recordHit("cnry7f3a9k2m", inToolCall = false, encodings = setOf(MarkerEncoding.RAW), ref = null, now = 2L)
        assertEquals(MarkerStatus.SEEN_IN_RESPONSE, a?.status)
        assertEquals(1, a?.hitCount)

        val b = tracker.recordHit("CNRY7F3A9K2M", inToolCall = true, encodings = setOf(MarkerEncoding.BASE64), ref = null, now = 3L)
        assertEquals(MarkerStatus.SEEN_IN_TOOL_CALL, b?.status)
        assertEquals(setOf(MarkerEncoding.RAW, MarkerEncoding.BASE64), b?.encodings)

        val c = tracker.recordHit("cnry7f3a9k2m", inToolCall = false, encodings = emptySet(), ref = null, now = 4L)
        assertEquals(MarkerStatus.SEEN_IN_TOOL_CALL, c?.status)
        assertEquals(3, c?.hitCount)
        assertEquals(4L, c?.lastHitAt)
    }

    private fun ref(channel: TrafficChannel) = TrafficRef(channel, "Proxy", "POST", "https://h/p", 0L, messageId = "#1")

    @Test
    fun `status follows the channel and only ever moves up`() {
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, origin)

        assertEquals(MarkerStatus.SEEN_IN_REQUEST, tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_REQUEST))?.status)
        assertEquals(MarkerStatus.SEEN_IN_REQUEST, tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.WS_OUT))?.status)
        assertEquals(MarkerStatus.SEEN_IN_RESPONSE, tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.WS_IN))?.status)
        // A later request-side hit must not demote the status.
        assertEquals(MarkerStatus.SEEN_IN_RESPONSE, tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_REQUEST))?.status)
        assertEquals(MarkerStatus.SEEN_IN_TOOL_CALL, tracker.recordHit("cnry7f3a9k2m", true, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_REQUEST))?.status)
        assertEquals(MarkerStatus.SEEN_IN_TOOL_CALL, tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_RESPONSE))?.status)
    }

    @Test
    fun `lastEncodings is this hit only while encodings accumulate`() {
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, origin)
        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.URL_ENCODED), ref(TrafficChannel.HTTP_RESPONSE))
        val h = tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.BASE64), ref(TrafficChannel.HTTP_RESPONSE))!!
        assertEquals(setOf(MarkerEncoding.BASE64), h.lastEncodings)
        assertEquals(setOf(MarkerEncoding.URL_ENCODED, MarkerEncoding.BASE64), h.encodings)
    }

    @Test
    fun `a clipboard marker takes the first message it is seen in as its origin`() {
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, null)
        assertNull(tracker["cnry7f3a9k2m"]?.origin)

        val first = tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_RESPONSE))!!
        assertEquals("POST h/p (clipboard, first seen in response #1)", first.origin?.description)
        assertEquals("", first.origin?.bodyHash)
        assertFalse(tracker.isOrigin("cnry7f3a9k2m", "POST", "h", "/p", "any"))

        val later = TrafficRef(TrafficChannel.HTTP_REQUEST, "Proxy", "POST", "https://other/x", 0L, messageId = "#2")
        val second = tracker.recordHit("cnry7f3a9k2m", true, setOf(MarkerEncoding.RAW), later)!!
        assertEquals(first.origin, second.origin)
        // A hit without a reference leaves the origin empty until a real message arrives.
        tracker.place("cnryclipboard2", MarkerKind.TOKEN, null)
        assertNull(tracker.recordHit("cnryclipboard2", false, emptySet(), null)?.origin)
    }

    @Test
    fun `a clipboard marker keeps counting hits on the endpoint its first-seen origin names`() {
        // Clipboard markers get an origin from their first sighting; that origin must not turn the same
        // endpoint into an excluded "originating request" for the hits that follow.
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, null)
        val endpointX = { id: String -> TrafficRef(TrafficChannel.HTTP_REQUEST, "Proxy", "POST", "https://app.local/api/chat", 0L, messageId = id) }

        val first = tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), endpointX("#1"))!!
        assertEquals(1, first.hitCount)
        assertEquals("POST app.local/api/chat (clipboard, first seen in request #1)", first.origin?.description)

        // The handler consults isOrigin with the real body hash before recording a request-side hit.
        assertFalse(tracker.isOrigin("cnry7f3a9k2m", "POST", "app.local", "/api/chat", "hash-of-message-1"))
        assertFalse(tracker.isOrigin("cnry7f3a9k2m", "POST", "app.local", "/api/chat", "hash-of-message-2"))

        val second = tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), endpointX("#2"))!!
        assertEquals(2, second.hitCount)
        assertEquals("#2", second.lastHit?.messageId)
        assertEquals(first.origin, second.origin)
        assertEquals(2, tracker["cnry7f3a9k2m"]?.hitCount)
    }

    @Test
    fun `first-seen origins describe WebSocket and Collaborator references too`() {
        val ws = MarkerOrigin.firstSeenIn(TrafficRef(TrafficChannel.WS_IN, "Proxy", "", "wss://h:8443/.ws", 0L, messageId = "sha:abcd1234"))
        assertEquals("WS h/.ws (clipboard, first seen in ws<- sha:abcd1234)", ws.description)
        val oob = MarkerOrigin.firstSeenIn(TrafficRef(TrafficChannel.COLLABORATOR, "Collaborator", "DNS", "from 10.0.0.5:53", 0L))
        assertEquals("DNS from 10.0.0.5:53 (clipboard, first seen in oob)", oob.description)
    }

    @Test
    fun `agentPathStatus records the highest agent-path status and ignores collaborator hits`() {
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, origin)
        assertNull(tracker["cnry7f3a9k2m"]?.agentPathStatus)

        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_REQUEST), agentContext = true)
        assertEquals(MarkerStatus.SEEN_IN_REQUEST, tracker["cnry7f3a9k2m"]?.agentPathStatus)

        val oob = TrafficRef(TrafficChannel.COLLABORATOR, "Collaborator", "DNS", "from 10.0.0.5:53", 0L)
        tracker.recordHit("cnry7f3a9k2m", false, emptySet(), oob, agentContext = true)
        assertEquals(MarkerStatus.OOB_INTERACTION, tracker["cnry7f3a9k2m"]?.status)
        assertEquals(MarkerStatus.SEEN_IN_REQUEST, tracker["cnry7f3a9k2m"]?.agentPathStatus, "an interaction never raises the agent-path status")

        tracker.recordHit("cnry7f3a9k2m", true, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_RESPONSE))
        assertEquals(MarkerStatus.OOB_INTERACTION, tracker["cnry7f3a9k2m"]?.status)
        assertEquals(MarkerStatus.SEEN_IN_TOOL_CALL, tracker["cnry7f3a9k2m"]?.agentPathStatus, "a tool call is agent-path by itself")

        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.WS_OUT), agentContext = true)
        assertEquals(MarkerStatus.SEEN_IN_TOOL_CALL, tracker["cnry7f3a9k2m"]?.agentPathStatus, "never moves down")
    }

    /**
     * 2026-10-21 real-app run: the tester posted a Collaborator payload to a forum and viewed the page; the
     * later visit was labelled agent-attributed because those sightings counted as agent history. A sighting
     * in traffic that is not agent-shaped raises the status but never the agent-path status.
     */
    @Test
    fun `sightings outside agent-shaped traffic never raise the agent-path status`() {
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, null)
        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_REQUEST))
        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_RESPONSE))
        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.WS_IN))
        tracker.recordHit("cnry7f3a9k2m", false, emptySet(), null)
        assertEquals(MarkerStatus.SEEN_IN_RESPONSE, tracker["cnry7f3a9k2m"]?.status)
        assertNull(tracker["cnry7f3a9k2m"]?.agentPathStatus)
    }

    /**
     * 2026-10-21 checklist 10: the row's attribution vanished once a page view became the last hit. The
     * labelled interaction keeps the latest browser/agent label through later in-band hits and trailing DNS.
     */
    @Test
    fun `labelled interaction keeps the latest informative label`() {
        val tracker = MarkerTracker()
        tracker.place("k3j2h1", MarkerKind.COLLABORATOR, null)
        fun oob(method: String, a: Attribution) =
            TrafficRef(TrafficChannel.COLLABORATOR, "Collaborator", method, "from 203.0.113.9:80", 0L, attribution = a)

        tracker.recordHit("k3j2h1", false, emptySet(), oob("DNS", Attribution.UNATTRIBUTED))
        assertEquals(Attribution.UNATTRIBUTED, tracker["k3j2h1"]?.labelledInteraction?.attribution, "the only interaction so far")
        tracker.recordHit("k3j2h1", false, emptySet(), oob("HTTP", Attribution.BROWSER))
        assertEquals(Attribution.BROWSER, tracker["k3j2h1"]?.labelledInteraction?.attribution)
        tracker.recordHit("k3j2h1", false, emptySet(), oob("DNS", Attribution.UNATTRIBUTED))
        assertEquals(Attribution.BROWSER, tracker["k3j2h1"]?.labelledInteraction?.attribution, "a trailing DNS lookup does not hide it")
        tracker.recordHit("k3j2h1", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_RESPONSE))
        assertEquals(TrafficChannel.HTTP_RESPONSE, tracker["k3j2h1"]?.lastHit?.channel)
        assertEquals(Attribution.BROWSER, tracker["k3j2h1"]?.labelledInteraction?.attribution, "an in-band hit does not hide it")
        tracker.recordHit("k3j2h1", false, emptySet(), oob("HTTP", Attribution.AGENT))
        assertEquals(Attribution.AGENT, tracker["k3j2h1"]?.labelledInteraction?.attribution, "a newer labelled interaction replaces it")
    }

    /** F4: delivery through a tool result ranks above a plain sighting and below a tool call. */
    @Test
    fun `tool result is its own rung between response and tool call`() {
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, null)
        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_RESPONSE))
        assertEquals(MarkerStatus.SEEN_IN_RESPONSE, tracker["cnry7f3a9k2m"]?.status)

        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_REQUEST), inToolResult = true)
        assertEquals(MarkerStatus.SEEN_IN_TOOL_RESULT, tracker["cnry7f3a9k2m"]?.status)
        assertEquals(MarkerStatus.SEEN_IN_TOOL_RESULT, tracker["cnry7f3a9k2m"]?.agentPathStatus, "a tool result is agent-path by itself")

        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_RESPONSE))
        assertEquals(MarkerStatus.SEEN_IN_TOOL_RESULT, tracker["cnry7f3a9k2m"]?.status, "never moves down")

        tracker.recordHit("cnry7f3a9k2m", true, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_REQUEST), inToolResult = true)
        assertEquals(MarkerStatus.SEEN_IN_TOOL_CALL, tracker["cnry7f3a9k2m"]?.status, "a tool call outranks a tool result in the same hit")
        assertTrue(MarkerStatus.SEEN_IN_RESPONSE.rank < MarkerStatus.SEEN_IN_TOOL_RESULT.rank)
        assertTrue(MarkerStatus.SEEN_IN_TOOL_RESULT.rank < MarkerStatus.SEEN_IN_TOOL_CALL.rank)
    }

    // ---- F8: hit count = distinct messages ------------------------------------------------------------

    private fun fp(channel: TrafficChannel, url: String, body: String) = MarkerTracker.fingerprint(channel, "GET", url, body)

    /** 2026-10-21: a polled endpoint drove one marker to 48 hits while serving the same body. */
    @Test
    fun `a repeated message updates last hit but not the count`() {
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, null)
        val poll = fp(TrafficChannel.HTTP_RESPONSE, "https://app/chatbot/state", "hash-a")
        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_RESPONSE), now = 10L, fingerprint = poll)
        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_RESPONSE), now = 20L, fingerprint = poll)
        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_RESPONSE), now = 30L, fingerprint = poll)
        val h = tracker["cnry7f3a9k2m"]!!
        assertEquals(1, h.hitCount)
        assertEquals(30L, h.lastHitAt, "last seen still moves")
    }

    /** The known trap: a later turn to the same endpoint with a different body must still count. */
    @Test
    fun `a later turn to the same endpoint still counts`() {
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, null)
        val url = "https://api.openai.com/v1/chat/completions"
        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_REQUEST), fingerprint = fp(TrafficChannel.HTTP_REQUEST, url, "turn-1"))
        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), ref(TrafficChannel.HTTP_REQUEST), fingerprint = fp(TrafficChannel.HTTP_REQUEST, url, "turn-2"))
        assertEquals(2, tracker["cnry7f3a9k2m"]!!.hitCount)
    }

    @Test
    fun `same body in the other direction or on another url counts`() {
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, null)
        tracker.recordHit("cnry7f3a9k2m", false, emptySet(), ref(TrafficChannel.HTTP_REQUEST), fingerprint = fp(TrafficChannel.HTTP_REQUEST, "https://h/a", "same"))
        tracker.recordHit("cnry7f3a9k2m", false, emptySet(), ref(TrafficChannel.HTTP_RESPONSE), fingerprint = fp(TrafficChannel.HTTP_RESPONSE, "https://h/a", "same"))
        tracker.recordHit("cnry7f3a9k2m", false, emptySet(), ref(TrafficChannel.HTTP_RESPONSE), fingerprint = fp(TrafficChannel.HTTP_RESPONSE, "https://h/b", "same"))
        assertEquals(3, tracker["cnry7f3a9k2m"]!!.hitCount)
    }

    /** Nothing is suppressed but the count: a repeat that classifies higher still promotes the status. */
    @Test
    fun `a repeat never blocks a status promotion`() {
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, null)
        val f = fp(TrafficChannel.HTTP_RESPONSE, "https://h/a", "same")
        tracker.recordHit("cnry7f3a9k2m", false, emptySet(), ref(TrafficChannel.HTTP_RESPONSE), fingerprint = f)
        tracker.recordHit("cnry7f3a9k2m", true, emptySet(), ref(TrafficChannel.HTTP_RESPONSE), fingerprint = f)
        assertEquals(MarkerStatus.SEEN_IN_TOOL_CALL, tracker["cnry7f3a9k2m"]!!.status)
        assertEquals(1, tracker["cnry7f3a9k2m"]!!.hitCount)
    }

    @Test
    fun `collaborator interactions and unfingerprinted hits always count`() {
        val tracker = MarkerTracker()
        tracker.place("k3j2h1", MarkerKind.COLLABORATOR, null)
        val oob = TrafficRef(TrafficChannel.COLLABORATOR, "Collaborator", "DNS", "from 10.0.0.5:53", 0L)
        val f = fp(TrafficChannel.COLLABORATOR, "dns", "same")
        tracker.recordHit("k3j2h1", false, emptySet(), oob, fingerprint = f)
        tracker.recordHit("k3j2h1", false, emptySet(), oob, fingerprint = f)
        tracker.recordHit("k3j2h1", false, emptySet(), ref(TrafficChannel.HTTP_RESPONSE))
        tracker.recordHit("k3j2h1", false, emptySet(), ref(TrafficChannel.HTTP_RESPONSE))
        assertEquals(4, tracker["k3j2h1"]!!.hitCount)
    }

    @Test
    fun `removing a marker forgets its fingerprints`() {
        val tracker = MarkerTracker()
        val f = fp(TrafficChannel.HTTP_RESPONSE, "https://h/a", "same")
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, null)
        tracker.recordHit("cnry7f3a9k2m", false, emptySet(), ref(TrafficChannel.HTTP_RESPONSE), fingerprint = f)
        tracker.remove("cnry7f3a9k2m")
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, null)
        tracker.recordHit("cnry7f3a9k2m", false, emptySet(), ref(TrafficChannel.HTTP_RESPONSE), fingerprint = f)
        assertEquals(1, tracker["cnry7f3a9k2m"]!!.hitCount)
    }

    @Test
    fun `hits on unknown tokens are ignored`() {
        val tracker = MarkerTracker()
        assertNull(tracker.recordHit("cnryunknown1", false, emptySet(), null))
    }

    @Test
    fun `origin matching needs endpoint and the exact placed body`() {
        val tracker = MarkerTracker()
        val placedBody = "sha256-of-the-edited-body"
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, origin.copy(bodyHash = placedBody))
        assertTrue(tracker.isOrigin("cnry7f3a9k2m", "post", "APP.local", "/api/docs", placedBody))
        assertFalse(tracker.isOrigin("cnry7f3a9k2m", "POST", "api.openai.com", "/v1/chat/completions", placedBody))
        assertFalse(tracker.isOrigin("cnry7f3a9k2m", "GET", "app.local", "/api/docs", placedBody))
    }

    @Test
    fun `bug 8 - a later turn to the same endpoint is not the origin`() {
        // LLM APIs reuse one endpoint every turn; only the exact message the marker was inserted into is excluded.
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, origin.copy(bodyHash = "hash-of-turn-1"))
        assertFalse(tracker.isOrigin("cnry7f3a9k2m", "POST", "app.local", "/api/docs", "hash-of-turn-2"))
    }

    @Test
    fun `an origin without a body hash never excludes anything`() {
        val tracker = MarkerTracker()
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, origin.copy(bodyHash = ""))
        assertFalse(tracker.isOrigin("cnry7f3a9k2m", "POST", "app.local", "/api/docs", "anything"))
    }

    @Test
    fun `listeners see placements and hits and removal updates indexes`() {
        val tracker = MarkerTracker()
        val events = ArrayList<Pair<String, MarkerHit?>>()
        tracker.addListener { token, hit -> events += token to hit }
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, origin)
        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), null)
        tracker.remove("cnry7f3a9k2m")
        assertEquals(3, events.size)
        assertNull(events[2].second)
        assertFalse(tracker.contains("cnry7f3a9k2m"))
        assertTrue(tracker.prefixedTokenLengths().isEmpty())
    }
}
