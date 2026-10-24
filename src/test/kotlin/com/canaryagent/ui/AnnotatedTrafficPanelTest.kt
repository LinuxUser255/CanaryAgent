package com.canaryagent.ui

import com.canaryagent.annotation.AnnotatedTrafficStore
import com.canaryagent.model.AnnotatedTrafficItem
import com.canaryagent.model.AnnotationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotatedTrafficPanelTest {
    private val store = AnnotatedTrafficStore()
    private val viewer = FakeViewer()
    private val actions = FakeActions()
    private val panel = AnnotatedTrafficPanel(store, viewer, actions)

    private fun add(url: String, note: String, session: String, source: AnnotationSource = AnnotationSource.AUTO, messageKey: String = "") =
        store.add { id -> AnnotatedTrafficItem(id, id * 1000, "Proxy", "POST", url, note, source, session, messageKey = messageKey) }

    init {
        add("https://api/chat", "CanaryAgent: tools present: search_docs [tool keys]", "aaaa1111")
        add("https://api/chat", "CanaryAgent: tools present: fetch_url [tool keys]", "bbbb2222")
        add("https://api/other", "CanaryAgent: marker hit: cnry7f3a9k2m", "aaaa1111")
        panel.reload()
    }

    @Test
    fun `tool filter narrows to notes mentioning the tool and null restores`() {
        assertEquals(3, panel.table.table.rowCount)
        panel.setToolFilter("fetch_url")
        assertEquals(1, panel.table.table.rowCount)
        panel.setToolFilter(null)
        assertEquals(3, panel.table.table.rowCount)
    }

    @Test
    fun `selecting a row shows it in the viewer and clearing shows nothing`() {
        panel.table.table.setRowSelectionInterval(0, 0)
        assertEquals(1L, viewer.shown.last()?.id)
        panel.table.table.clearSelection()
        assertNull(viewer.shown.last())
    }

    @Test
    fun `session menu narrows and show all resets both filters`() {
        val menu = panel.table.popupMenu!!
        panel.table.table.setRowSelectionInterval(0, 0) // session aaaa1111
        menu.aboutToShow()
        assertTrue(menu.item("Show only this session").isEnabled)
        menu.item("Show only this session").click()
        assertEquals("aaaa1111", panel.table.filterText)
        assertEquals(2, panel.table.table.rowCount)

        panel.setToolFilter("search_docs")
        assertEquals(1, panel.table.table.rowCount)
        menu.item("Show all").click()
        assertEquals("", panel.table.filterText)
        assertEquals(3, panel.table.table.rowCount)
    }

    @Test
    fun `session item is disabled without a session hint`() {
        add("https://api/anon", "CanaryAgent: agent-like [agent path]", "")
        panel.reload()
        val row = (0 until panel.table.table.rowCount).first { panel.table.table.getValueAt(it, 3) == "https://api/anon" }
        panel.table.table.setRowSelectionInterval(row, row)
        val menu = panel.table.popupMenu!!
        menu.aboutToShow()
        assertFalse(menu.item("Show only this session").isEnabled)
    }

    @Test
    fun `copy url and repeater actions use the seam`() {
        val menu = panel.table.popupMenu!!
        panel.table.table.setRowSelectionInterval(0, 1)
        menu.item("Copy URL").click()
        assertEquals(listOf("https://api/chat\nhttps://api/chat"), actions.copied)
        // Items stored without a request copy cannot be sent; nothing reaches Repeater.
        menu.item("Send to Repeater").click()
        panel.table.table.doubleClickRow(0)
        assertTrue(actions.sent.isEmpty())
    }

    @Test
    fun `reload after store changes keeps the table in sync`() {
        store.clear()
        panel.reload()
        assertEquals(0, panel.table.table.rowCount)
    }

    @Test
    fun `one message with a marker hit and an agent note is one row with both notes`() {
        add("https://api/llm", "CanaryAgent: marker hit: cnry7f3a9k2m (tool call)", "cccc3333", AnnotationSource.MARKER_HIT, "#42")
        add("https://api/llm", "CanaryAgent: agent-like: web_search [tool keys]", "cccc3333", AnnotationSource.AUTO, "#42")
        add("https://api/llm", "CanaryAgent: agent-like: web_search [tool keys]", "cccc3333", AnnotationSource.AUTO, "#43")
        panel.reload()
        val table = panel.table.table
        assertEquals(5, table.rowCount)
        val noteCol = Columns.traffic.indexOfFirst { it.name == "Note" }
        val sourceCol = Columns.traffic.indexOfFirst { it.name == "Source" }
        val merged = (0 until table.rowCount).first { table.getValueAt(it, noteCol).toString().contains("(tool call)") }
        assertEquals(
            "CanaryAgent: marker hit: cnry7f3a9k2m (tool call) | CanaryAgent: agent-like: web_search [tool keys]",
            table.getValueAt(merged, noteCol),
        )
        assertEquals("marker hit", table.getValueAt(merged, sourceCol))
        // Both notes remain searchable and the tool narrowing still finds the merged row.
        panel.table.setFilterText("(tool call)")
        assertEquals(1, table.rowCount)
        panel.table.setFilterText("")
        panel.setToolFilter("web_search")
        assertEquals(2, table.rowCount)
    }

    @Test
    fun `clear table confirms with an in-memory-only message and honours the answer`() {
        actions.confirmAnswer = false
        panel.table.clearTableButton.doClick(0)
        val message = actions.confirmations.single()
        assertTrue(message.startsWith("Clear 3 annotated traffic row(s)?"), message)
        assertTrue(message.contains("in memory only"), message)
        assertFalse(message.contains("cannot be undone"), message)
        assertEquals(3, store.size())

        actions.confirmAnswer = true
        panel.table.clearTableButton.doClick(0)
        assertEquals(0, store.size())
        panel.reload()
        assertEquals(0, panel.table.table.rowCount)
    }
}
