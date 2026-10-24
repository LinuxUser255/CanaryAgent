package com.canaryagent.annotation

/**
 * Heuristic for responses that reached the handler before the server finished sending them, which
 * happens when Burp's Proxy streams responses straight through. Markers and tools inside the missing
 * part are invisible to the extension, so the handler logs a one-time warning.
 */
object StreamingCheck {
    private val TEXT_LIKE = listOf("text/", "json", "event-stream", "ndjson")

    /**
     * True for a server-sent-events body that is empty or does not end with the blank line that
     * terminates every SSE event, and for an empty chunked body of a text-like type.
     */
    fun looksTruncated(contentType: String?, transferEncoding: String?, body: String): Boolean {
        val ct = contentType?.lowercase() ?: ""
        if (ct.contains("text/event-stream")) {
            if (body.isEmpty()) return true
            return !body.replace("\r\n", "\n").endsWith("\n\n")
        }
        val chunked = transferEncoding?.contains("chunked", ignoreCase = true) == true
        if (chunked && body.isEmpty()) return ct.isEmpty() || TEXT_LIKE.any { ct.contains(it) }
        return false
    }
}
