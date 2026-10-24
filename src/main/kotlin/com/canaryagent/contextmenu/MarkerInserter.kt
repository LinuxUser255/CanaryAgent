package com.canaryagent.contextmenu

import burp.api.montoya.core.Range
import burp.api.montoya.http.message.HttpMessage
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse.SelectionContext
import com.canaryagent.model.MarkerOrigin
import com.canaryagent.util.Hashing
import burp.api.montoya.core.ByteArray as BurpByteArray

/**
 * Splices text into the message currently shown in a Burp editor, at the selection (replacing it)
 * or at the caret, but only where a marker can be tracked: inside a header value or in the body.
 * The request/status line, header names and the line breaks between them are refused with a
 * one-line message so the token never lands in front of the HTTP method (the caret sits at offset 0
 * right after "Send to Repeater"). Responses accept the body only.
 *
 * Body edits go through `withBody` so Burp fixes Content-Length; header edits rebuild the request from
 * raw bytes. The classification itself ([classify], [plan]) is pure and unit-tested on raw bytes.
 */
object MarkerInserter {

    /** Where a caret offset sits inside a raw HTTP message. */
    sealed class CaretRegion {
        /** The request line or the status line. */
        object StartLine : CaretRegion()

        /** Inside a header's name, or on a header line without a colon. */
        data class HeaderName(val name: String) : CaretRegion()

        /** After the colon of a header; [name] keeps the header's casing. */
        data class HeaderValue(val name: String) : CaretRegion()

        /** Inside a CRLF, or on the blank line between headers and body. */
        object Separator : CaretRegion()

        /** Anywhere in the body, including an empty one. */
        object Body : CaretRegion()
    }

    /** What [insert] will do for a caret or selection. */
    sealed class Plan {
        /** Nothing is inserted; [message] is shown to the user. */
        data class Refuse(val message: String) : Plan()

        /** The marker goes into [region]; only header values and the body are insertable. */
        data class Insert(val region: CaretRegion) : Plan() {
            val inBody: Boolean get() = region is CaretRegion.Body

            /** Location recorded on the marker's origin: `header <Name>` or `body`. */
            val location: String
                get() = when (region) {
                    is CaretRegion.HeaderValue -> "header ${region.name}"
                    else -> "body"
                }
        }
    }

    /** Outcome of [insert]. */
    sealed class Result {
        /** Where text was inserted and the hash of the request body as it will be sent. */
        data class Inserted(val field: String, val requestBodyHash: String) : Result()

        /** Nothing was changed; [message] is the one-line explanation for the user. */
        data class Refused(val message: String) : Result()
    }

    const val REFUSE_REQUEST = "Place the caret inside a header value or the body."
    const val REFUSE_RESPONSE = "Place the caret inside the response body."

    /** The refusal message for the editor's current caret/selection, or null when insertion is allowed. */
    fun refusal(editor: MessageEditorHttpRequestResponse): String? {
        val isRequest = editor.selectionContext() == SelectionContext.REQUEST
        val message: HttpMessage = if (isRequest) editor.requestResponse().request() else editor.requestResponse().response()
        val raw = message.toByteArray().bytes
        val (start, end) = targetRange(editor.selectionOffsets().orElse(null), editor.caretPosition(), raw.size)
        return (plan(raw, message.bodyOffset(), start, end, isRequest) as? Plan.Refuse)?.message
    }

    /** Inserts [text] into the request or response of [editor], or refuses without touching it. */
    fun insert(editor: MessageEditorHttpRequestResponse, text: String): Result {
        val rr = editor.requestResponse()
        val isRequest = editor.selectionContext() == SelectionContext.REQUEST
        val message: HttpMessage = if (isRequest) rr.request() else rr.response()
        val original = message.toByteArray().bytes
        val (start, end) = targetRange(editor.selectionOffsets().orElse(null), editor.caretPosition(), original.size)
        val bodyOffset = message.bodyOffset()
        val plan = when (val p = plan(original, bodyOffset, start, end, isRequest)) {
            is Plan.Refuse -> return Result.Refused(p.message)
            is Plan.Insert -> p
        }
        val updated = splice(original, start, end, text.toByteArray(Charsets.ISO_8859_1))
        val inBody = plan.inBody

        val requestBody: ByteArray
        if (isRequest) {
            val req = rr.request()
            requestBody = if (inBody) updated.copyOfRange(bodyOffset, updated.size) else original.copyOfRange(bodyOffset, original.size)
            val newReq = if (inBody) {
                req.withBody(BurpByteArray.byteArray(*requestBody))
            } else {
                HttpRequest.httpRequest(req.httpService(), BurpByteArray.byteArray(*updated))
            }
            editor.setRequest(newReq)
        } else {
            val resp = rr.response()
            requestBody = rr.request()?.body()?.bytes ?: ByteArray(0)
            editor.setResponse(resp.withBody(BurpByteArray.byteArray(*updated.copyOfRange(bodyOffset, updated.size))))
        }
        val field = if (inBody && isRequest) describeBodyParam(rr.request(), start) ?: plan.location else plan.location
        return Result.Inserted(field, Hashing.sha256Hex(requestBody))
    }

