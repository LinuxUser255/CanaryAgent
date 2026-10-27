package com.canaryagent.scanner

import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.scanner.AuditResult
import burp.api.montoya.scanner.ConsolidationAction
import burp.api.montoya.scanner.audit.issues.AuditIssue
import burp.api.montoya.scanner.audit.issues.AuditIssueConfidence
import burp.api.montoya.scanner.audit.issues.AuditIssueSeverity
import burp.api.montoya.scanner.scancheck.PassiveScanCheck
import com.canaryagent.markers.MarkerScanner
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.MarkerEncoding
import com.canaryagent.util.CanaryLog

/** The evidence rung an audit issue reports; mirrors the marker status ladder's in-band rungs. */
enum class IssueRung(
    /** Fixed issue name; consolidation and Burp's own grouping key off it. */
    val issueName: String,
) {
    TOOL_CALL("Canary marker in LLM tool call"),
    TOOL_RESULT("Canary marker in tool result"),
    RESURFACED("Canary marker resurfaced in traffic"),
}

/**
 * One prospective audit issue: the rung, the tokens that reached it in this message, and the
 * rendered detail. Pure Kotlin so the construction logic is unit-testable without Burp.
 * [detail]'s first line (up to the first `<br>`) lists the tokens and is stable across re-scans
 * of the same finding; everything after it may change (ladder status), so consolidation compares
 * only the first line.
 */
data class MarkerIssueSpec(val rung: IssueRung, val tokens: List<String>, val detail: String)

/**
 * Passive scan check that surfaces marker resurfacing as Burp Scanner audit issues (Professional).
 *
 * Strictly additive and read-only: [HttpObservationHandler][com.canaryagent.handlers.HttpObservationHandler]
 * remains the only writer of [MarkerTracker] state, so audit re-scans can never inflate hit counts,
 * and detection itself never depends on the Scanner (Community, non-audited traffic and WebSocket
 * are covered by the handlers alone). This check re-scans the audited pair and reads the tracker
 * for context.
 */
