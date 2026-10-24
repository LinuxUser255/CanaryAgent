package com.canaryagent.annotation

import burp.api.montoya.core.Annotations
import burp.api.montoya.core.HighlightColor
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import com.canaryagent.inventory.ObservedTool
import com.canaryagent.markers.MarkerMatch
import com.canaryagent.inventory.ToolExtractor
import com.canaryagent.inventory.ToolInventory
import com.canaryagent.model.AnnotatedTrafficItem
import com.canaryagent.model.AnnotationSource
import com.canaryagent.settings.AnnotationSettings
import com.canaryagent.util.Hashing

/** Heuristic result for one message plus the tools visible in it. */
data class MessageAnalysis(val detection: Detection, val tools: List<ObservedTool>) {
    val isAgentRelated: Boolean get() = detection.isAgentRelated

    /** Request and response analyses combined: union of signals, tools de-duplicated by lowercased name. */
    fun merge(other: MessageAnalysis): MessageAnalysis =
        MessageAnalysis(detection.merge(other.detection), (tools + other.tools).distinctBy { it.name.lowercase() })

    companion object {
        val NONE = MessageAnalysis(Detection.NONE, emptyList())
    }
}

/**
 * Runs parser -> detector -> tool extractor over messages, feeds the inventory and the annotated
 * traffic list, and composes Proxy-history annotations (highlight + note). Pure analysis is in
 * [analyze]; the Montoya-facing helpers are thin.
 */
