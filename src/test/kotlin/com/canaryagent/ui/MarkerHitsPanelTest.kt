package com.canaryagent.ui

import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.MarkerEncoding
import com.canaryagent.model.MarkerKind
import com.canaryagent.model.MarkerStatus
import com.canaryagent.model.TrafficChannel
import com.canaryagent.model.TrafficRef
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkerHitsPanelTest {
    private val tracker = MarkerTracker()
    private val actions = FakeActions()
    private val panel = MarkerHitsPanel(tracker, actions)

    init {
        tracker.place("cnryaaaaaaaa", MarkerKind.TOKEN, null, now = 1)
        tracker.place("cnrybbbbbbbb", MarkerKind.TOKEN, null, now = 2)
        tracker.recordHit("cnrybbbbbbbb", true, setOf(MarkerEncoding.RAW), TrafficRef(TrafficChannel.HTTP_RESPONSE, "Proxy", "POST", "https://h/p", 3))
        panel.reload()
    }

    @Test
    fun `status renderer colours by status`() {
        val table = panel.panel.table
        val statusCol = Columns.markers.indexOfFirst { it.name == "Status" }
        val renderer = table.columnModel.getColumn(statusCol).cellRenderer
        // The renderer hands back one reused label, so read the colour right after each call.
        val placed = (renderer.getTableCellRendererComponent(table, "placed", false, false, 0, statusCol) as JLabel).foreground
        assertEquals(MarkerHitsPanel.StatusRenderer.PLACED, placed)
        val toolCall = (renderer.getTableCellRendererComponent(table, "seen in tool call", false, false, 1, statusCol) as JLabel).foreground
        assertEquals(MarkerHitsPanel.StatusRenderer.TOOL_CALL, toolCall)
        assertEquals(MarkerHitsPanel.StatusRenderer.OOB, MarkerHitsPanel.StatusRenderer.colorFor(MarkerStatus.OOB_INTERACTION))
        assertEquals(MarkerHitsPanel.StatusRenderer.SEEN, MarkerHitsPanel.StatusRenderer.colorFor(MarkerStatus.SEEN_IN_REQUEST))
    }

    @Test
    fun `placed at column shows clipboard and then the first message seen`() {
        val col = Columns.markers.indexOfFirst { it.name == "Placed at" }
        val table = panel.panel.table
        assertEquals("clipboard", table.getValueAt(0, col))
        assertEquals("POST h/p (clipboard, first seen in response)", table.getValueAt(1, col))
    }

    @Test
    fun `send last hit is disabled when no request copy exists`() {
        panel.panel.table.setRowSelectionInterval(1, 1)
        val menu = panel.panel.popupMenu!!
        menu.aboutToShow()
        assertFalse(menu.item("Send last hit request to Repeater").isEnabled)
        menu.item("Send last hit request to Repeater").click()
        assertTrue(actions.sent.isEmpty())
    }

    @Test
    fun `copy marker copies selected labels`() {
        panel.panel.table.setRowSelectionInterval(0, 1)
        panel.panel.popupMenu!!.item("Copy marker").click()
        assertEquals(listOf("cnryaaaaaaaa\ncnrybbbbbbbb"), actions.copied)
    }

    @Test
    fun `remove marker asks first and honours the answer`() {
        val menu = panel.panel.popupMenu!!
        panel.panel.table.setRowSelectionInterval(0, 0)
        actions.confirmAnswer = false
        menu.item("Remove marker").click()
        assertEquals(1, actions.confirmations.size)
        assertTrue(tracker.contains("cnryaaaaaaaa"))

        actions.confirmAnswer = true
        menu.item("Remove marker").click()
        assertFalse(tracker.contains("cnryaaaaaaaa"))
        panel.reload()
        assertEquals(1, panel.panel.table.rowCount)
    }

    @Test
    fun `remove all markers with no hits confirms with the count and spares anything seen`() {
        tracker.place("cnrycccccccc", MarkerKind.TOKEN, null, now = 3)
        // Seen once but still counted as a hit: must survive even though its status is the lowest "seen" rank.
        tracker.place("cnrydddddddd", MarkerKind.TOKEN, null, now = 4)
        tracker.recordHit("cnrydddddddd", false, setOf(MarkerEncoding.RAW), TrafficRef(TrafficChannel.HTTP_REQUEST, "Proxy", "POST", "https://h/q", 5))
        panel.reload()
        val menu = panel.panel.popupMenu!!
        panel.panel.table.setRowSelectionInterval(0, 0)
        menu.aboutToShow()
        assertTrue(menu.item("Remove all markers with no hits").isEnabled)

        actions.confirmAnswer = false
        menu.item("Remove all markers with no hits").click()
        assertEquals(listOf("Remove 2 marker(s) that have never been seen? This cannot be undone."), actions.confirmations)
        assertEquals(4, tracker.size())

        actions.confirmAnswer = true
        menu.item("Remove all markers with no hits").click()
        assertEquals(setOf("cnrybbbbbbbb", "cnrydddddddd"), tracker.snapshot().map { it.token }.toSet())
        panel.reload()
        assertEquals(2, panel.panel.table.rowCount)

        // Nothing left to remove: the item is disabled and a click asks nothing.
        menu.aboutToShow()
        assertFalse(menu.item("Remove all markers with no hits").isEnabled)
        menu.item("Remove all markers with no hits").click()
        assertEquals(2, actions.confirmations.size)
    }

    @Test
    fun `clear table asks with the count and keeps markers on cancel`() {
        actions.confirmAnswer = false
        panel.panel.clearTableButton.doClick(0)
        assertEquals(listOf("Remove 2 marker(s) and their hit history? This cannot be undone."), actions.confirmations)
        assertEquals(2, tracker.size())
        panel.reload()
        assertEquals(2, panel.panel.table.rowCount)
    }

    @Test
    fun `clear table empties the tracker on confirm`() {
        actions.confirmAnswer = true
        panel.panel.clearTableButton.doClick(0)
        assertEquals(1, actions.confirmations.size)
        panel.reload()
        assertEquals(0, panel.panel.table.rowCount)
        assertNull(tracker["cnrybbbbbbbb"])
        // Nothing left: the button is a no-op and asks nothing.
        panel.panel.clearTableButton.doClick(0)
        assertEquals(1, actions.confirmations.size)
    }
}