    /** Hash of the request body currently shown in [editor] (for tracking a selection without editing). */
    fun requestBodyHash(editor: MessageEditorHttpRequestResponse): String =
        Hashing.sha256Hex(editor.requestResponse().request()?.body()?.bytes ?: ByteArray(0))

    /**
     * Builds the origin record for a marker placed at [field] in the request shown in [editor].
     * [bodyHash] identifies the exact message so later turns to the same endpoint still count as hits.
     */
    fun originOf(editor: MessageEditorHttpRequestResponse, tool: String, field: String, bodyHash: String): MarkerOrigin? {
        val req = editor.requestResponse().request() ?: return null
        val service = req.httpService()
        return MarkerOrigin(
            tool = tool,
            method = req.method(),
            host = service?.host() ?: "",
            path = req.pathWithoutQuery(),
            location = field,
            bodyHash = bodyHash,
        )
    }

    /** Text currently selected in [editor], or null when there is no selection. */
    fun selectedText(editor: MessageEditorHttpRequestResponse): String? {
        val range = editor.selectionOffsets().orElse(null) ?: return null
        val rr = editor.requestResponse()
        val message: HttpMessage = if (editor.selectionContext() == SelectionContext.REQUEST) rr.request() else rr.response()
        val bytes = message.toByteArray().bytes
        val start = range.startIndexInclusive().coerceIn(0, bytes.size)
        val end = range.endIndexExclusive().coerceIn(start, bytes.size)
        if (end <= start) return null
        return String(bytes, start, end - start, Charsets.ISO_8859_1)
    }

    /**
     * Decides whether text may go in at [start, end) of [raw] (end == start for a plain caret).
     * Requests accept header values and the body; responses the body only. A selection must start
     * and end in the same region so replacing it cannot eat a header name or a line break.
     */
    fun plan(raw: ByteArray, bodyOffset: Int, start: Int, end: Int = start, isRequest: Boolean = true): Plan {
        val from = classify(raw, bodyOffset, start)
        val to = if (end > start) classify(raw, bodyOffset, end) else from
        if (from != to) return Plan.Refuse(if (isRequest) REFUSE_REQUEST else REFUSE_RESPONSE)
        return when {
            from is CaretRegion.Body -> Plan.Insert(from)
            isRequest && from is CaretRegion.HeaderValue -> Plan.Insert(from)
            isRequest -> Plan.Refuse(REFUSE_REQUEST)
            else -> Plan.Refuse(REFUSE_RESPONSE)
        }
    }

    /**
     * Classifies caret [offset] ("before byte offset") in [raw], whose body starts at [bodyOffset].
     * Lines end at LF with an optional CR before it; a caret right before that CR is still inside
     * the value, a caret between CR and LF or on the blank line is a [CaretRegion.Separator].
     */
    fun classify(raw: ByteArray, bodyOffset: Int, offset: Int): CaretRegion {
        val body = bodyOffset.coerceIn(0, raw.size)
        val off = offset.coerceIn(0, raw.size)
        if (off >= body) return CaretRegion.Body

        var lineStart = 0
        var lineIndex = 0
        while (lineStart < body) {
            var nl = lineStart
            while (nl < body && raw[nl] != '\n'.code.toByte()) nl++
            val lineEnd = if (nl < body) nl + 1 else body // exclusive, includes the line break
            val contentEnd = if (nl > lineStart && raw[nl - 1] == '\r'.code.toByte()) nl - 1 else nl
            if (off < lineEnd) {
                if (lineIndex == 0) return CaretRegion.StartLine
                if (contentEnd == lineStart || off > contentEnd) return CaretRegion.Separator
                val line = String(raw, lineStart, contentEnd - lineStart, Charsets.ISO_8859_1)
                val colon = line.indexOf(':')
                if (colon < 0) return CaretRegion.HeaderName(line.trim())
                val name = line.substring(0, colon).trim()
                return if (off - lineStart <= colon) CaretRegion.HeaderName(name) else CaretRegion.HeaderValue(name)
            }
            lineStart = lineEnd
            lineIndex++
        }
        return CaretRegion.Body
    }

    internal fun targetRange(selection: Range?, caret: Int, length: Int): Pair<Int, Int> {
        if (selection != null) {
            val s = selection.startIndexInclusive().coerceIn(0, length)
            val e = selection.endIndexExclusive().coerceIn(s, length)
            return s to e
        }
        val c = caret.coerceIn(0, length)
        return c to c
    }

    internal fun splice(original: ByteArray, start: Int, end: Int, insert: ByteArray): ByteArray {
        val out = ByteArray(original.size - (end - start) + insert.size)
        System.arraycopy(original, 0, out, 0, start)
        System.arraycopy(insert, 0, out, start, insert.size)
        System.arraycopy(original, end, out, start + insert.size, original.size - end)
        return out
    }

    /** `json param "content"` when [offset] falls inside a body parameter's value; null otherwise. */
    private fun describeBodyParam(request: HttpRequest?, offset: Int): String? {
        val param = request?.parameters()?.firstOrNull { p ->
            val r = p.valueOffsets()
            offset >= r.startIndexInclusive() && offset <= r.endIndexExclusive()
        } ?: return null
        return "${param.type().name.lowercase()} param \"${param.name()}\""
    }
}
