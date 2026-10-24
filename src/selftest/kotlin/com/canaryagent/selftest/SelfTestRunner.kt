package com.canaryagent.selftest

import burp.api.montoya.collaborator.InteractionType
import burp.api.montoya.collaborator.SecretKey
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.RequestOptions
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.websocket.extension.ExtensionWebSocketCreationStatus
import com.canaryagent.CanaryAgentRuntime
import com.canaryagent.annotation.StreamingSettingsHint
import com.canaryagent.collaborator.CollaboratorCorrelator
import com.canaryagent.inventory.ToolInventory
import com.canaryagent.persistence.MontoyaNode
import com.canaryagent.persistence.PersistenceService
import com.canaryagent.markers.MarkerGenerator
import com.canaryagent.markers.MarkerHitLogger
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.AnnotationSource
import com.canaryagent.model.Attribution
import com.canaryagent.model.MarkerEncoding
import com.canaryagent.model.MarkerHit
import com.canaryagent.model.MarkerKind
import com.canaryagent.model.MarkerOrigin
import com.canaryagent.model.MarkerStatus
import com.canaryagent.model.TrafficChannel
import com.canaryagent.model.TrafficRef
import com.canaryagent.settings.CanaryAgentSettings
import com.canaryagent.util.Hashing
import com.canaryagent.util.Timestamps
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Exercises the live runtime end to end from inside Burp: real requests through Burp's HTTP stack to
 * httpbin.org, a WebSocket echo, Collaborator (Professional), and the proxy listener for the
 * second-turn case. Each check reports PASS / FAIL / WARN / SKIP with a one-line detail.
 */
class SelfTestRunner(private val rt: CanaryAgentRuntime, private val report: (String) -> Unit) {

    enum class Outcome { PASS, FAIL, WARN, SKIP }
    data class Result(val name: String, val outcome: Outcome, val detail: String)

    private val results = ArrayList<Result>()
    @Volatile private var cancelled = false
    private var collaboratorId: String? = null
    private var collaboratorHost: String? = null

    private val httpbin = HttpService.httpService("httpbin.org", 443, true)
    private val options = RequestOptions.requestOptions().withResponseTimeout(TIMEOUT_MS)

    /**
     * Proxy listener the A2/A8/A11 cases send through. Defaults to Burp's 127.0.0.1:8080; a headless run
     * that must not clash with a GUI Burp passes `-Dcanaryagent.selftest.proxyPort=<port>` and lists the
     * same port in its user options file.
     */
    private val proxyPort: Int = System.getProperty(PROP_PROXY_PORT)?.toIntOrNull() ?: 8080
    private val proxyListener = "127.0.0.1:$proxyPort"
    private fun proxyService(): HttpService = HttpService.httpService("127.0.0.1", proxyPort, false)

    fun cancel() {
        cancelled = true
    }

    fun runAll(): List<Result> {
        results.clear()
        report("${Timestamps.hms(System.currentTimeMillis())} self-test on Burp ${rt.capabilities.edition}; markers tracked: ${rt.tracker.size()}")
        report("E0 settings read now (live): ${rt.settings.describe()}; effective matching=${rt.annotationSettings.matchingEnabled}")
        report(
            "Preconditions: A5 needs 'Automatically flag' ON (now ${rt.annotationSettings.matchingEnabled}); " +
                "E2 needs 'Do not persist' OFF (now ${!rt.settings.persistenceEnabled}); " +
                "A7 needs echo.websocket.org out of the streaming list; A2 needs the Proxy listener on $proxyListener.",
        )
        check("A1 response hit: no request-side hit, log format, raw-only encodings") { responseHit() }
        check("A3 tool-call classification (bug 4)") { toolCallHit() }
        check("A9 tool-result classification (F4)") { toolResultHit() }
        check("A10 repeated identical response counts once (F8)") { repeatCountsOnce() }
        check("A4 base64-only encodings (bug 3)") { base64Hit() }
        check("A5 NEW TOOL, inventory keys, annotated traffic") { inventory() }
        check("A6 mark as agent content") { markManually() }
        check("A2 Proxy: later turn to the same endpoint still counts (bug 8)") { proxySecondTurn() }
        check("A8 Proxy history note is one clean CanaryAgent note") { proxyNote() }
        check("A11 request-side hit gets a traffic row and Proxy note (F10)") { requestSideRow() }
        check("A7 WebSocket echo: wss URL and inbound hit (bug 7)") { webSocketEcho() }
        check("C1 Collaborator out-of-band interaction") { collaboratorOob() }
        check("C2 Collaborator in-band echo keeps status") { collaboratorInBand() }
        check("C3 Collaborator client key persisted for reloads") { collaboratorKeyPersisted() }
        check("C4 Collaborator attribution: browser-shaped fetch vs plain fetch after a tool call (F12)") { collaboratorAttribution() }
        check("D1 Collaborator interval setting clamped") { intervalClamp() }
        check("D2 streaming truncation warning") {
            skip("needs Proxy response streaming on a live SSE endpoint; run the manual curl in docs/PROGRESS.md")
        }
        check("E1 streaming settings hint reads Burp's project options") { streamingSettings() }
        check("E2 persistence round trip through Burp's project store") { persistenceRoundTrip() }
        val counts = Outcome.entries.associateWith { o -> results.count { it.outcome == o } }
        report("RESULT: ${counts[Outcome.PASS]} pass, ${counts[Outcome.FAIL]} fail, ${counts[Outcome.WARN]} warn, ${counts[Outcome.SKIP]} skip")
        return results.toList()
    }