class TrafficAnnotator(
    private val parser: BodyParser,
    private val detector: AgentTrafficDetector,
    private val inventory: ToolInventory,
    private val store: AnnotatedTrafficStore,
    private val settings: AnnotationSettings,
) {
    companion object {
        const val NOTE_PREFIX = "CanaryAgent:"
        val STRONG_COLOR: HighlightColor = HighlightColor.ORANGE
        val MEDIUM_COLOR: HighlightColor = HighlightColor.YELLOW
        val MARKER_COLOR: HighlightColor = HighlightColor.RED
        private const val MAX_TOOLS_IN_NOTE = 5

        /**
         * `CanaryAgent: marker hit: cnry… (tool call), cnry… (tool result), cnry…` for the hits in one message.
         * Shared by the HTTP request and response paths and the Proxy WebSocket handler.
         */
        /**
         * `CanaryAgent: marker hit: <token> (tool call); <token> (tool result); <token>`. Each label
         * belongs to the token it follows; labelled tokens come first and entries are separated by `; `
         * so a label cannot read as covering its neighbour (`a, b (tool result)` once suggested both
         * were in the tool result).
         */
        fun markerHitNote(hits: List<MarkerMatch>): String {
            val labelled = hits.filter { it.inToolCall || it.inToolResult }
            val plain = hits.filter { !it.inToolCall && !it.inToolResult }
            return "$NOTE_PREFIX marker hit: " + (labelled + plain).joinToString("; ") { m ->
                m.token + when {
                    m.inToolCall -> " (tool call)"
                    m.inToolResult -> " (tool result)"
                    else -> ""
                }
            }
        }

        /**
         * Logger++ parks a correlation placeholder (`$LPP:<id>$`) in the notes of every message it tracks
         * and removes it again later. It is not a note: treating it as one produced `$LPP:…$ | CanaryAgent: …`
         * and, once stripped, ` | CanaryAgent: …` on every Proxy row (real-app run of 2026-10-21).
         */
        private val PLACEHOLDER = Regex("[$]LPP:\\d+[$]")

        /**
         * [existing] with [note] appended as `a | b`, unchanged when it already contains it. Foreign
         * placeholders are moved to the end, directly after the text, so that removing them leaves a
         * clean note and no dangling separator.
         */
        fun joinNotes(existing: String?, note: String): String {
            if (existing.isNullOrBlank()) return note
            val placeholders = PLACEHOLDER.findAll(existing).joinToString("") { it.value }
            val text = existing.replace(PLACEHOLDER, "").trim()
            if (text.contains(note)) return existing
            val joined = if (text.isEmpty()) note else "$text | $note"
            return joined + placeholders
        }
    }

    /** Analyses one body. [path] is only used for the path-fragment heuristic. */
    fun analyze(body: String, contentType: String?, path: String?): MessageAnalysis {
        val roots = parser.parse(body, contentType)
        if (roots.isEmpty()) return MessageAnalysis.NONE
        val detection = detector.detect(roots, path)
        if (!detection.isAgentRelated) return MessageAnalysis.NONE
        return MessageAnalysis(detection, ToolExtractor.extract(roots))
    }

    /**
     * True when [body] is traffic the detector flags as agent-related (strong or medium signals). Used for
     * marker attribution, so it ignores the matching toggle like all marker observation does.
     */
    fun isAgentShaped(body: String, contentType: String?, path: String?): Boolean =
        analyze(body, contentType, path).isAgentRelated

    /** Analyses a request body with its Content-Type and path. */
    fun analyze(request: HttpRequest): MessageAnalysis =
        analyze(request.bodyToString(), request.headerValue("Content-Type"), request.path())

    /** Analyses a response body; the initiating [request] supplies the path for the agent-path signal. */
    fun analyze(response: HttpResponse, request: HttpRequest?): MessageAnalysis =
        analyze(response.bodyToString(), response.headerValue("Content-Type"), request?.path())

    /** Whether automatic matching applies to this request. Markers are never affected by this. */
    fun shouldAutoAnnotate(inScope: Boolean): Boolean =
        settings.matchingEnabled && (!settings.inScopeOnly || inScope)

    /** Adds the tools of one message to the inventory. [source] is the request URL. */
    fun recordTools(analysis: MessageAnalysis, source: String) {
        if (analysis.tools.isNotEmpty()) inventory.record(analysis.tools, source)
    }

    /**
     * Appends an entry to the annotated traffic list, or merges it into the row already recorded for
     * the same message when [messageKey] (e.g. `#<Burp message id>`) is given.
     */
    fun recordTraffic(
        tool: String,
        request: HttpRequest,
        response: HttpResponse?,
        note: String,
        source: AnnotationSource,
        messageKey: String = "",
    ): AnnotatedTrafficItem = store.add { id ->
        AnnotatedTrafficItem(
            id = id,
            timestamp = System.currentTimeMillis(),
            tool = tool,
            method = request.method(),
            url = request.url(),
            note = note,
            source = source,
            sessionHint = sessionHint(request),
            request = request.copyToTempFile(),
            response = response?.copyToTempFile(),
            messageKey = messageKey,
        )
    }

    /** Manual "mark as agent content": ignores the matching toggle, annotates and inventories the pair. */
    fun markManually(rr: HttpRequestResponse, tool: String) {
        val request = rr.request() ?: return
        val response: HttpResponse? = rr.response()
        var analysis = analyze(request)
        if (response != null) analysis = analysis.merge(analyze(response, request))
        recordTools(analysis, request.url())
        val note = if (analysis.isAgentRelated) noteFor(analysis) else "$NOTE_PREFIX marked as agent content"
        recordTraffic(tool, request, response, note, AnnotationSource.MANUAL)
        val annotations = rr.annotations()
        annotations.setNotes(appendNote(annotations.notes(), note))
        annotations.setHighlightColor(if (analysis.detection.strong) STRONG_COLOR else MEDIUM_COLOR)
    }

    /**
     * Short note for Proxy history: `CanaryAgent: tools present: search_docs, fetch_url [signals]` when
     * tools were offered or called, `CanaryAgent: agent traffic [signals]` for strong traffic without
     * tools (a chat-history response, a messages array on its own), `CanaryAgent: agent-like …` for
     * medium evidence.
     */
    fun noteFor(analysis: MessageAnalysis): String {
        val d = analysis.detection
        val tools = analysis.tools.map { it.name }
        val head = when {
            d.strong && tools.isEmpty() -> "agent traffic"
            d.strong -> "tools present"
            else -> "agent-like"
        }
        val toolPart = when {
            tools.isEmpty() -> ""
            tools.size <= MAX_TOOLS_IN_NOTE -> ": " + tools.joinToString(", ")
            else -> ": " + tools.take(MAX_TOOLS_IN_NOTE).joinToString(", ") + " (+${tools.size - MAX_TOOLS_IN_NOTE})"
        }
        val why = d.signals.sortedBy { it.ordinal }.take(3).joinToString(", ") { it.label }
        return "$NOTE_PREFIX $head$toolPart [$why]"
    }

    /** Highlight for an auto-annotated row: [STRONG_COLOR] or [MEDIUM_COLOR]; marker hits use [MARKER_COLOR]. */
    fun colorFor(analysis: MessageAnalysis): HighlightColor =
        if (analysis.detection.strong) STRONG_COLOR else MEDIUM_COLOR

    /**
     * Returns [annotations] with [note] appended (deduplicated) and the highlight raised to [color].
     * Returns the very same instance when neither changes, so callers can tell Burp "not modified"
     * instead of handing back a copy.
     */
    fun annotate(annotations: Annotations, note: String, color: HighlightColor): Annotations {
        val notes = appendNote(annotations.notes(), note)
        val existing = annotations.highlightColor()
        val raise = existing == null || existing == HighlightColor.NONE || color == MARKER_COLOR
        val newColor = if (raise) color else existing
        if (notes == annotations.notes() && newColor == existing) return annotations
        var a = annotations.withNotes(notes)
        if (newColor != existing) a = a.withHighlightColor(color)
        return a
    }

    internal fun appendNote(existing: String?, note: String): String = joinNotes(existing, note)

    /** Best-effort grouping key from the session cookie / Authorization header (hashed, never stored raw). */
    internal fun sessionHint(request: HttpRequest): String {
        val material = request.headerValue("Authorization") ?: request.headerValue("Cookie") ?: return ""
        return Hashing.short(material)
    }
}
