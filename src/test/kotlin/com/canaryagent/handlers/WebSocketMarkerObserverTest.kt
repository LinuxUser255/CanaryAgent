package com.canaryagent.handlers

import burp.api.montoya.core.ToolType
import burp.api.montoya.logging.Logging
import burp.api.montoya.websocket.Direction
import com.canaryagent.markers.MarkerScanner
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.MarkerKind
import com.canaryagent.model.MarkerStatus
import com.canaryagent.util.CanaryLog
import java.io.PrintStream
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebSocketMarkerObserverTest {
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

    /** Collects deferred work so the test controls when recording happens. */
    private class QueuedExecutor : Executor {
        val queue = ArrayList<Runnable>()
        override fun execute(command: Runnable) { queue.add(command) }
        fun flush() { for (r in queue) r.run(); queue.clear() }
    }

    private val token = "cnryws1234567"
    private val tracker = MarkerTracker().apply { place(token, MarkerKind.TOKEN, null) }
    private val executor = QueuedExecutor()
    private val observer = WebSocketMarkerObserver(tracker, MarkerScanner(tracker), CanaryLog(CapturedLogging()), { false }, executor)
    private val endpoint = WebSocketEndpoint("wss://app.local/chat", "app.local", "/chat", ToolType.PROXY)

    @Test
    fun `hits are returned synchronously but recorded on the executor`() {
        val hits = observer.observe("""{"reply":"$token"}""", Direction.SERVER_TO_CLIENT, endpoint)
        assertEquals(listOf(token), hits.map { it.token }, "the frame's hits must be available to annotate from")
        // The handler thread has returned; recording has not happened yet.
        assertEquals(MarkerStatus.PLACED, tracker[token]!!.status)
        assertEquals(0, tracker[token]!!.hitCount)
        executor.flush()
        assertEquals(MarkerStatus.SEEN_IN_RESPONSE, tracker[token]!!.status)
        assertEquals(1, tracker[token]!!.hitCount)
    }

    @Test
    fun `a frame without markers defers nothing`() {
        observer.observe("""{"reply":"nothing to see"}""", Direction.SERVER_TO_CLIENT, endpoint)
        assertTrue(executor.queue.isEmpty(), "no hit, no deferred work")
    }
}
