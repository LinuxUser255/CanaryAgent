package com.canaryagent.persistence

import burp.api.montoya.logging.Logging
import com.canaryagent.inventory.ToolInventory
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.MarkerKind
import com.canaryagent.util.CanaryLog
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistenceOptOutTest {
    private object QuietLogging : Logging {
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

    @Test
    fun `opt-out is read live at each flush, and queued changes land once it is lifted`() {
        var optedOut = false
        val root = MemoryNode()
        val tracker = MarkerTracker()
        val inventory = ToolInventory()
        val svc = PersistenceService(root, tracker, inventory, CanaryLog(QuietLogging), scheduler = null, writesEnabled = { !optedOut })
        svc.restore(); svc.attach()

        optedOut = true
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, null)
        svc.flushNow()
        assertFalse(svc.isWriting())
        assertNull(root.children["canaryagent"]?.children?.get("markers"))

        optedOut = false
        svc.flushNow()
        assertTrue(svc.isWriting())
        assertEquals(setOf("cnry7f3a9k2m"), root.children["canaryagent"]!!.children["markers"]!!.childKeys())
    }
}
