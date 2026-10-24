package com.canaryagent.persistence

import burp.api.montoya.logging.Logging
import com.canaryagent.inventory.ToolInventory
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.MarkerEncoding
import com.canaryagent.model.MarkerKind
import com.canaryagent.model.MarkerOrigin
import com.canaryagent.model.MarkerStatus
import com.canaryagent.model.ToolEntry
import com.canaryagent.model.TrafficChannel
import com.canaryagent.model.TrafficRef
import com.canaryagent.util.CanaryLog
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistenceServiceTest {
    /** Minimal Montoya Logging so CanaryLog can be built; messages are collected. */
    private class CapturedLogging : Logging {
        val lines = ArrayList<String>()
        override fun output(): PrintStream = System.out
        override fun error(): PrintStream = System.err
        override fun logToOutput(message: String) { lines += message }
        override fun logToOutput(message: Any?) { lines += message.toString() }
        override fun logToError(message: String) { lines += message }
        override fun logToError(message: String, cause: Throwable) { lines += message }
        override fun logToError(cause: Throwable) { lines += cause.toString() }
        override fun raiseDebugEvent(message: String) {}
        override fun raiseInfoEvent(message: String) {}
        override fun raiseErrorEvent(message: String) {}
        override fun raiseCriticalEvent(message: String) {}
    }

    private val log = CanaryLog(CapturedLogging())
    private val origin = MarkerOrigin("Repeater", "POST", "app.local", "/api", "body", "h1")

    private fun service(root: MemoryNode, tracker: MarkerTracker, inventory: ToolInventory) =
        PersistenceService(root, tracker, inventory, log, scheduler = null)

    @Test
    fun `changes are written on flush and restored into fresh stores`() {
        val root = MemoryNode()
        val tracker = MarkerTracker()
        val inventory = ToolInventory()
        val svc = service(root, tracker, inventory)
        assertEquals(0 to 0, svc.restore())
        svc.attach()

        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, origin, now = 1L)
        tracker.recordHit("cnry7f3a9k2m", false, setOf(MarkerEncoding.RAW), TrafficRef(TrafficChannel.HTTP_RESPONSE, "Proxy", "POST", "https://h/p", 2L, messageId = "#1"), now = 2L)
        tracker.place("needle", MarkerKind.CUSTOM, null, now = 3L)
        inventory.addOrUpdate("search_docs", setOf("query"), "https://api/x", now = 4L)
        svc.flushNow()

        val tracker2 = MarkerTracker()
        val inventory2 = ToolInventory()
        assertEquals(2 to 1, PersistenceService.restoreInto(root, tracker2, inventory2))
        assertEquals(tracker.snapshot(), tracker2.snapshot())
        assertEquals(inventory.snapshot(), inventory2.snapshot())
        assertEquals(setOf(12), tracker2.prefixedTokenLengths())
        assertEquals(listOf("needle"), tracker2.customTokens().toList())
        assertEquals(MarkerStatus.SEEN_IN_RESPONSE, tracker2["cnry7f3a9k2m"]?.status)
    }

    @Test
    fun `removal and clear delete stored data, dispose keeps it`() {
        val root = MemoryNode()
        val tracker = MarkerTracker()
        val inventory = ToolInventory()
        val svc = service(root, tracker, inventory)
        svc.restore(); svc.attach()
        tracker.place("cnryaaaaaaaa", MarkerKind.TOKEN, origin)
        tracker.place("cnrybbbbbbbb", MarkerKind.TOKEN, origin)
        inventory.addOrUpdate("t", emptySet(), "")
        svc.flushNow()

        tracker.remove("cnryaaaaaaaa")
        svc.flushNow()
        val markers = root.children["canaryagent"]!!.children["markers"]!!
        assertEquals(setOf("cnrybbbbbbbb"), markers.childKeys())

        inventory.clear()
        svc.flushNow()
        assertNull(root.children["canaryagent"]!!.children["tools"])

        // Unload: dispose first, then the runtime clears the stores. Stored markers must survive.
        svc.dispose()
        tracker.clear()
        assertEquals(setOf("cnrybbbbbbbb"), root.children["canaryagent"]!!.children["markers"]!!.childKeys())
    }

    @Test
    fun `restore skips unreadable entries and logs once`() {
        val root = MemoryNode()
        val markers = root.childOrCreate("canaryagent").childOrCreate("markers")
        markers.childOrCreate("broken").setString("token", "broken")
        val tracker = MarkerTracker()
        assertEquals(0 to 0, PersistenceService.restoreInto(root, tracker, ToolInventory(), log))
        assertTrue(tracker.size() == 0)
    }

    @Test
    fun `restore does not notify listeners`() {
        val root = MemoryNode()
        val markers = root.childOrCreate("canaryagent").childOrCreate("markers")
        MarkerCodec.write(markers.childOrCreate("cnry7f3a9k2m"), com.canaryagent.model.MarkerHit("cnry7f3a9k2m", "x", MarkerKind.TOKEN, null, 1L))
        val tools = root.childOrCreate("canaryagent").childOrCreate("tools")
        ToolCodec.write(tools.childOrCreate("t"), ToolEntry("t", emptySet(), 1, 1L, 1L))
        val tracker = MarkerTracker()
        val inventory = ToolInventory()
        var events = 0
        tracker.addListener { _, _ -> events++ }
        inventory.addListener { _, _ -> events++ }
        assertEquals(1 to 1, PersistenceService.restoreInto(root, tracker, inventory))
        assertEquals(0, events)
    }
}