class MarkerPassiveScanCheck(
    private val tracker: MarkerTracker,
    private val scanner: MarkerScanner,
    private val log: CanaryLog,
    private val maxBodyBytes: Int = MarkerScanner.DEFAULT_MAX_CHARS,
) : PassiveScanCheck {

    companion object {
        private const val REMEDIATION =
            "Treat all content an LLM agent reads (documents, pages, tool results) as untrusted input. " +
                "Constrain tool availability and arguments server-side, and require confirmation for " +
                "consequential actions, so that read content cannot steer tool calls."

        private const val BACKGROUND =
            "CanaryAgent plants unique marker tokens into content an LLM agent may read. A marker " +
                "resurfacing in traffic shows that content reached the application or the model; a marker " +
                "inside a tool call's arguments shows the model acted on content it read - indirect prompt " +
                "injection, observed rather than inferred."

        /** The stable identity line of a detail: everything before the first `<br>`. */
        fun markerLine(detail: String): String = detail.substringBefore("<br>")

        /** Whether two issues describe the same finding: same rung name, same marker line. */
        fun sameFinding(existingName: String, existingDetail: String, newName: String, newDetail: String): Boolean =
            existingName == newName && markerLine(existingDetail) == markerLine(newDetail)
    }

    override fun checkName(): String = "CanaryAgent marker detection"

    override fun doCheck(baseRequestResponse: HttpRequestResponse): AuditResult {
        log.once(
            "passive-check-first-run",
            "Scanner integration: passive check invoked by an audit for the first time.",
        )
        return try {
            val specs = findIssueSpecs(
                requestText = baseRequestResponse.request()?.toString(),
                requestBody = baseRequestResponse.request()?.bodyToString(),
                responseText = baseRequestResponse.response()?.toString(),
                responseBody = baseRequestResponse.response()?.bodyToString(),
            )
            if (specs.isEmpty()) return AuditResult.auditResult(emptyList())
            val baseUrl = baseRequestResponse.request().url()
            AuditResult.auditResult(specs.map { it.toAuditIssue(baseUrl, baseRequestResponse) })
        } catch (t: RuntimeException) {
            log.error("Passive check failed; no issue raised for this message.", t)
            AuditResult.auditResult(emptyList())
        }
    }

    /**
     * Burp asks when this check reported several issues for the same URL path. The same rung with the
     * same marker set is the same finding: keep the newer issue (its ladder status is the freshest).
     * Anything else is distinct evidence and both stay.
     */
    override fun consolidateIssues(existingIssue: AuditIssue, newIssue: AuditIssue): ConsolidationAction =
        if (sameFinding(existingIssue.name(), existingIssue.detail(), newIssue.name(), newIssue.detail())) {
            ConsolidationAction.KEEP_NEW
        } else {
            ConsolidationAction.KEEP_BOTH
        }

    /**
     * Pure issue construction: scans both directions, folds each token to its strongest rung in this
     * message, and renders one spec per rung. Reads [tracker] only; never records a hit.
     */
    internal fun findIssueSpecs(
        requestText: String?,
        requestBody: String?,
        responseText: String?,
        responseBody: String?,
    ): List<MarkerIssueSpec> {
        if (tracker.size() == 0) return emptyList()

        /** Strongest classification of each token across both directions. */
        val toolCall = HashSet<String>()
        val toolResult = HashSet<String>()
        val plain = HashSet<String>()
        val encodings = HashMap<String, MutableSet<MarkerEncoding>>()

        fun scanSide(text: String?, body: String?) {
            if (text.isNullOrEmpty()) return
            if (body != null && body.length > maxBodyBytes) return
            for (m in scanner.scan(text, body)) {
                when {
                    m.inToolCall -> toolCall.add(m.token)
                    m.inToolResult -> toolResult.add(m.token)
                    else -> plain.add(m.token)
                }
                encodings.getOrPut(m.token) { LinkedHashSet() }.addAll(m.encodings)
            }
        }
        scanSide(requestText, requestBody)
        scanSide(responseText, responseBody)

        toolResult.removeAll(toolCall)
        plain.removeAll(toolCall)
        plain.removeAll(toolResult)

        return listOfNotNull(
            specFor(IssueRung.TOOL_CALL, toolCall, encodings),
            specFor(IssueRung.TOOL_RESULT, toolResult, encodings),
            specFor(IssueRung.RESURFACED, plain, encodings),
        )
    }

    private fun specFor(
        rung: IssueRung,
        tokens: Set<String>,
        encodings: Map<String, Set<MarkerEncoding>>,
    ): MarkerIssueSpec? {
        if (tokens.isEmpty()) return null
        val sorted = tokens.sorted()
        val detail = buildString {
            append("Markers: ").append(sorted.joinToString(", "))
            for (token in sorted) {
                val hit = tracker[token]
                append("<br><b>").append(token).append("</b>: ")
                append("seen as ").append((encodings[token] ?: emptySet()).joinToString(", ") { it.label }.ifEmpty { "raw" })
                if (hit != null) {
                    hit.origin?.let { append("; placed at ").append(it.description) }
                    append("; ladder status: ").append(hit.status.label)
                }
            }
            append("<br>Hit counts and full history are in the CanaryAgent tab.")
        }
        return MarkerIssueSpec(rung, sorted, detail)
    }

    private fun MarkerIssueSpec.toAuditIssue(baseUrl: String, pair: HttpRequestResponse): AuditIssue =
        AuditIssue.auditIssue(
            rung.issueName,
            detail,
            REMEDIATION,
            baseUrl,
            severityOf(rung),
            AuditIssueConfidence.FIRM,
            BACKGROUND,
            REMEDIATION,
            severityOf(rung),
            pair,
        )

    private fun severityOf(rung: IssueRung): AuditIssueSeverity = when (rung) {
        IssueRung.TOOL_CALL -> AuditIssueSeverity.HIGH
        IssueRung.TOOL_RESULT -> AuditIssueSeverity.MEDIUM
        IssueRung.RESURFACED -> AuditIssueSeverity.INFORMATION
    }
}
