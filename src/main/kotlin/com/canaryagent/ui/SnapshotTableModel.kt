package com.canaryagent.ui

import javax.swing.table.AbstractTableModel

/** One table column over row type [T]. [value] must be cheap; it runs on the EDT for every repaint. */
class Column<T>(
    val name: String,
    val preferredWidth: Int,
    val type: Class<*> = String::class.java,
    val value: (T) -> Any?,
)

/**
 * Read-only table model over an immutable snapshot list. Replace the whole list with [setRows];
 * never mutate rows in place. Pure JVM, usable headless.
 */
class SnapshotTableModel<T>(
    val columns: List<Column<T>>,
    /** Stable identity used to keep the selection across reloads. */
    val key: (T) -> Any,
) : AbstractTableModel() {

    private var rows: List<T> = emptyList()

    /** The current snapshot, as last set. */
    fun rows(): List<T> = rows

    /** The item at a model (not view) index. */
    fun rowAt(modelIndex: Int): T = rows[modelIndex]

    /** Model index of the row whose key equals [k], or -1. */
    fun indexOfKey(k: Any): Int = rows.indexOfFirst { key(it) == k }

    /** Replaces the snapshot and fires a full table change; EDT only. */
    fun setRows(newRows: List<T>) {
        rows = newRows
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column].name
    override fun getColumnClass(columnIndex: Int): Class<*> = columns[columnIndex].type
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? = columns[columnIndex].value(rows[rowIndex])
}
