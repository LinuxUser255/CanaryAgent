package com.canaryagent.collaborator

import burp.api.montoya.collaborator.DnsDetails
import burp.api.montoya.collaborator.HttpDetails
import burp.api.montoya.collaborator.Interaction
import burp.api.montoya.collaborator.InteractionId
import burp.api.montoya.collaborator.InteractionType
import burp.api.montoya.collaborator.SmtpDetails
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.Attribution
import com.canaryagent.model.MarkerEncoding
import com.canaryagent.model.MarkerKind
import com.canaryagent.model.MarkerStatus
import com.canaryagent.model.TrafficChannel
import com.canaryagent.model.TrafficRef
import java.net.InetAddress
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollaboratorCorrelatorTest {
    private val id = "k3j2h1g0f9e8d7c6b5a4z3y2x1w0v9u8t"
    private val host = "$id.oastify.com"

    private class FakeInteraction(
        private val id: String,
        private val type: InteractionType,
        private val ip: String,
        private val port: Int,
        private val at: ZonedDateTime,
        private val http: HttpDetails? = null,
    ) : Interaction {
        override fun id(): InteractionId = object : InteractionId { override fun toString() = id }
        override fun type(): InteractionType = type
        override fun timeStamp(): ZonedDateTime = at
        override fun clientIp(): InetAddress = InetAddress.getByName(ip)
        override fun clientPort(): Int = port
        override fun dnsDetails(): Optional<DnsDetails> = Optional.empty()
        override fun httpDetails(): Optional<HttpDetails> = Optional.ofNullable(http)
        override fun smtpDetails(): Optional<SmtpDetails> = Optional.empty()
        override fun customData(): Optional<String> = Optional.empty()
    }

    private val now = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, ZoneOffset.UTC)

    private fun tracked(): MarkerTracker = MarkerTracker().apply { place(id, MarkerKind.COLLABORATOR, null, label = host) }

    private fun dns(forId: String = id) = FakeInteraction(forId, InteractionType.DNS, "10.0.0.5", 53, now)

    private fun http(path: String, headers: Map<String, String>, forId: String = id) =
        FakeInteraction(forId, InteractionType.HTTP, "203.0.113.9", 80, now, FakeHttp.details(FakeHttp.request("GET", path, headers)))

    private val browserHeaders = mapOf(
        "Host" to host,
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/131.0",
        "Accept" to "text/html,application/xhtml+xml",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Site" to "cross-site",
    )
    private val plainHeaders = mapOf("Host" to host, "User-Agent" to "python-requests/2.32", "Accept" to "*/*")

    private fun seenInToolCall(tracker: MarkerTracker) {
        tracker.recordHit(id, inToolCall = true, encodings = setOf(MarkerEncoding.RAW), ref = TrafficRef(TrafficChannel.HTTP_RESPONSE, "Proxy", "POST", "https://h/p", 0L))
        assertEquals(MarkerStatus.SEEN_IN_TOOL_CALL, tracker[id]!!.status)
    }

    @Test
    fun `payload is tracked by id and labelled with the hostname`() {
        val h = tracked()[id]!!
        assertEquals(id, h.token)
        assertEquals(host, h.label)
        assertEquals(MarkerKind.COLLABORATOR, h.kind)
    }

    @Test
    fun `matching dns interaction becomes an oob hit`() {
        val tracker = tracked()
        val n = CollaboratorCorrelator.apply(listOf(FakeInteraction(id.uppercase(), InteractionType.DNS, "10.0.0.5", 53, now)), tracker, now = 5_000L)
        assertEquals(1, n)
        val h = tracker[id]!!
        assertEquals(MarkerStatus.OOB_INTERACTION, h.status)
        assertEquals(1, h.hitCount)
        assertEquals(5_000L, h.lastHitAt)
        val ref = h.lastHit!!
        assertEquals(TrafficChannel.COLLABORATOR, ref.channel)
        assertEquals("Collaborator", ref.tool)
        assertEquals("DNS", ref.method)
        assertEquals("from 10.0.0.5:53", ref.url)
        assertEquals(now.toInstant().toEpochMilli(), ref.timestamp)
        assertEquals(id.uppercase(), ref.messageId)
    }

    @Test
    fun `oob outranks tool call and is never demoted`() {
        val tracker = tracked()
        seenInToolCall(tracker)
        CollaboratorCorrelator.apply(listOf(FakeInteraction(id, InteractionType.HTTP, "9.9.9.9", 443, now)), tracker)
        assertEquals(MarkerStatus.OOB_INTERACTION, tracker[id]!!.status)
        tracker.recordHit(id, inToolCall = false, encodings = setOf(MarkerEncoding.RAW), ref = TrafficRef(TrafficChannel.HTTP_RESPONSE, "Proxy", "POST", "https://h/p", 0L))
        assertEquals(MarkerStatus.OOB_INTERACTION, tracker[id]!!.status)
        assertEquals(3, tracker[id]!!.hitCount)
    }

    // ---- attribution (F12) ----------------------------------------------------------------------

    /** 1. Sec-Fetch headers: promoted, browser-attributed, signals name the header. */
    @Test
    fun `sec-fetch headers promote and attribute to the browser`() {
        val tracker = tracked()
        assertEquals(1, CollaboratorCorrelator.apply(listOf(http("/", browserHeaders)), tracker))
        val h = tracker[id]!!
        assertEquals(MarkerStatus.OOB_INTERACTION, h.status)
        assertEquals(1, h.hitCount)
        val ref = h.lastHit!!
        assertEquals(Attribution.BROWSER, ref.attribution)
        assertTrue(ref.attributionSignals.any { it.startsWith("Sec-Fetch-Mode") }, ref.attributionSignals.toString())
        assertTrue(ref.attributionSignals.any { it.startsWith("Sec-Fetch-Dest") }, ref.attributionSignals.toString())
        assertTrue(ref.attributionSignals.any { it.startsWith("Sec-Fetch-Site") }, ref.attributionSignals.toString())
    }

    /** 2. favicon path with no Sec-Fetch headers: promoted, browser-attributed. */
    @Test
    fun `favicon path without sec-fetch headers promotes and attributes to the browser`() {
        val tracker = tracked()
        CollaboratorCorrelator.apply(listOf(http("/favicon.ico", plainHeaders)), tracker)
        val h = tracker[id]!!
        assertEquals(MarkerStatus.OOB_INTERACTION, h.status)
        assertEquals(Attribution.BROWSER, h.lastHit?.attribution)
        assertEquals(listOf("path /favicon.ico"), h.lastHit!!.attributionSignals.filter { it.startsWith("path") })
    }

    /**
     * 3. The trap: marker already at SEEN_IN_TOOL_CALL and the interaction carries browser headers.
     * The row must still be recorded and promoted; attribution says browser. Any implementation that
     * skips, defers or downgrades the hit on browser evidence fails here.
     */
    @Test
    fun `browser evidence on an agent-shaped marker still promotes and is browser-attributed`() {
        val tracker = tracked()
        seenInToolCall(tracker)
        val before = tracker[id]!!
        val n = CollaboratorCorrelator.apply(listOf(http("/favicon.ico", browserHeaders)), tracker, now = 7_000L)
        assertEquals(1, n, "the interaction must count as matched")
        val h = tracker[id]!!
        assertEquals(MarkerStatus.OOB_INTERACTION, h.status, "status must be promoted, not suppressed")
        assertEquals(before.hitCount + 1, h.hitCount, "the hit must be recorded")
        assertEquals(7_000L, h.lastHitAt)
        assertEquals(TrafficChannel.COLLABORATOR, h.lastHit?.channel)
        assertEquals(Attribution.BROWSER, h.lastHit?.attribution, "direct evidence beats the marker's history")
        assertTrue(h.lastHit!!.attributionSignals.any { it.startsWith("Sec-Fetch-Mode") })
    }

    /** 4. Agent-shaped marker, no browser headers: promoted, agent-attributed. */
    @Test
    fun `plain fetch on an agent-shaped marker is agent-attributed`() {
        val tracker = tracked()
        seenInToolCall(tracker)
        CollaboratorCorrelator.apply(listOf(http("/", plainHeaders)), tracker)
        val h = tracker[id]!!
        assertEquals(MarkerStatus.OOB_INTERACTION, h.status)
        assertEquals(Attribution.AGENT, h.lastHit?.attribution)
        assertTrue(h.lastHit!!.attributionSignals.any { it == "prior status: seen in tool call" }, h.lastHit!!.attributionSignals.toString())
    }

    /** 5. PLACED marker, DNS interaction: promoted, unattributed. */
    @Test
    fun `dns interaction on a placed marker is unattributed`() {
        val tracker = tracked()
        CollaboratorCorrelator.apply(listOf(dns()), tracker)
        val h = tracker[id]!!
        assertEquals(MarkerStatus.OOB_INTERACTION, h.status)
        assertEquals(Attribution.UNATTRIBUTED, h.lastHit?.attribution)
        assertEquals(emptyList(), h.lastHit?.attributionSignals)
    }

    /** 6. PLACED marker, HTTP interaction without browser headers: promoted, unattributed. */
    @Test
    fun `plain http interaction on a placed marker is unattributed`() {
        val tracker = tracked()
        CollaboratorCorrelator.apply(listOf(http("/", plainHeaders)), tracker)
        val h = tracker[id]!!
        assertEquals(MarkerStatus.OOB_INTERACTION, h.status)
        assertEquals(Attribution.UNATTRIBUTED, h.lastHit?.attribution)
    }

    /** 8. Unknown ids stay ignored, browser headers or not. */
    @Test
    fun `unknown ids are ignored`() {
        val tracker = tracked()
        val n = CollaboratorCorrelator.apply(listOf(dns("zzzz"), http("/favicon.ico", browserHeaders, forId = "zzzz")), tracker)
        assertEquals(0, n)
        assertEquals(MarkerStatus.PLACED, tracker[id]!!.status)
        assertEquals(0, tracker[id]!!.hitCount)
        assertNull(tracker["zzzz"])
    }

    /**
     * Resolution precedes the fetch: Collaborator delivers the DNS lookups before the HTTP interaction, and
     * the first of them promotes the row. The agent label must survive that, in one batch and across polls.
     * This is what failed live on 2026-10-21 (JAR 9 run).
     */
    @Test
    fun `agent label survives the dns hit that promotes the row, within and across batches`() {
        val tracker = tracked()
        seenInToolCall(tracker)
        CollaboratorCorrelator.apply(listOf(dns(), dns(), dns(), http("/", plainHeaders)), tracker)
        var h = tracker[id]!!
        assertEquals(MarkerStatus.OOB_INTERACTION, h.status)
        assertEquals(5, h.hitCount, "the in-band seed plus four interactions")
        assertEquals(Attribution.AGENT, h.lastHit?.attribution, "the HTTP fetch evaluated after the DNS promotion")
        assertEquals(listOf("prior status: seen in tool call", "User-Agent: python-requests/2.32"), h.lastHit?.attributionSignals)

        // Next poll, same payload fetched again: still agent.
        CollaboratorCorrelator.apply(listOf(http("/", plainHeaders)), tracker)
        h = tracker[id]!!
        assertEquals(Attribution.AGENT, h.lastHit?.attribution)
        // Direct browser evidence still wins over the in-band history.
        CollaboratorCorrelator.apply(listOf(http("/favicon.ico", browserHeaders)), tracker)
        assertEquals(Attribution.BROWSER, tracker[id]!!.lastHit?.attribution)
        assertEquals(MarkerStatus.OOB_INTERACTION, tracker[id]!!.status)
        assertEquals(7, tracker[id]!!.hitCount)
    }

    /** With no in-band history, promotion by a DNS lookup does not conjure an agent label for later interactions. */
    @Test
    fun `no in-band history stays unattributed after promotion`() {
        val tracker = tracked()
        CollaboratorCorrelator.apply(listOf(dns(), dns()), tracker)
        CollaboratorCorrelator.apply(listOf(http("/", plainHeaders)), tracker)
        assertEquals(MarkerStatus.OOB_INTERACTION, tracker[id]!!.status)
        assertEquals(Attribution.UNATTRIBUTED, tracker[id]!!.lastHit?.attribution)
    }

    /**
     * 2026-10-21 real-app run (checklist 9a): the tester posted the payload to a forum, viewed the page, then
     * visited the http:// URL in Chrome, which sends no Sec-Fetch headers to plain-http origins. Those
     * sightings were not agent-shaped, so neither the page fetch nor the DNS lookups may read as the agent's.
     */
    @Test
    fun `tester's own sightings and an http browser visit stay unattributed`() {
        val tracker = tracked()
        val forum = "https://app/community/posts"
        tracker.recordHit(id, inToolCall = false, encodings = setOf(MarkerEncoding.RAW), ref = TrafficRef(TrafficChannel.HTTP_REQUEST, "Proxy", "POST", forum, 0L))
        tracker.recordHit(id, inToolCall = false, encodings = setOf(MarkerEncoding.RAW), ref = TrafficRef(TrafficChannel.HTTP_RESPONSE, "Proxy", "GET", forum, 0L))
        val httpBrowser = mapOf(
            "Host" to host,
            "Upgrade-Insecure-Requests" to "1",
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36",
        )
        CollaboratorCorrelator.apply(listOf(dns()), tracker)
        assertEquals(Attribution.UNATTRIBUTED, tracker[id]!!.lastHit?.attribution, "DNS")
        CollaboratorCorrelator.apply(listOf(http("/", httpBrowser)), tracker)
        assertEquals(MarkerStatus.OOB_INTERACTION, tracker[id]!!.status, "still promoted: attribution never suppresses")
        assertEquals(Attribution.UNATTRIBUTED, tracker[id]!!.lastHit?.attribution, "page fetch")
    }

    /** The same payload, once seen in a provider request the detector flags, makes a plain fetch the agent's. */
    @Test
    fun `a sighting in agent-shaped traffic makes a plain fetch agent-attributed`() {
        val tracker = tracked()
        tracker.recordHit(
            id, inToolCall = false, encodings = setOf(MarkerEncoding.RAW),
            ref = TrafficRef(TrafficChannel.HTTP_REQUEST, "Proxy", "POST", "https://api.openai.com/v1/chat/completions", 0L),
            agentContext = true,
        )
        CollaboratorCorrelator.apply(listOf(http("/", plainHeaders)), tracker)
        assertEquals(Attribution.AGENT, tracker[id]!!.lastHit?.attribution)
        assertEquals(listOf("prior status: seen in request"), tracker[id]!!.lastHit?.attributionSignals?.take(1))
    }

    /** DNS carries no headers, so an agent-shaped marker makes a DNS interaction agent-attributed, never browser. */
    @Test
    fun `dns on an agent-shaped marker is agent-attributed`() {
        val tracker = tracked()
        seenInToolCall(tracker)
        CollaboratorCorrelator.apply(listOf(dns()), tracker)
        assertEquals(Attribution.AGENT, tracker[id]!!.lastHit?.attribution)
    }
}
