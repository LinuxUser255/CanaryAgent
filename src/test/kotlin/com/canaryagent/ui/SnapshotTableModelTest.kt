package com.canaryagent.ui

import javax.swing.event.TableModelEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SnapshotTableModelTest {
    private data class Row(val id: Int, val name: String)

    private val model = SnapshotTableModel(
        listOf(
            Column<Row>("Id", 40, Integer::class.java) { it.id },
            Column<Row>("Name", 100) { it.name },
        ),
    ) { it.id }

    @Test
    fun `columns and values`() {
        model.setRows(listOf(Row(1, "a"), Row(2, "b")))
        assertEquals(2, model.columnCount)
        assertEquals(listOf("Id", "Name"), (0 until model.columnCount).map { model.getColumnName(it) })
        assertEquals(Integer::class.java, model.getColumnClass(0))
        assertEquals(2, model.rowCount)
        assertEquals("b", model.getValueAt(1, 1))
        assertEquals(Row(2, "b"), model.rowAt(1))
        assertFalse(model.isCellEditable(0, 0))
    }

    @Test
    fun `setRows replaces everything and fires one change event`() {
        val events = ArrayList<TableModelEvent>()
        model.addTableModelListener { events += it }
        model.setRows(listOf(Row(1, "a")))
        model.setRows(listOf(Row(3, "c")))
        assertEquals(2, events.size)
        assertEquals(1, model.rowCount)
        assertEquals(0, model.indexOfKey(3))
        assertEquals(-1, model.indexOfKey(1))
    }
}
