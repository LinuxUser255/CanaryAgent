package com.canaryagent.ui

import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.RowSorter
import javax.swing.SortOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FilterableTablePanelTest {
    private data class Row(val id: Int, val name: String, val note: String)

    private val model = SnapshotTableModel(
        listOf(
            Column<Row>("Id", 40, Integer::class.java) { it.id },
            Column<Row>("Name", 100) { it.name },
            Column<Row>("Note", 100) { it.note },
        ),
    ) { it.id }

    private var cleared = 0
    private val panel = FilterableTablePanel("Rows", model) { cleared++ }
    private val rows = listOf(Row(1, "alpha", "tools present: search"), Row(2, "beta", "marker hit"), Row(3, "gamma", "tools present: fetch"))

    @Test
    fun `title carries the row count, and the shown count while filtered`() {
        assertEquals("Rows (0)", panel.titleLabel.text)
        panel.reload(rows)
        assertEquals("Rows (3)", panel.titleLabel.text)
        panel.setFilterText("tools")
        assertEquals("Rows (2 of 3)", panel.titleLabel.text)
        panel.setExternalFilter { it.name == "alpha" }
        assertEquals("Rows (1 of 3)", panel.titleLabel.text)
        panel.setExternalFilter(null)
        panel.clearFilter()
        assertEquals("Rows (3)", panel.titleLabel.text)
        panel.reload(emptyList())
        assertEquals("Rows (0)", panel.titleLabel.text)
    }

    @Test
    fun `filter text narrows across all columns case-insensitively`() {
        panel.reload(rows)
        assertEquals(3, panel.table.rowCount)
        panel.setFilterText("TOOLS")
        assertEquals(2, panel.table.rowCount)
        panel.setFilterText("beta")
        assertEquals(1, panel.table.rowCount)
        panel.setFilterText("")
        assertEquals(3, panel.table.rowCount)
    }

    @Test
    fun `external predicate combines with the text filter`() {
        panel.reload(rows)
        panel.setExternalFilter { it.note.contains("tools") }
        assertEquals(2, panel.table.rowCount)
        panel.setFilterText("fetch")
        assertEquals(1, panel.table.rowCount)
        panel.setExternalFilter(null)
        assertEquals(1, panel.table.rowCount)
        panel.setFilterText("")
        assertEquals(3, panel.table.rowCount)
    }

    @Test
    fun `selection survives a reload that reorders rows`() {
        panel.reload(rows)
        panel.table.setRowSelectionInterval(1, 1) // beta
        assertEquals(listOf(2), panel.selectedItems().map { it.id })
        panel.reload(rows.reversed() + Row(4, "delta", ""))
        assertEquals(listOf(2), panel.selectedItems().map { it.id })
    }

    @Test
    fun `sorting by a column reorders the view`() {
        panel.reload(rows)
        panel.table.rowSorter.sortKeys = listOf(RowSorter.SortKey(1, SortOrder.DESCENDING))
        val names = (0 until panel.table.rowCount).map { panel.table.getValueAt(it, 1) }
        assertEquals(listOf("gamma", "beta", "alpha"), names)
    }

    @Test
    fun `x inside the filter field clears it and shows only while there is text`() {
        panel.reload(rows)
        assertFalse(panel.clearFilterButton.isVisible)
        assertTrue(panel.filterField.components.contains(panel.clearFilterButton))
        panel.setFilterText("beta")
        assertTrue(panel.clearFilterButton.isVisible)
        assertEquals(1, panel.table.rowCount)
        panel.clearFilterButton.doClick(0)
        assertEquals("", panel.filterText)
        assertEquals(3, panel.table.rowCount)
        assertFalse(panel.clearFilterButton.isVisible)
    }

    @Test
    fun `escape in the filter field clears it`() {
        panel.reload(rows)
        panel.setFilterText("gamma")
        assertEquals(1, panel.table.rowCount)
        val key = panel.filterField.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))
        val action = assertNotNull(panel.filterField.actionMap.get(key), "Esc is bound while the field has focus")
        action.actionPerformed(ActionEvent(panel.filterField, ActionEvent.ACTION_PERFORMED, ""))
        assertEquals("", panel.filterText)
        assertEquals(3, panel.table.rowCount)
    }

    @Test
    fun `typed keystrokes are debounced through the coalescer, not applied per character`() {
        panel.reload(rows)
        // Simulate typing: a document insert, as a keystroke produces, not setFilterText.
        panel.filterField.document.insertString(0, "beta", null)
        // The x button follows the text immediately; the pattern compile waits for the pause.
        assertTrue(panel.clearFilterButton.isVisible)
        assertEquals(3, panel.table.rowCount, "filter must not apply synchronously on a keystroke")
        javax.swing.SwingUtilities.invokeAndWait { panel.filterCoalescer.refreshNow() }
        assertEquals(1, panel.table.rowCount, "filter applies once the coalescer fires")
    }

    @Test
    fun `dispose stops the pending debounce`() {
        panel.reload(rows)
        panel.filterField.document.insertString(0, "beta", null)
        panel.dispose()
        assertEquals(3, panel.table.rowCount)
    }

    @Test
    fun `clear table button calls back`() {
        val button = panel.components.flatMap { (it as? javax.swing.JPanel)?.components?.toList() ?: emptyList() }
            .filterIsInstance<javax.swing.JButton>().first { it.text == "Clear table" }
        button.doClick(0)
        assertEquals(1, cleared)
    }
}
