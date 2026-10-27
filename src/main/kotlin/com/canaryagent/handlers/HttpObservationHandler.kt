package com.canaryagent.handlers

import burp.api.montoya.core.Annotations
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import burp.api.montoya.http.message.MimeType
import com.canaryagent.annotation.MessageAnalysis
import com.canaryagent.annotation.StreamingCheck
import com.canaryagent.annotation.TrafficAnnotator
import com.canaryagent.markers.HitPolicy
import com.canaryagent.markers.MarkerMatch
import com.canaryagent.markers.MarkerScanner
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.AnnotationSource
import com.canaryagent.model.TrafficChannel
import com.canaryagent.model.TrafficRef
import com.canaryagent.util.CanaryLog
import com.canaryagent.util.Hashing
import com.canaryagent.util.JsonSupport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Observes every HTTP message Burp sends or receives (all tools, all scopes).
 *
 * Two independent jobs: marker observation (always on) and heuristic annotation + tool inventory
 * (subject to the settings). Never modifies traffic; only returns annotations (highlight + note),
 * which Burp applies to the Proxy history item.
 */
class HttpObservationHandler(
    private val tracker: MarkerTracker,
    private val scanner: MarkerScanner,
    private val annotator: TrafficAnnotator,
    private val log: CanaryLog,
    private val maxBodyBytes: Int = MarkerScanner.DEFAULT_MAX_CHARS,
    /** Runs work the returned annotations do not depend on (F-review [3]); direct in tests. */
    private val executor: Executor = ObservationExecutor.DIRECT,
) : HttpHandler {

    /** Request-side analysis awaiting its response, keyed by message id. Bounded crudely. */
    private val pending = ConcurrentHashMap<Int, MessageAnalysis>()

    /**
     * Defers [work] to the executor. Everything it touches must already be captured: plain strings,
     * immutable models, `copyToTempFile()` copies and the shared parse - never a live handler object,
     * which Burp may invalidate once the handler returns.
     */
    private fun defer(what: String, work: () -> Unit) {
        executor.execute {
            try {
                work()
            } catch (t: RuntimeException) {
                log.error("Deferred observation work failed: $what", t)
            }
        }
    }

    // Both stages return the plain `continueWith(message)` ("annotations will not be modified") unless
    // something was actually added.

    override fun handleHttpRequestToBeSent(request: HttpRequestToBeSent): RequestToBeSentAction {
        val original = request.annotations()
        var annotations = original
        try {
            // One parse per message, shared by marker classification and annotation (F-review [2]);
            // lazy, so a message that triggers neither never parses. Null when the body is over the cap.
            val parsed = if (request.body().length() <= maxBodyBytes) {
                lazy { JsonSupport.parseFragments(request.bodyToString()) }
            } else null
            val hits = observeMarkersInRequest(request, parsed)
            annotations = annotateRequestHits(request, hits, annotations)
            annotations = annotateRequest(request, annotations, parsed)
        } catch (t: RuntimeException) {
            log.error("Observation failed for request ${request.url()}", t)
        }
        return if (annotations === original) RequestToBeSentAction.continueWith(request)
        else RequestToBeSentAction.continueWith(request, annotations)
    }

    override fun handleHttpResponseReceived(response: HttpResponseReceived): ResponseReceivedAction {
        val original = response.annotations()
        var annotations = original
        try {
            warnIfStreamedTruncated(response)
            // Shared parse, as on the request side. Null when the response is binary or over the cap.
            val parsed = if (!isBinary(response.inferredMimeType()) && response.body().length() <= maxBodyBytes) {
                lazy { JsonSupport.parseFragments(response.bodyToString()) }
            } else null
            val hits = observeMarkersInResponse(response, parsed)
            annotations = annotateResponse(response, hits, annotations, parsed)
        } catch (t: RuntimeException) {
            log.error("Observation failed for response to ${response.initiatingRequest().url()}", t)
        }
        return if (annotations === original) ResponseReceivedAction.continueWith(response)
        else ResponseReceivedAction.continueWith(response, annotations)
    }

    // ---- markers -------------------------------------------------------------------------------

    /** Records request-side hits per [HitPolicy]; returns the hits that counted (empty when none). */
    private fun observeMarkersInRequest(request: HttpRequestToBeSent, parsed: Lazy<List<Any>>?): List<MarkerMatch> {
        if (tracker.size() == 0) return emptyList()
        val tool = request.toolSource().toolType()
        // Outbound traffic from user-driven tools is the user re-sending their own marker; only the
        // Proxy can produce request-side hits, and never for the request the marker was inserted into.
        if (!HitPolicy.shouldCount(TrafficChannel.HTTP_REQUEST, tool, isOrigin = false)) return emptyList()

        val text = if (parsed != null) request.toString() else headersOnly(request)
        val matches = if (parsed != null) scanner.scan(text, parsed) else scanner.scan(text)
        if (matches.isEmpty()) return emptyList()

        val method = request.method()
        val host = request.httpService().host()
        val path = request.pathWithoutQuery()
        val bodyHash = Hashing.sha256Hex(request.body().bytes)
        val hits = matches.filter { HitPolicy.shouldCount(TrafficChannel.HTTP_REQUEST, tool, tracker.isOrigin(it.token, method, host, path, bodyHash)) }
        if (hits.isEmpty()) return emptyList()

        val ref = TrafficRef(
            channel = TrafficChannel.HTTP_REQUEST,
            tool = tool.toString(),
            method = method,
            url = request.url(),
            timestamp = System.currentTimeMillis(),
            request = request.copyToTempFile(),
            messageId = "#${request.messageId()}",
        )
        // F8: the same body to the same URL again (a retry, a replayed form) does not raise the hit count.
        val fingerprint = MarkerTracker.fingerprint(TrafficChannel.HTTP_REQUEST, method, request.url(), bodyHash)
        // Captured now; the deferred work must not touch the live request. The scan already forced the
        // shared parse, so forcing it again below is free.
        val body = if (parsed != null) request.bodyToString() else null
        val contentType = request.headerValue("Content-Type")
        val requestPath = request.path()
        defer("request marker hits") {
            val agentShaped = parsed != null && body != null &&
                annotator.analyze(body, contentType, requestPath, parsed.value).isAgentRelated
            for (m in hits) {
                tracker.recordHit(m.token, m.inToolCall, m.encodings, ref, agentContext = agentShaped, inToolResult = m.inToolResult, fingerprint = fingerprint)
            }
        }
        return hits
    }

    /**
     * F10: a request that carried a counted marker gets the same red highlight, Proxy note and Annotated
     * Traffic row as a response hit. The row is keyed by message id, so the response merges into it.
     * Runs whatever the matching toggle says, like all marker observation.
     */
    private fun annotateRequestHits(request: HttpRequestToBeSent, hits: List<MarkerMatch>, annotations: Annotations): Annotations {
        if (hits.isEmpty()) return annotations
        val note = TrafficAnnotator.markerHitNote(hits)
        val tool = request.toolSource().toolType().toString()
        val requestCopy = request.copyToTempFile()
        val messageKey = "#${request.messageId()}"
        defer("request hit row") { annotator.recordTraffic(tool, requestCopy, null, note, AnnotationSource.MARKER_HIT, messageKey) }
        return annotator.annotate(annotations, note, TrafficAnnotator.MARKER_COLOR)
    }

    private fun observeMarkersInResponse(response: HttpResponseReceived, parsed: Lazy<List<Any>>?): List<MarkerMatch> {
        if (tracker.size() == 0) return emptyList()
        if (parsed == null) {
            if (!isBinary(response.inferredMimeType())) {
                log.once("resp-too-large", "Skipping marker scan of responses larger than $maxBodyBytes bytes.")
            }
            return emptyList()
        }
        val matches = scanner.scan(response.toString(), parsed)
        if (matches.isEmpty()) return emptyList()

        val initiating = response.initiatingRequest()
        val ref = TrafficRef(
            channel = TrafficChannel.HTTP_RESPONSE,
            tool = response.toolSource().toolType().toString(),
            method = initiating.method(),
            url = initiating.url(),
            timestamp = System.currentTimeMillis(),
            request = initiating.copyToTempFile(),
            response = response.copyToTempFile(),
            messageId = "#${response.messageId()}",
        )
        // F8: a poll or page reload returning the same body for the same URL does not raise the hit count.
        val fingerprint = MarkerTracker.fingerprint(
            TrafficChannel.HTTP_RESPONSE, initiating.method(), initiating.url(), Hashing.sha256Hex(response.body().bytes),
        )
        // Captured now; the deferred work must not touch the live messages (scan already forced the parse).
        val respBody = response.bodyToString()
        val respContentType = response.headerValue("Content-Type")
        val reqPath = initiating.path()
        val reqBody = if (initiating.body().length() <= maxBodyBytes) initiating.bodyToString() else null
        val reqContentType = initiating.headerValue("Content-Type")
        defer("response marker hits") {
            // A response belongs to the agent pipeline when it, or the request it answers, is agent-shaped.
            val agentShaped = annotator.analyze(respBody, respContentType, reqPath, parsed.value).isAgentRelated ||
                (reqBody != null && annotator.analyze(reqBody, reqContentType, reqPath).isAgentRelated)
            for (m in matches) {
                tracker.recordHit(m.token, m.inToolCall, m.encodings, ref, agentContext = agentShaped, inToolResult = m.inToolResult, fingerprint = fingerprint)
            }
        }
        return matches
    }

    // ---- heuristics + inventory ----------------------------------------------------------------

    private fun annotateRequest(request: HttpRequestToBeSent, annotations: Annotations, parsed: Lazy<List<Any>>?): Annotations {
        if (!annotator.shouldAutoAnnotate(request.isInScope)) return annotations
        if (parsed == null) return annotations
        val analysis = annotator.analyze(request, parsed.value)
        if (!analysis.isAgentRelated) return annotations

        val url = request.url()
        defer("request inventory") { annotator.recordTools(analysis, url) }
        if (pending.size > MAX_PENDING) pending.clear()
        pending[request.messageId()] = analysis
        return annotator.annotate(annotations, annotator.noteFor(analysis), annotator.colorFor(analysis))
    }

    private fun annotateResponse(
        response: HttpResponseReceived,
        hits: List<MarkerMatch>,
        annotations: Annotations,
        parsed: Lazy<List<Any>>?,
    ): Annotations {
        val request = response.initiatingRequest()
        val tool = response.toolSource().toolType().toString()
        // Both notes below land on the same Annotated Traffic row (and the same Proxy note).
        val messageKey = "#${response.messageId()}"
        var result = annotations

        if (hits.isNotEmpty()) {
            val note = TrafficAnnotator.markerHitNote(hits)
            val requestCopy = request.copyToTempFile()
            val responseCopy = response.copyToTempFile()
            defer("response hit row") { annotator.recordTraffic(tool, requestCopy, responseCopy, note, AnnotationSource.MARKER_HIT, messageKey) }
            result = annotator.annotate(result, note, TrafficAnnotator.MARKER_COLOR)
        }

        val fromRequest = pending.remove(response.messageId())
        if (!annotator.shouldAutoAnnotate(request.isInScope)) return result
        if (parsed == null) return result

        val own = annotator.analyze(response, request, parsed.value)
        val url = request.url()
        if (own.isAgentRelated) defer("response inventory") { annotator.recordTools(own, url) }
        val combined = fromRequest?.merge(own) ?: own
        if (!combined.isAgentRelated) return result

        val note = annotator.noteFor(combined)
        val requestCopy = request.copyToTempFile()
        val responseCopy = response.copyToTempFile()
        defer("auto row") { annotator.recordTraffic(tool, requestCopy, responseCopy, note, AnnotationSource.AUTO, messageKey) }
        return annotator.annotate(result, note, annotator.colorFor(combined))
    }

    /** One-time warning when a streamed response reaches us before the server finished sending it. */
    private fun warnIfStreamedTruncated(response: HttpResponseReceived) {
        val body = response.body()
        if (body.length() > maxBodyBytes) return
        val truncated = StreamingCheck.looksTruncated(
            response.headerValue("Content-Type"),
            response.headerValue("Transfer-Encoding"),
            response.bodyToString(),
        )
        if (!truncated) return
        log.once(
            "streaming-truncated",
            "A streamed response (${response.initiatingRequest().url()}, ${body.length()} bytes) arrived incomplete. " +
                "Markers and tools inside the missing part cannot be seen. Disable Proxy -> 'Stream responses' " +
                "for this host, or turn off streaming in the client while testing (docs/DEVELOPMENT.md section 6). " +
                "This warning is shown once.",
        )
    }

    private fun headersOnly(request: HttpRequestToBeSent): String =
        buildString {
            append(request.method()).append(' ').append(request.url()).append('\n')
            for (h in request.headers()) append(h.name()).append(": ").append(h.value()).append('\n')
        }

    private fun isBinary(mime: MimeType): Boolean = when (mime) {
        MimeType.IMAGE_UNKNOWN, MimeType.IMAGE_JPEG, MimeType.IMAGE_GIF, MimeType.IMAGE_PNG,
        MimeType.IMAGE_BMP, MimeType.IMAGE_TIFF, MimeType.SOUND, MimeType.VIDEO,
        MimeType.APPLICATION_FLASH, MimeType.FONT_WOFF, MimeType.FONT_WOFF2, MimeType.LEGACY_SER_AMF,
        -> true
        else -> false
    }

    private companion object {
        const val MAX_PENDING = 2000
    }
}
