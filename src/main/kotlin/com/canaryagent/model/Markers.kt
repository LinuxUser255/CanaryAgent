package com.canaryagent.model

import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import java.net.URI

/** How a marker was created. */
enum class MarkerKind(val label: String) {
    /** Generated `cnry...` token. */
    TOKEN("marker"),
    /** User-supplied or selected text; matched without the fixed prefix. */
    CUSTOM("custom"),
    /** Burp Collaborator payload (Professional only). */
    COLLABORATOR("collaborator"),
}

/** Lifecycle of a marker. Status only ever moves to a higher [rank]; tool call is terminal. */
enum class MarkerStatus(val label: String, val rank: Int) {
    PLACED("placed", 0),
    /** Seen in an outgoing request or client-to-server WebSocket frame (outside tool-call keys). */
    SEEN_IN_REQUEST("seen in request", 1),
    /** Seen in a response or server-to-client WebSocket frame (outside tool-call keys). */
    SEEN_IN_RESPONSE("seen in response", 2),
    /**
     * Inside a tool result handed to the model (F4): content the agent read was delivered to it. Weaker
     * than a tool call, which shows the model acted on it; stronger than a plain sighting, which a user
     * typing the token also produces.
     */
    SEEN_IN_TOOL_RESULT("seen in tool result", 3),
    SEEN_IN_TOOL_CALL("seen in tool call", 4),
    /** Burp Collaborator recorded a DNS/HTTP/SMTP interaction for the payload: strongest evidence. */
    OOB_INTERACTION("collaborator interaction", 5),
}

/** Encoding variant in which a marker was observed. A hit can carry several. */
enum class MarkerEncoding(val label: String) {
    RAW("raw"),
    CASE_CHANGED("case-changed"),
    JSON_ESCAPED("json-escaped"),
    URL_ENCODED("url-encoded"),
    BASE64("base64"),
}

/**
 * What the evidence inside one Collaborator interaction suggests caused it. Describes that interaction,
 * never the marker: it sits beside [MarkerStatus] and never changes it.
 */
enum class Attribution(val label: String) {
    /** Direct evidence in the interaction's own request that a browser produced it. */
    BROWSER("browser-attributed"),
    /** No browser evidence, and the marker had already travelled through the agent pipeline in-band. */
    AGENT("agent-attributed"),
    /** Neither; most DNS interactions land here. */
    UNATTRIBUTED("unattributed"),
}

/** Where traffic was observed. Outbound channels are subject to the hit policy. */
enum class TrafficChannel(val label: String, val isOutbound: Boolean) {
    HTTP_REQUEST("request", true),
    HTTP_RESPONSE("response", false),
    WS_OUT("ws->", true),
    WS_IN("ws<-", false),
    /** Out-of-band Collaborator interaction; always counts. */
    COLLABORATOR("oob", false),
}

/**
 * Where a marker was placed. Used to exclude the originating request from hits: only a request to
 * the same endpoint whose body hashes to [bodyHash] is the origin, so later turns to an LLM endpoint
 * that reuse the path still count.
 *
 * A marker that left through the clipboard has no origin until it is first seen; [firstSeenIn] then
 * names that message (with an empty hash, so it excludes nothing).
 */
data class MarkerOrigin(
    val tool: String,
    val method: String,
    val host: String,
    val path: String,
    /** Human-readable location inside the message, e.g. `json param "content"`. */
    val location: String,
    /** SHA-256 of the message body right after insertion; empty when unknown (then nothing is excluded). */
    val bodyHash: String = "",
) {
    val description: String
        get() = "$method $host$path ($location)"

    companion object {
        /** "Placed at" text while a clipboard-sourced marker has not been seen anywhere yet. */
        const val CLIPBOARD = "clipboard"

        /** Origin for a clipboard-sourced marker: the first message it was seen in. Never excludes hits. */
        fun firstSeenIn(ref: TrafficRef): MarkerOrigin {
            val uri = runCatching { URI(ref.url) }.getOrNull()?.takeIf { it.host != null }
            val where = "$CLIPBOARD, first seen in ${ref.channel.label}" + if (ref.messageId.isNotEmpty()) " ${ref.messageId}" else ""
            return MarkerOrigin(
                tool = ref.tool,
                method = ref.method.ifEmpty { "WS" },
                host = uri?.host ?: "",
                path = uri?.rawPath ?: ref.url,
                location = where,
                bodyHash = "",
            )
        }
    }
}

/**
 * Lightweight reference to a message in which something was observed.
 * [request] and [response] must be `copyToTempFile()` copies, never live handler objects.
 */
data class TrafficRef(
    val channel: TrafficChannel,
    val tool: String,
    /** HTTP method; empty for WebSocket frames. */
    val method: String,
    val url: String,
    val timestamp: Long,
    val request: HttpRequest? = null,
    val response: HttpResponse? = null,
    /** Short excerpt for WebSocket messages, which have no request/response pair. */
    val excerpt: String? = null,
    /** `#<Burp message id>` for HTTP, `sha:<8 hex>` of the payload for WebSocket frames. */
    val messageId: String = "",
    /** Collaborator interactions only: what caused this interaction, per [attributionSignals]. */
    val attribution: Attribution? = null,
    /** The signals that produced [attribution], in precedence order; legible, never a score. */
    val attributionSignals: List<String> = emptyList(),
)

/** A placed marker and everything observed about it so far. Immutable. */
data class MarkerHit(
    /** Lowercased token used for matching. */
    val token: String,
    /** Display form (original casing). */
    val label: String,
    val kind: MarkerKind,
    val origin: MarkerOrigin?,
    val placedAt: Long,
    val status: MarkerStatus = MarkerStatus.PLACED,
    /**
     * Highest in-band status reached in agent-shaped traffic only: inside a tool call, or in a message the
     * detector flags as agent traffic (provider requests and responses). Never raised by a Collaborator
     * interaction, and not by the tester's own browsing of the app (a forum post, a page view). [status]
     * collapses once an interaction promotes the row; this keeps the fact that the payload travelled
     * through the agent pipeline, for attribution of later interactions.
     */
    val agentPathStatus: MarkerStatus? = null,
    /** Every encoding seen across all hits. */
    val encodings: Set<MarkerEncoding> = emptySet(),
    /** Encodings of the most recent hit only. */
    val lastEncodings: Set<MarkerEncoding> = emptySet(),
    val hitCount: Int = 0,
    val lastHitAt: Long? = null,
    val lastHit: TrafficRef? = null,
    /**
     * The Collaborator interaction whose attribution the row shows: the most recent one labelled browser
     * or agent, else the most recent interaction. Kept apart from [lastHit] so a later in-band hit (a page
     * listing the payload) or a trailing DNS lookup does not hide how the fetch was attributed.
     */
    val labelledInteraction: TrafficRef? = null,
)
