package com.canaryagent.model

import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse

/** Why a message ended up in the Annotated Traffic list. */
enum class AnnotationSource(val label: String) {
    AUTO("auto"),
    MANUAL("manual"),
    MARKER_PLACED("marker placed"),
    MARKER_HIT("marker hit"),
}

/**
 * One annotated request/response. [request] and [response] are `copyToTempFile()` copies.
 * Items sharing a non-empty [messageKey] describe the same Burp message and are merged into one row.
 */
data class AnnotatedTrafficItem(
    val id: Long,
    val timestamp: Long,
    val tool: String,
    val method: String,
    val url: String,
    val note: String,
    val source: AnnotationSource,
    /** Best-effort grouping hint (session cookie / auth header hash), may be empty. */
    val sessionHint: String = "",
    val request: HttpRequest? = null,
    val response: HttpResponse? = null,
    /** `#<Burp message id>` for handler-recorded traffic; empty when the message has no stable identity. */
    val messageKey: String = "",
)
