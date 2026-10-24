package com.canaryagent.persistence

import burp.api.montoya.logging.Logging
import com.canaryagent.inventory.ToolInventory
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.MarkerKind
import com.canaryagent.util.CanaryLog
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollaboratorKeyPersistenceTest {
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

    private fun service(root: KeyValueNode, writesEnabled: () -> Boolean = { true }) =
        PersistenceService(root, MarkerTracker(), ToolInventory(), CanaryLog(QuietLogging), scheduler = null, writesEnabled = writesEnabled)

    @Test
    fun `key is written at the next flush and read back by a fresh service on the same tree`() {
        val root = MemoryNode()
        val svc = service(root)
        assertNull(svc.loadCollaboratorKey())
        svc.saveCollaboratorKey("secret-1")
        assertNull(svc.loadCollaboratorKey(), "nothing written before a flush")
        svc.flushNow()
        assertEquals("secret-1", svc.loadCollaboratorKey())
        assertEquals("secret-1", service(root).loadCollaboratorKey(), "a reload sees the key")
    }

    @Test
    fun `opt-out keeps the key queued until writes are allowed again`() {
        var optedOut = true
        val root = MemoryNode()
        val svc = service(root) { !optedOut }
        svc.saveCollaboratorKey("secret-2")
        svc.flushNow()
        assertNull(svc.loadCollaboratorKey())
        optedOut = false
        svc.flushNow()
        assertEquals("secret-2", svc.loadCollaboratorKey())
    }

    @Test
    fun `clearing markers and tools leaves the key in place`() {
        val root = MemoryNode()
        val tracker = MarkerTracker()
        val inventory = ToolInventory()
        val svc = PersistenceService(root, tracker, inventory, CanaryLog(QuietLogging), scheduler = null)
        svc.attach()
        svc.saveCollaboratorKey("secret-3")
        tracker.place("cnry7f3a9k2m", MarkerKind.TOKEN, null)
        svc.flushNow()
        tracker.clear()
        inventory.clear()
        svc.flushNow()
        assertEquals(0 to 0, PersistenceService.restoreInto(root, MarkerTracker(), ToolInventory()))
        assertEquals("secret-3", svc.loadCollaboratorKey())
    }
}
