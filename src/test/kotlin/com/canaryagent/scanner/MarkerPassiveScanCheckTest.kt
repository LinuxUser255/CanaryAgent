package com.canaryagent.scanner

import burp.api.montoya.logging.Logging
import com.canaryagent.markers.MarkerScanner
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.MarkerKind
import com.canaryagent.model.MarkerStatus
import com.canaryagent.util.CanaryLog
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkerPassiveScanCheckTest {
    private class CapturedLogging : Logging {
        override fun output(): PrintStream = System.out
        override fun error(): PrintStream = System.err
        override fun logToOutput(message: String) {}
        override fun logToOutput(message: Any?) {}
        override fun logToError(message: String) {}
        override fun logToError(message: String, cause: Throwable) {}
        override fun logToError(cause: Throwable) {}
        override fun raiseDebugEvent(message: String) {}
        override fun raiseInfoEvent(message: String) {}
        override fun raiseErrorEvent(message: String) {}
        override fun raiseCriticalEvent(message: String) {}
    }

    private val token = "cnryabc12345"
    private val tracker = MarkerTracker()
    private val check = MarkerPassiveScanCheck(tracker, MarkerScanner(tracker), CanaryLog(CapturedLogging()))

    private val toolCallBody = """{"tool_calls":[{"function":{"arguments":"{\"q\":\"$token\"}"}}]}"""
    private val toolResultBody = """{"messages":[{"role":"tool","content":"$token"}]}"""
    private val plainBody = """{"reply":"the page mentioned $token somewhere"}"""

    @Test
    fun `no tracked markers yields no specs`() {
        val specs = check.findIssueSpecs(plainBody, plainBody, null, null)
        assertTrue(specs.isEmpty())
    }

    @Test
    fun `tool-call token produces the tool-call rung`() {
        tracker.place(token, MarkerKind.TOKEN, null)
        val specs = check.findIssueSpecs(toolCallBody, toolCallBody, null, null)
        assertEquals(listOf(IssueRung.TOOL_CALL), specs.map { it.rung })
        assertEquals(listOf(token), specs[0].tokens)
    }

    @Test
    fun `tool-result token produces the tool-result rung`() {
        tracker.place(token, MarkerKind.TOKEN, null)
        val specs = check.findIssueSpecs(toolResultBody, toolResultBody, null, null)
        assertEquals(listOf(IssueRung.TOOL_RESULT), specs.map { it.rung })
    }

    @Test
    fun `plain resurfacing produces the information rung`() {
        tracker.place(token, MarkerKind.TOKEN, null)
        val specs = check.findIssueSpecs(null, null, plainBody, plainBody)
        assertEquals(listOf(IssueRung.RESURFACED), specs.map { it.rung })
    }

    @Test
    fun `a token folds to its strongest rung across both directions`() {
        tracker.place(token, MarkerKind.TOKEN, null)
        // Plain in the request, inside a tool call in the response: one spec, the strongest rung.
        val specs = check.findIssueSpecs(plainBody, plainBody, toolCallBody, toolCallBody)
        assertEquals(listOf(IssueRung.TOOL_CALL), specs.map { it.rung })
    }

    @Test
    fun `the check never records a hit - the tracker is read-only to it`() {
        tracker.place(token, MarkerKind.TOKEN, null)
        val specs = check.findIssueSpecs(toolCallBody, toolCallBody, plainBody, plainBody)
        assertTrue(specs.isNotEmpty(), "sanity: the scan did find the marker")
        val hit = tracker[token]!!
        assertEquals(MarkerStatus.PLACED, hit.status, "doCheck must not promote the ladder")
        assertEquals(0, hit.hitCount, "doCheck must not count hits")
    }

    @Test
    fun `bodies over the cap are not scanned`() {
        tracker.place(token, MarkerKind.TOKEN, null)
        val small = MarkerPassiveScanCheck(tracker, MarkerScanner(tracker), CanaryLog(CapturedLogging()), maxBodyBytes = 10)
        assertTrue(small.findIssueSpecs(plainBody, plainBody, null, null).isEmpty())
    }

    @Test
    fun `detail identity line is stable and consolidation keys on it`() {
        tracker.place(token, MarkerKind.TOKEN, null)
        val before = check.findIssueSpecs(plainBody, plainBody, null, null).single().detail
        // Ladder status changes between audits of the same finding; the identity line must not.
        tracker.recordHit(token, inToolCall = false, encodings = emptySet(), ref = null)
        val after = check.findIssueSpecs(plainBody, plainBody, null, null).single().detail
        assertEquals("Markers: $token", MarkerPassiveScanCheck.markerLine(before))
        assertEquals(MarkerPassiveScanCheck.markerLine(before), MarkerPassiveScanCheck.markerLine(after))
        assertTrue(MarkerPassiveScanCheck.sameFinding(IssueRung.RESURFACED.issueName, before, IssueRung.RESURFACED.issueName, after))
    }

    @Test
    fun `different tokens or rungs are distinct findings`() {
        val other = "cnryxyz67890"
        tracker.place(token, MarkerKind.TOKEN, null)
        tracker.place(other, MarkerKind.TOKEN, null)
        val a = check.findIssueSpecs(plainBody, plainBody, null, null).single().detail
        val b = check.findIssueSpecs(plainBody.replace(token, other), plainBody.replace(token, other), null, null).single().detail
        assertFalse(MarkerPassiveScanCheck.sameFinding(IssueRung.RESURFACED.issueName, a, IssueRung.RESURFACED.issueName, b))
        assertFalse(MarkerPassiveScanCheck.sameFinding(IssueRung.TOOL_CALL.issueName, a, IssueRung.RESURFACED.issueName, a))
    }
}