    // ---- checks ---------------------------------------------------------------------------------

    private fun responseHit(): Result {
        val token = newToken()
        val body = """{"msg":"$token"}"""
        rt.tracker.place(token, MarkerKind.TOKEN, originFor(body))
        val channels = channelsDuring(token) { post(body) }
        val hit = rt.tracker[token] ?: return fail("marker vanished")
        val problems = ArrayList<String>()
        if (TrafficChannel.HTTP_REQUEST in channels) problems += "request-side hit from an extension send (bug 1)"
        if (TrafficChannel.HTTP_RESPONSE !in channels) problems += "no response-side hit; channels=$channels"
        if (hit.status != MarkerStatus.SEEN_IN_RESPONSE) problems += "status=${hit.status}"
        if (hit.lastEncodings != setOf(MarkerEncoding.RAW)) problems += "encodings=${hit.lastEncodings} (bug 3)"
        val line = MarkerHitLogger.format(hit)
        val format = Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} HIT \[seen in response] in response POST https://httpbin\.org/anything \(raw\) tool=\S+ id=#\d+$""")
        if (!format.matches(line)) problems += "log format: $line (bugs 2/5)"
        return if (problems.isEmpty()) pass(line) else fail(problems.joinToString("; "))
    }

    private fun toolCallHit(): Result {
        val token = newToken()
        val body = """{"tool_calls":[{"function":{"name":"x","arguments":"{\"q\":\"$token\"}"}}]}"""
        rt.tracker.place(token, MarkerKind.TOKEN, originFor(body))
        post(body)
        val hit = rt.tracker[token] ?: return fail("marker vanished")
        return if (hit.status == MarkerStatus.SEEN_IN_TOOL_CALL) pass("status=${hit.status.label}") else fail("status=${hit.status.label} (bug 4)")
    }

    /** httpbin echoes the body, so the response carries an OpenAI `role: tool` message with the token. */
    private fun toolResultHit(): Result {
        val token = newToken()
        val body = """{"messages":[{"role":"tool","tool_call_id":"c1","content":"{\"post\":\"$token\"}"}]}"""
        rt.tracker.place(token, MarkerKind.TOKEN, originFor(body))
        post(body)
        val hit = rt.tracker[token] ?: return fail("marker vanished")
        return if (hit.status == MarkerStatus.SEEN_IN_TOOL_RESULT) pass("status=${hit.status.label}") else fail("status=${hit.status.label} (F4)")
    }

    /**
     * httpbin's `/base64/<value>` returns the decoded value as the body, identical on every call, like a
     * polled endpoint. Two identical fetches must count once; a different body (a POST echo) counts again.
     */
    private fun repeatCountsOnce(): Result {
        val token = newToken()
        rt.tracker.place(token, MarkerKind.TOKEN, null)
        // Padding stays: httpbin decodes with Python's urlsafe_b64decode, which rejects unpadded input and
        // answers "Incorrect Base64 data" (self-test run 19, 2026-10-22). The plaintext is 22 bytes, so it
        // always needs "==".
        val value = Base64.getUrlEncoder().encodeToString("poll says $token".toByteArray())
        val get = HttpRequest.httpRequest(httpbin, "GET /base64/$value HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\n\r\n")
        rt.api.http().sendRequest(get, options)
        val first = rt.tracker[token]?.hitCount ?: return fail("marker vanished")
        rt.api.http().sendRequest(get, options)
        val repeat = rt.tracker[token]?.hitCount ?: return fail("marker vanished")
        post("""{"turn":2,"m":"$token"}""")
        val changed = rt.tracker[token]?.hitCount ?: return fail("marker vanished")
        return when {
            first != 1 -> fail("first fetch gave hitCount=$first (expected 1)")
            repeat != 1 -> fail("identical repeat raised hitCount to $repeat (F8)")
            changed != 2 -> fail("a different message gave hitCount=$changed (expected 2)")
            else -> pass("identical fetch counted once (1, 1), a different message counted (2)")
        }
    }

    private fun base64Hit(): Result {
        val token = newToken()
        val b64 = Base64.getEncoder().encodeToString("the marker is $token ok".toByteArray())
        val body = """{"blob":"$b64"}"""
        rt.tracker.place(token, MarkerKind.TOKEN, originFor(body))
        post(body)
        val hit = rt.tracker[token] ?: return fail("marker vanished")
        return if (hit.lastEncodings == setOf(MarkerEncoding.BASE64)) pass("encodings=${hit.lastEncodings}") else fail("encodings=${hit.lastEncodings}")
    }

    private fun inventory(): Result {
        if (!rt.annotationSettings.matchingEnabled) return skip("'Automatically flag agent-related traffic' is OFF (read live); tick it and run again")
        val corpus = resource("/corpus/openai_request_tools.json")
        val before = rt.inventory["search_docs"]?.messageCount ?: 0
        val rr = post(corpus)
        val search = rt.inventory["search_docs"] ?: return fail("search_docs not in inventory")
        val problems = ArrayList<String>()
        if (search.parameterKeys.toList() != listOf("limit", "query")) problems += "search_docs keys=${search.parameterKeys}"
        if (rt.inventory["send_email"] == null) problems += "send_email missing"
        // httpbin echoes the body, so the response is a second message carrying the same tools: +2, once per message.
        val expected = before + 2
        if (search.messageCount != expected) problems += "messageCount ${search.messageCount} (expected $expected: request + echoed response)"
        val row = rt.traffic.snapshot().lastOrNull { it.url.contains("httpbin.org/anything") && it.source == AnnotationSource.AUTO }
        if (row == null || !row.note.startsWith("CanaryAgent: tools present")) problems += "no auto-annotated traffic row; note=${row?.note}"
        if (problems.isNotEmpty()) return fail(problems.joinToString("; "))
        val notes = rr.annotations()?.notes() ?: ""
        return if (notes.contains("CanaryAgent:")) {
            pass("NEW TOOL logged; note on result: $notes")
        } else {
            warn("inventory and traffic OK; sendRequest result carries no note (Proxy history is the real target, check it manually)")
        }
    }

    private fun markManually(): Result {
        val rr = post("""{"hello":"world"}""")
        val before = rt.traffic.size()
        rt.annotator.markManually(rr, "SelfTest")
        val row = rt.traffic.snapshot().lastOrNull()
        val ok = rt.traffic.size() == before + 1 && row?.source == AnnotationSource.MANUAL
        val notes = rr.annotations()?.notes() ?: ""
        return when {
            !ok -> fail("no MANUAL traffic row; size ${rt.traffic.size()} vs $before")
            notes.contains("CanaryAgent:") -> pass("row added; note set: $notes")
            else -> warn("row added; note not visible on the sendRequest result")
        }
    }

    private fun proxySecondTurn(): Result {
        val proxy = proxyService()
        fun viaProxy(body: String): HttpRequestResponse {
            val raw = "POST http://httpbin.org/anything HTTP/1.1\r\nHost: httpbin.org\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n"
            return rt.api.http().sendRequest(HttpRequest.httpRequest(proxy, raw).withBody(body), options)
        }
        val token = newToken()
        val turn1 = """{"turn":1,"msg":"$token"}"""
        rt.tracker.place(token, MarkerKind.TOKEN, originFor(turn1))

        val first = channelsDuring(token) { viaProxy(turn1) }
        val response = rt.tracker[token]?.lastHit
        if (response == null && first.isEmpty()) {
            return skip("no traffic observed via $proxyListener; is the Proxy listener on that port and does Burp allow plain-HTTP proxying?")
        }
        if (TrafficChannel.HTTP_REQUEST in first) return fail("the inserted turn itself counted as a request-side hit (origin exclusion broken)")

        val turn2 = """{"turn":2,"history":"$token"}"""
        val second = channelsDuring(token) { viaProxy(turn2) }
        return if (TrafficChannel.HTTP_REQUEST in second) {
            pass("turn 1 excluded, turn 2 request-side hit recorded; channels=$second")
        } else {
            fail("second turn to the same endpoint produced no request-side hit (bug 8); channels=$second")
        }
    }

    /**
     * The real-app run of 2026-10-21 showed every Proxy row's note as ` | CanaryAgent: tools present: ...`.
     * Cause (JAR 12 trace): Logger++ parks a `$LPP:<id>$` placeholder in the notes of each message it
     * tracks and strips it later; the join treated it as a note. Sends an agent-shaped body through the
     * listener and reads the row's note back through `api.proxy().history()`. Needs the row to be
     * logged: httpbin.org in Target scope, or out-of-scope Proxy logging enabled. Most telling with
     * Logger++ loaded, which is how the defect was found.
     */
    private fun proxyNote(): Result {
        if (!rt.annotationSettings.matchingEnabled) return skip("'Automatically flag agent-related traffic' is OFF (read live); tick it and run again")
        val expected = "CanaryAgent: tools present: search_docs, send_email [tool keys, messages array, tool schema]"
        val (notes, states) = proxyRowNotes(newToken()) ?: return skip(
            "row not in Proxy history (httpbin.org in scope: ${rt.api.scope().isInScope("http://httpbin.org/anything")}); " +
                "add http://httpbin.org to Target scope or re-enable out-of-scope Proxy logging, then run again",
        )
        for (line in states) report("    row: $line")
        return when {
            notes == expected -> pass("note: $notes")
            notes.isEmpty() -> fail("row carries no note")
            else -> fail("note is '$notes' (expected exactly '$expected')")
        }
    }

    /**
     * Sends the tools corpus through the listener tagged with [tag]; returns the row's final note and the
     * sequence of (hasResponse, note) states seen while polling, or null when the row never showed up.
     */
    private fun proxyRowNotes(tag: String): Pair<String, List<String>>? {
        val proxy = proxyService()
        val raw = "POST http://httpbin.org/anything?selftest=$tag HTTP/1.1\r\nHost: httpbin.org\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n"
        val states = ArrayList<String>()
        val watcher = Thread {
            val until = System.currentTimeMillis() + TIMEOUT_MS
            var last = ""
            try {
                while (System.currentTimeMillis() < until) {
                    val row = rt.api.proxy().history().lastOrNull { it.url().contains("selftest=$tag") }
                    if (row != null) {
                        val state = "hasResponse=${row.hasResponse()} notes='${row.annotations()?.notes()}' color=${row.annotations()?.highlightColor()}"
                        if (state != last) { states += state; last = state }
                    }
                    Thread.sleep(10)
                }
            } catch (_: InterruptedException) {
                // done
            }
        }
        watcher.isDaemon = true
        watcher.start()
        val rr = rt.api.http().sendRequest(HttpRequest.httpRequest(proxy, raw).withBody(resource("/corpus/openai_request_tools.json")), options)
        if (rr.response() == null) return null

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var row: burp.api.montoya.proxy.ProxyHttpRequestResponse? = null
        while (System.currentTimeMillis() < deadline && !cancelled) {
            row = rt.api.proxy().history().lastOrNull { it.url().contains("selftest=$tag") && it.hasResponse() }
            if (row != null) break
            Thread.sleep(POLL_MS)
        }
        if (row == null) return null
        var notes = ""
        while (System.currentTimeMillis() < deadline && !cancelled) {
            notes = row.annotations()?.notes() ?: ""
            if (notes.contains("CanaryAgent:")) break
            Thread.sleep(POLL_MS)
        }
        Thread.sleep(SETTLE_MS)
        notes = row.annotations()?.notes() ?: ""
        watcher.interrupt()
        return notes to states
    }

    /**
     * F10: a marker in a request whose response carries nothing back (httpbin `/status/204`, no body), sent
     * through the Proxy listener so the hit counts. The only possible hit is request-side; it must still
     * produce an Annotated Traffic row (Source marker hit) and a red Proxy note. Before F10 neither appeared.
     */
    private fun requestSideRow(): Result {
        val proxy = proxyService()
        val token = newToken()
        rt.tracker.place(token, MarkerKind.TOKEN, null)
        val raw = "POST http://httpbin.org/status/204?selftest=$token HTTP/1.1\r\nHost: httpbin.org\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n"
        val rr = rt.api.http().sendRequest(HttpRequest.httpRequest(proxy, raw).withBody("""{"note":"$token"}"""), options)
        if (rr.response() == null) return skip("no response via $proxyListener; is the Proxy listener on that port?")
        val hit = rt.tracker[token] ?: return fail("marker vanished")
        if (hit.status != MarkerStatus.SEEN_IN_REQUEST) return fail("status=${hit.status.label}, expected seen in request (the hit itself is not F10)")

        val row = rt.traffic.snapshot().lastOrNull { it.url.contains("selftest=$token") }
            ?: return fail("no Annotated Traffic row for the request-side hit (F10)")
        val problems = ArrayList<String>()
        if (row.source != AnnotationSource.MARKER_HIT) problems += "row source=${row.source}"
        if (!row.note.contains("marker hit: $token")) problems += "row note='${row.note}'"

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var notes = ""
        while (System.currentTimeMillis() < deadline && !cancelled) {
            notes = rt.api.proxy().history().lastOrNull { it.url().contains("selftest=$token") }?.annotations()?.notes() ?: ""
            if (notes.contains(token)) break
            Thread.sleep(POLL_MS)
        }
        if (!notes.contains("marker hit: $token")) problems += "Proxy note='$notes' (row not logged? httpbin.org must be in scope)"
        return if (problems.isEmpty()) pass("row: ${row.note}; Proxy note: $notes") else fail(problems.joinToString("; "))
    }

    private fun webSocketEcho(): Result {
        val creation = rt.api.websockets().createWebSocket(HttpService.httpService("echo.websocket.org", 443, true), "/.ws")
        if (creation.status() != ExtensionWebSocketCreationStatus.SUCCESS) return skip("WebSocket connection failed: ${creation.status()}")
        val ws = creation.webSocket().orElse(null) ?: return skip("no WebSocket object")
        val token = newToken()
        rt.tracker.place(token, MarkerKind.TOKEN, null)
        try {
            ws.sendTextMessage("""{"t":"$token"}""")
            val hit = await(token, WS_TIMEOUT_MS) { it.hitCount > 0 && it.lastHit?.channel == TrafficChannel.WS_IN }
                ?: return warn("no inbound WebSocket hit within ${WS_TIMEOUT_MS / 1000}s; extension-created sockets may bypass WebSocketCreatedHandler. Verify via the browser echo page.")
            val url = hit.lastHit?.url ?: ""
            return if (url.startsWith("wss://echo.websocket.org/.ws")) pass("url=$url status=${hit.status.label}") else fail("url=$url (bug 7)")
        } finally {
            try { ws.close() } catch (_: RuntimeException) { }
        }
    }

    private fun collaboratorOob(): Result {
        val service = rt.collaborator ?: return skip("Community edition: no Collaborator")
        if (!service.isAvailable()) return skip("Collaborator unavailable (disabled in settings?)")
        val payload = service.newPayload() ?: return skip("could not generate a Collaborator payload")
        val host = payload.toString()
        val id = payload.id().toString().lowercase()
        rt.tracker.place(id, MarkerKind.COLLABORATOR, null, label = host)
        collaboratorId = id
        collaboratorHost = host
        try {
            rt.api.http().sendRequest(HttpRequest.httpRequestFromUrl("http://$host/"), options)
        } catch (_: RuntimeException) {
            // The fetch itself may fail; the interaction is what matters.
        }
        val deadline = System.currentTimeMillis() + COLLAB_TIMEOUT_MS
        val seen = LinkedHashSet<String>()
        while (System.currentTimeMillis() < deadline && !cancelled) {
            val interactions = service.fetchInteractions()
            for (i in interactions) seen += i.type().name
            if (interactions.isNotEmpty()) CollaboratorCorrelator.apply(interactions, rt.tracker)
            val hit = rt.tracker[id]
            if (hit?.status == MarkerStatus.OOB_INTERACTION) {
                val withRequest = hit.lastHit?.request != null
                return pass("status=${hit.status.label}; interactions=$seen; request captured=$withRequest")
            }
            Thread.sleep(POLL_MS)
        }
        return fail("no Collaborator interaction within ${COLLAB_TIMEOUT_MS / 1000}s (seen=$seen)")
    }

    private fun collaboratorInBand(): Result {
        val id = collaboratorId ?: return skip("C1 did not run")
        val host = collaboratorHost ?: return skip("C1 did not run")
        val before = rt.tracker[id] ?: return fail("payload no longer tracked")
        post("""{"x":"$host"}""")
        val after = rt.tracker[id] ?: return fail("payload vanished")
        val problems = ArrayList<String>()
        if (after.hitCount <= before.hitCount) problems += "no in-band hit recorded"
        if (before.status == MarkerStatus.OOB_INTERACTION && after.status != MarkerStatus.OOB_INTERACTION) problems += "status demoted to ${after.status}"
        return if (problems.isEmpty()) pass("hits ${before.hitCount} -> ${after.hitCount}, status=${after.status.label}") else fail(problems.joinToString("; "))
    }

    /**
     * F12: attribution labels each interaction without touching the status ladder. Two payloads: one
     * fetched the way a browser would (Sec-Fetch headers, `/favicon.ico`), one first seen in a tool
     * call and then fetched plainly. Both rows must reach "collaborator interaction"; the labels differ.
     * Interactions are caught through a tracker listener, so it does not matter whether this loop or
     * the background poller applies them. Both requests are built from raw bytes: `httpRequestFromUrl`
     * adds a browser-shaped header set (Sec-Fetch-*, Sec-CH-UA, a Chrome User-Agent) in current Burp
     * builds, which made the "plain" fetch browser-attributed in the 2026-10-21 run.
     */
    private fun collaboratorAttribution(): Result {
        val service = rt.collaborator ?: return skip("Community edition: no Collaborator")
        if (!service.isAvailable()) return skip("Collaborator unavailable (disabled in settings?)")
        val browserPayload = service.newPayload() ?: return skip("could not generate a Collaborator payload")
        val agentPayload = service.newPayload() ?: return skip("could not generate a second Collaborator payload")
        val browserId = browserPayload.id().toString().lowercase()
        val agentId = agentPayload.id().toString().lowercase()
        rt.tracker.place(browserId, MarkerKind.COLLABORATOR, null, label = browserPayload.toString())
        rt.tracker.place(agentId, MarkerKind.COLLABORATOR, null, label = agentPayload.toString())

        // Seed the agent-shaped history in-band: httpbin echoes the body, so the response hit is a tool-call hit.
        post("""{"tool_calls":[{"function":{"name":"fetch_url","arguments":"{\"url\":\"http://$agentPayload/\"}"}}]}""")
        val seeded = rt.tracker[agentId]?.status
        if (seeded != MarkerStatus.SEEN_IN_TOOL_CALL) return fail("agent payload not seen in a tool call before its fetch; status=${seeded?.label}")

        val oob = ConcurrentHashMap<String, CopyOnWriteArrayList<TrafficRef>>()
        val listener = MarkerTracker.Listener { t, hit ->
            val last = hit?.lastHit
            if (last != null && (t == browserId || t == agentId) && last.channel == TrafficChannel.COLLABORATOR) {
                oob.computeIfAbsent(t) { CopyOnWriteArrayList() }.add(last)
            }
        }
        rt.tracker.addListener(listener)
        try {
            val browserLike = rawGet(
                browserPayload.toString(), "/favicon.ico",
                "Sec-Fetch-Mode: no-cors", "Sec-Fetch-Dest: image", "Sec-Fetch-Site: cross-site",
                "User-Agent: Mozilla/5.0 (self-test browser-shaped fetch)",
            )
            val plain = rawGet(agentPayload.toString(), "/")
            for (req in listOf(browserLike, plain)) {
                try { rt.api.http().sendRequest(req, options) } catch (_: RuntimeException) { /* the interaction is what matters */ }
            }
            fun httpRef(id: String): TrafficRef? = oob[id]?.firstOrNull { it.method == InteractionType.HTTP.name }
            val deadline = System.currentTimeMillis() + COLLAB_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && !cancelled && (httpRef(browserId) == null || httpRef(agentId) == null)) {
                val interactions = service.fetchInteractions()
                if (interactions.isNotEmpty()) CollaboratorCorrelator.apply(interactions, rt.tracker)
                Thread.sleep(POLL_MS)
            }
            val browserHttp = httpRef(browserId)
                ?: return fail("no HTTP interaction for the browser-shaped fetch within ${COLLAB_TIMEOUT_MS / 1000}s; seen=${oob[browserId]?.map { it.method }}")
            val agentHttp = httpRef(agentId)
                ?: return fail("no HTTP interaction for the plain fetch within ${COLLAB_TIMEOUT_MS / 1000}s; seen=${oob[agentId]?.map { it.method }}")

            val problems = ArrayList<String>()
            val browserStatus = rt.tracker[browserId]?.status
            val agentStatus = rt.tracker[agentId]?.status
            if (browserStatus != MarkerStatus.OOB_INTERACTION) problems += "browser-shaped row not promoted: ${browserStatus?.label} (attribution must never suppress)"
            if (agentStatus != MarkerStatus.OOB_INTERACTION) problems += "agent-shaped row not promoted: ${agentStatus?.label}"
            if (browserHttp.attribution != Attribution.BROWSER) problems += "browser-shaped fetch attributed ${browserHttp.attribution?.label} ${browserHttp.attributionSignals}"
            if (browserHttp.attributionSignals.none { it.startsWith("Sec-Fetch-Mode") } || "path /favicon.ico" !in browserHttp.attributionSignals) {
                problems += "browser signals incomplete: ${browserHttp.attributionSignals}"
            }
            if (agentHttp.attribution != Attribution.AGENT) problems += "plain fetch after a tool call attributed ${agentHttp.attribution?.label} ${agentHttp.attributionSignals}"
            val dns = oob.values.flatten().filter { it.method == InteractionType.DNS.name }
            if (dns.any { it.attribution == Attribution.BROWSER }) problems += "a DNS interaction was browser-attributed: ${dns.map { it.attributionSignals }}"
            if (problems.isNotEmpty()) return fail(problems.joinToString("; "))
            return pass(
                "browser-shaped: ${browserHttp.attribution?.label} ${browserHttp.attributionSignals}; " +
                    "plain after tool call: ${agentHttp.attribution?.label} ${agentHttp.attributionSignals}; " +
                    "DNS: ${dns.map { it.attribution?.label }}; both rows ${MarkerStatus.OOB_INTERACTION.label}",
            )
        } finally {
            rt.tracker.removeListener(listener)
        }
    }

    /**
     * The live client's key must reach the project store and `restoreClient` must accept it. The origin
     * line tells which path this load took: CREATED on the first run, RESTORED on every reload after it.
     */
    private fun collaboratorKeyPersisted(): Result {
        val service = rt.collaborator ?: return skip("Community edition: no Collaborator")
        val persistence = rt.persistence ?: return skip("project persistence unavailable")
        val live = service.secretKey() ?: return skip("no Collaborator client yet (C1 did not run)")
        if (!persistence.isWriting()) return skip("'Do not persist' is ticked (read live); the key stays queued until it is unticked")
        persistence.flushNow()
        val stored = persistence.loadCollaboratorKey() ?: return fail("no key stored after flush")
        if (stored != live) return fail("stored key differs from the live client's key")
        val back = rt.api.collaborator().restoreClient(SecretKey.secretKey(stored)).secretKey.toString()
        if (back != stored) return fail("restoreClient returned a different key")
        return pass("origin=${service.origin}; stored key matches the live client; restoreClient round trip ok")
    }

    /** Dumps the real streaming section so the key names the hint relies on can be verified. */
    private fun streamingSettings(): Result {
        val json = rt.api.burpSuite().exportProjectOptionsAsJson("project_options.http")
        val finding = StreamingSettingsHint.analyze(json)
        val start = json.indexOf("streaming", ignoreCase = true)
        val excerpt = if (start >= 0) json.substring(start, minOf(json.length, start + 600)) else json.take(300)
        report("    options excerpt: $excerpt")
        return if (finding == null) {
            warn("no streaming section found; key names differ from what the hint expects (see excerpt)")
        } else {
            pass("section=${finding.sectionKey} eventStream=${finding.eventStreamAsStreaming} prefixes=${finding.prefixes} hidesSse=${finding.hidesSseResponses}")
        }
    }

    /** Writes the live stores through Burp's PersistedObject and reads them back into fresh stores. */
    private fun persistenceRoundTrip(): Result {
        val service = rt.persistence ?: return skip("project persistence unavailable")
        if (!service.isWriting()) return skip("'Do not persist' is ticked (read live), so nothing is written; untick and run again")
        val withRequest = rt.tracker.snapshot().firstOrNull { it.lastHit?.request != null }
        service.flushNow()
        val tracker2 = MarkerTracker()
        val inventory2 = ToolInventory()
        val (markers, tools) = PersistenceService.restoreInto(MontoyaNode(rt.api.persistence().extensionData()), tracker2, inventory2)
        val problems = ArrayList<String>()
        if (markers != rt.tracker.size()) problems += "restored $markers markers, live ${rt.tracker.size()}"
        if (tools != rt.inventory.size()) problems += "restored $tools tools, live ${rt.inventory.size()}"
        for (live in rt.tracker.snapshot()) {
            val back = tracker2[live.token] ?: run { problems += "missing ${live.token}"; continue }
            if (back.status != live.status || back.hitCount != live.hitCount || back.encodings != live.encodings) problems += "mismatch ${live.token}"
            if (back.agentPathStatus != live.agentPathStatus) problems += "agent-path status not restored for ${live.token}"
            if (back.lastHit?.attribution != live.lastHit?.attribution || back.lastHit?.attributionSignals != live.lastHit?.attributionSignals) {
                problems += "attribution not restored for ${live.token}"
            }
            val shown = live.labelledInteraction
            if (back.labelledInteraction?.attribution != shown?.attribution || back.labelledInteraction?.attributionSignals != shown?.attributionSignals) {
                problems += "labelled interaction not restored for ${live.token}"
            }
        }
        if (withRequest != null && tracker2[withRequest.token]?.lastHit?.request == null) problems += "request copy not restored for ${withRequest.token}"
        val attributed = rt.tracker.snapshot().count { it.lastHit?.attribution != null }
        return if (problems.isEmpty()) pass("$markers markers, $tools tools restored identically; request copies ${if (withRequest != null) "verified" else "not exercised"}; attribution on $attributed row(s) restored")
        else fail(problems.joinToString("; "))
    }

    private fun intervalClamp(): Result {
        val raw = rt.settings.describe().substringAfter("interval=")
        val v = rt.settings.collaboratorIntervalSeconds
        val ok = v in CanaryAgentSettings.MIN_COLLABORATOR_INTERVAL.toLong()..CanaryAgentSettings.MAX_COLLABORATOR_INTERVAL.toLong()
        return if (ok) pass("raw=$raw clamped=${v}s (read live before every poll)") else fail("interval=$v outside 5..300")
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun check(name: String, block: () -> Result) {
        if (cancelled) return
        val result = try {
            block()
        } catch (t: Throwable) {
            Result(name, Outcome.FAIL, "${t.javaClass.simpleName}: ${t.message}")
        }.copy(name = name)
        results += result
        report("${result.outcome} $name: ${result.detail}")
    }

    private fun pass(detail: String) = Result("", Outcome.PASS, detail)
    private fun fail(detail: String) = Result("", Outcome.FAIL, detail)
    private fun warn(detail: String) = Result("", Outcome.WARN, detail)
    private fun skip(detail: String) = Result("", Outcome.SKIP, detail)

    private fun newToken(): String = MarkerGenerator.nextUnique { rt.tracker.contains(it) }

    /** Origin as the context menu would record it for a request sent from a user-driven tool. */
    private fun originFor(body: String) =
        MarkerOrigin("SelfTest", "POST", "httpbin.org", "/anything", "body", Hashing.sha256Hex(body))

    /** A GET built from raw bytes so Burp sends exactly these headers and nothing browser-shaped of its own. */
    private fun rawGet(host: String, path: String, vararg headers: String): HttpRequest {
        val raw = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\nHost: ").append(host).append("\r\n")
            for (h in headers) append(h).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        return HttpRequest.httpRequest(HttpService.httpService(host, 80, false), raw)
    }

    private fun post(body: String): HttpRequestResponse {
        val raw = "POST /anything HTTP/1.1\r\nHost: httpbin.org\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n"
        return rt.api.http().sendRequest(HttpRequest.httpRequest(httpbin, raw).withBody(body), options)
    }

    /** Channels of every hit recorded for [token] while [block] runs (handlers run inside sendRequest). */
    private fun channelsDuring(token: String, block: () -> Unit): List<TrafficChannel> {
        val channels = CopyOnWriteArrayList<TrafficChannel>()
        var lastCount = rt.tracker[token]?.hitCount ?: 0
        val listener = MarkerTracker.Listener { t, hit ->
            if (t == token && hit != null && hit.hitCount > lastCount) {
                lastCount = hit.hitCount
                hit.lastHit?.channel?.let(channels::add)
            }
        }
        rt.tracker.addListener(listener)
        try {
            block()
            Thread.sleep(SETTLE_MS)
        } finally {
            rt.tracker.removeListener(listener)
        }
        return channels.toList()
    }

    private fun await(token: String, timeoutMs: Long, predicate: (MarkerHit) -> Boolean): MarkerHit? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !cancelled) {
            rt.tracker[token]?.takeIf(predicate)?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    private fun resource(path: String): String =
        checkNotNull(SelfTestRunner::class.java.getResourceAsStream(path)) { "missing resource $path" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    companion object {
        /** System property: Proxy listener port for the through-Burp cases (default 8080). */
        const val PROP_PROXY_PORT = "canaryagent.selftest.proxyPort"
        private const val TIMEOUT_MS = 15_000L
        private const val WS_TIMEOUT_MS = 10_000L
        private const val COLLAB_TIMEOUT_MS = 45_000L
        const val POLL_MS = 250L
        const val SETTLE_MS = 300L
    }
}
