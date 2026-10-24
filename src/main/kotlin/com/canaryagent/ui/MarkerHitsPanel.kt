package com.canaryagent.ui

import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.MarkerHit
import com.canaryagent.model.MarkerStatus
import java.awt.Color
import java.awt.Component
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.table.DefaultTableCellRenderer

/** Marker Hits section: every placed marker, its status, encodings and last hit. */
class MarkerHitsPanel(private val tracker: MarkerTracker, private val actions: UiActions) {

    val panel = FilterableTablePanel(
        title = "Marker Hits",
        model = SnapshotTableModel(Columns.markers) { h: MarkerHit -> h.token },
        onClear = ::clearAll,
    )

    init {
        val statusColumn = Columns.markers.indexOfFirst { it.name == "Status" }
        panel.table.columnModel.getColumn(statusColumn).cellRenderer = StatusRenderer()

        val menu = JPopupMenu()
        val sendLast = JMenuItem("Send last hit request to Repeater")
        sendLast.addActionListener {
            for (h in panel.selectedItems()) {
                val req = h.lastHit?.request ?: continue
                actions.sendToRepeater(req, "hit: ${h.label}")
            }
        }
        menu.add(sendLast)
        menu.add(JMenuItem("Copy marker").apply {
            addActionListener { actions.copyToClipboard(panel.selectedItems().joinToString("\n") { it.label }) }
        })
        menu.add(JMenuItem("Remove marker").apply {
            addActionListener {
                val selected = panel.selectedItems()
                if (selected.isEmpty()) return@addActionListener
                val ok = actions.confirm("Stop tracking ${selected.size} marker(s)? Future occurrences will no longer be detected.")
                if (ok) for (h in selected) tracker.remove(h.token)
            }
        })
        menu.addSeparator()
        val removeUnseen = JMenuItem("Remove all markers with no hits")
        removeUnseen.addActionListener { removeMarkersWithNoHits() }
        menu.add(removeUnseen)
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                sendLast.isEnabled = panel.selectedItems().any { it.lastHit?.request != null }
                removeUnseen.isEnabled = markersWithNoHits().isNotEmpty()
            }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}
            override fun popupMenuCanceled(e: PopupMenuEvent) {}
        })
        panel.setPopupMenu(menu)
    }

    /** EDT only. */
    fun reload() = panel.reload(tracker.snapshot())

    /** Markers still at `placed` that nothing has ever matched: the only ones the bulk removal touches. */
    private fun markersWithNoHits(): List<MarkerHit> =
        tracker.snapshot().filter { it.status == MarkerStatus.PLACED && it.hitCount == 0 }

    /** Bulk cleanup for dead markers; whatever has a hit or a higher status is left alone. */
    private fun removeMarkersWithNoHits() {
        val dead = markersWithNoHits()
        if (dead.isEmpty()) return
        val ok = actions.confirm("Remove ${dead.size} marker(s) that have never been seen? This cannot be undone.")
        if (ok) for (h in dead) tracker.remove(h.token)
    }

    /** "Clear table": drops every marker and its persisted hit history, after confirmation. */
    private fun clearAll() {
        val n = tracker.size()
        if (n == 0) return
        if (actions.confirm("Remove $n marker(s) and their hit history? This cannot be undone.")) tracker.clear()
    }

    /** Colours the status cell; mid-tone colours stay readable on both Burp themes. */
    class StatusRenderer : DefaultTableCellRenderer() {
        companion object {
            val OOB: Color = Color(200, 100, 220)
            val TOOL_CALL: Color = Color(220, 80, 80)
            val TOOL_RESULT: Color = Color(230, 150, 50)
            val SEEN: Color = Color(90, 150, 240)
            val PLACED: Color = Color(150, 150, 150)

            fun colorFor(status: MarkerStatus?): Color = when (status) {
                MarkerStatus.OOB_INTERACTION -> OOB
                MarkerStatus.SEEN_IN_TOOL_CALL -> TOOL_CALL
                MarkerStatus.SEEN_IN_TOOL_RESULT -> TOOL_RESULT
                MarkerStatus.SEEN_IN_RESPONSE, MarkerStatus.SEEN_IN_REQUEST -> SEEN
                MarkerStatus.PLACED, null -> PLACED
            }
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected) c.foreground = colorFor(MarkerStatus.entries.firstOrNull { it.label == value })
            return c
        }
    }
}
