package com.canaryagent.collaborator

import com.canaryagent.model.Attribution
import com.canaryagent.model.MarkerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure precedence rules, independent of Montoya objects. */
class InteractionAttributionTest {
    private fun headers(vararg pairs: Pair<String, String>): (String) -> String? {
        val m = pairs.toMap().mapKeys { it.key.lowercase() }
        return { name -> m[name.lowercase()] }
    }

    @Test
    fun `tier 1 beats tier 2 whatever the marker's history`() {
        for (status in MarkerStatus.entries) {
            val r = InteractionAttribution.attribute("/", headers("Sec-Fetch-Site" to "cross-site"), status)
            assertEquals(Attribution.BROWSER, r.attribution, status.name)
        }
    }

    /** A stale "look like a browser" header set (UIR + Mozilla UA, no Sec-Fetch) is a fetch tool, not a browser. */
    @Test
    fun `supporting headers alone never decide browser but are listed as evidence`() {
        val chua = InteractionAttribution.attribute("/", headers("Sec-CH-UA" to "\"Chromium\";v=\"130\""), MarkerStatus.PLACED)
        assertEquals(Attribution.UNATTRIBUTED, chua.attribution)
        assertEquals(listOf("Sec-CH-UA"), chua.signals)

        val stale = InteractionAttribution.attribute("/", headers("Upgrade-Insecure-Requests" to "1", "User-Agent" to "Mozilla/5.0 (Windows NT 10.0)"), MarkerStatus.SEEN_IN_TOOL_CALL)
        assertEquals(Attribution.AGENT, stale.attribution)
        assertEquals(listOf("prior status: seen in tool call", "Upgrade-Insecure-Requests", "User-Agent: Mozilla/5.0 (Windows NT 10.0)"), stale.signals)

        val referer = InteractionAttribution.attribute("/", headers("Referer" to "https://app.example/chat"), MarkerStatus.PLACED)
        assertEquals(Attribution.UNATTRIBUTED, referer.attribution)
        assertTrue(referer.signals.any { it.startsWith("Referer: https://app.example/chat") }, referer.signals.toString())

        // Alongside a Sec-Fetch header they ride along as evidence on a browser label.
        val real = InteractionAttribution.attribute("/", headers("Sec-Fetch-Site" to "cross-site", "Referer" to "https://app.example/chat"), MarkerStatus.PLACED)
        assertEquals(Attribution.BROWSER, real.attribution)
        assertEquals(listOf("Sec-Fetch-Site: cross-site", "Referer: https://app.example/chat"), real.signals)
    }

    @Test
    fun `user-agent is recorded as evidence but never decides`() {
        val mozilla = InteractionAttribution.attribute("/", headers("User-Agent" to "Mozilla/5.0 (Macintosh)"), MarkerStatus.PLACED)
        assertEquals(Attribution.UNATTRIBUTED, mozilla.attribution)
        assertTrue(mozilla.signals.any { it.startsWith("User-Agent") }, mozilla.signals.toString())
        val bot = InteractionAttribution.attribute("/", headers("User-Agent" to "python-requests/2.32"), MarkerStatus.SEEN_IN_TOOL_CALL)
        assertEquals(Attribution.AGENT, bot.attribution)
        assertTrue(bot.signals.any { it.startsWith("User-Agent") })
    }

    @Test
    fun `tier 2 applies only to in-band pipeline statuses`() {
        val none = headers()
        assertEquals(Attribution.AGENT, InteractionAttribution.attribute("/", none, MarkerStatus.SEEN_IN_REQUEST).attribution)
        assertEquals(Attribution.AGENT, InteractionAttribution.attribute("/", none, MarkerStatus.SEEN_IN_RESPONSE).attribution)
        assertEquals(Attribution.AGENT, InteractionAttribution.attribute("/", none, MarkerStatus.SEEN_IN_TOOL_CALL).attribution)
        assertEquals(Attribution.UNATTRIBUTED, InteractionAttribution.attribute("/", none, MarkerStatus.PLACED).attribution)
        assertEquals(Attribution.UNATTRIBUTED, InteractionAttribution.attribute("/", none, MarkerStatus.OOB_INTERACTION).attribution)
        assertEquals(Attribution.UNATTRIBUTED, InteractionAttribution.attribute(null, none, null).attribution)
    }

    @Test
    fun `favicon path is matched without its query and case-insensitively`() {
        assertEquals(Attribution.BROWSER, InteractionAttribution.attribute("/favicon.ico", headers(), MarkerStatus.PLACED).attribution)
        assertEquals(Attribution.BROWSER, InteractionAttribution.attribute("/FAVICON.ICO", headers(), MarkerStatus.PLACED).attribution)
        assertEquals(Attribution.UNATTRIBUTED, InteractionAttribution.attribute("/favicon.png", headers(), MarkerStatus.PLACED).attribution)
    }

    @Test
    fun `signals are listed in precedence order and name the header`() {
        val r = InteractionAttribution.attribute("/favicon.ico", headers("Referer" to "https://app.example/", "Sec-Fetch-Mode" to "no-cors", "User-Agent" to "Mozilla/5.0"), MarkerStatus.SEEN_IN_TOOL_CALL)
        assertEquals(Attribution.BROWSER, r.attribution)
        assertEquals("Sec-Fetch-Mode: no-cors", r.signals[0])
        assertEquals("path /favicon.ico", r.signals[1])
        assertTrue(r.signals[2].startsWith("Referer: https://app.example/"))
        assertTrue(r.signals.last().startsWith("User-Agent: Mozilla/5.0"))
        assertTrue(r.signals.none { it.startsWith("prior status") }, "tier 2 is not reported when tier 1 decided")
    }
}
