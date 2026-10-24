package com.canaryagent.collaborator

import burp.api.montoya.http.message.requests.HttpRequest
import com.canaryagent.model.Attribution
import com.canaryagent.model.MarkerStatus

/**
 * Labels one Collaborator interaction with what its own evidence suggests caused it. Attribution is a
 * label beside the status, never a filter: every matched interaction is still recorded and still
 * promotes the row. The rules, in precedence order:
 *
 * 1. Direct evidence in the interaction's HTTP request that a browser sent it (`Sec-Fetch-*` or a
 *    `/favicon.ico` fetch) decides [Attribution.BROWSER], whatever the marker's history. `Sec-CH-UA`,
 *    `Upgrade-Insecure-Requests` and `Referer` are listed as supporting evidence but never decide.
 *    Browsers send `Sec-Fetch-*` and `Sec-CH-UA` only to https origins: a browser visiting an http://
 *    payload carries neither (2026-10-21 real-app run), so testers should plant https:// payload URLs.
 * 2. Otherwise, a marker already seen in agent-shaped traffic (a tool call, or a message the detector
 *    flags as agent traffic; read from [com.canaryagent.model.MarkerHit.agentPathStatus], which no
 *    interaction changes) makes the interaction [Attribution.AGENT]. The tester's own browsing of the app
 *    (posting the payload, viewing the page) does not count.
 * 3. Otherwise [Attribution.UNATTRIBUTED]; DNS interactions carry no headers and land here unless rule 2 applies.
 *
 * `User-Agent` is listed as evidence when present but never decides: fetch tools send browser strings
 * by default. Client address, timing, DNS query type and scheme are deliberately not consulted.
 */
object InteractionAttribution {
    /** The label plus the header evidence it rests on, in the order it was checked. */
    data class Result(val attribution: Attribution, val signals: List<String>)

    /** Sent by current Chromium and Firefox on every navigation and sub-resource fetch; page script cannot suppress them. */
    val SEC_FETCH_HEADERS: List<String> = listOf("Sec-Fetch-Mode", "Sec-Fetch-Dest", "Sec-Fetch-Site")

    /** Browser-only headers recorded as supporting evidence; they never decide on their own. */
    val SUPPORTING_HEADERS: List<String> = listOf("Sec-CH-UA", "Upgrade-Insecure-Requests", "Referer")

    const val FAVICON_PATH = "/favicon.ico"

    /** Statuses that say the payload passed through the agent pipeline in-band before this interaction. */
    val AGENT_PIPELINE_STATUSES: Set<MarkerStatus> =
        setOf(MarkerStatus.SEEN_IN_REQUEST, MarkerStatus.SEEN_IN_RESPONSE, MarkerStatus.SEEN_IN_TOOL_RESULT, MarkerStatus.SEEN_IN_TOOL_CALL)

    private const val PRIOR_STATUS_PREFIX = "prior status: "
    private const val VALUE_CHARS = 40
    private const val REFERER_CHARS = 80
    private const val USER_AGENT_CHARS = 60

    /** [request] is the interaction's captured HTTP request (null for DNS/SMTP); [agentPathStatus] the row's highest agent-path status. */
    fun attribute(request: HttpRequest?, agentPathStatus: MarkerStatus?): Result =
        attribute(request?.pathWithoutQuery(), { name -> request?.headerValue(name) }, agentPathStatus)

    /** Pure form over the path and a header lookup, so the rules are unit-tested without Montoya objects. */
    fun attribute(path: String?, header: (String) -> String?, agentPathStatus: MarkerStatus?): Result {
        val browser = ArrayList<String>()
        for (name in SEC_FETCH_HEADERS) header(name)?.let { browser += "$name: ${it.trim().take(VALUE_CHARS)}" }
        if (path != null && path.equals(FAVICON_PATH, ignoreCase = true)) browser += "path $FAVICON_PATH"
        val evidence = ArrayList<String>()
        for (name in SUPPORTING_HEADERS) {
            val value = header(name) ?: continue
            evidence += if (name == "Referer") "$name: ${value.trim().take(REFERER_CHARS)}" else name
        }
        header("User-Agent")?.let { evidence += "User-Agent: ${it.trim().take(USER_AGENT_CHARS)}" }

        if (browser.isNotEmpty()) return Result(Attribution.BROWSER, browser + evidence)
        if (agentPathStatus != null && agentPathStatus in AGENT_PIPELINE_STATUSES) {
            return Result(Attribution.AGENT, listOf(PRIOR_STATUS_PREFIX + agentPathStatus.label) + evidence)
        }
        return Result(Attribution.UNATTRIBUTED, evidence)
    }
}
