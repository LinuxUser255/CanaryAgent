package com.canaryagent.ui

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.regex.Pattern
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.TableRowSorter

/**
 * A titled, filterable, read-only table over a [SnapshotTableModel]: header row with title, filter
 * field (with an inline x button and Esc to clear it) and "Clear table" button; sortable columns;
 * selection kept across reloads by row key; optional external predicate (used to narrow traffic to a
 * selected tool). All methods are EDT-only.
 *
 * [onClear] runs when the button is pressed; the owning section decides whether to confirm first.
 */
class FilterableTablePanel<T>(
    title: String,
    val model: SnapshotTableModel<T>,
    private val onClear: () -> Unit,
) : JPanel(BorderLayout()) {

    val table = JTable(model)
    private val sorter = TableRowSorter(model)
    /** The filter text field (exposed for tests). */
    internal val filterField = JTextField(18)

    /** The x button inside the filter field; shown only while the field has text (exposed for tests). */
    internal val clearFilterButton = JButton("\u00d7").apply {
        toolTipText = "Clear filter (Esc)"
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        isRolloverEnabled = true
        margin = Insets(0, 2, 0, 2)
        isVisible = false
        addActionListener { clearFilter() }
    }

    /** The "Clear table" button (exposed for tests). */
    internal val clearTableButton = JButton("Clear table").apply { addActionListener { onClear() } }

    /**
     * Section title with the row count: `Title (49)`, or `Title (12 of 49)` while a filter narrows the
     * view, so the number the Clear dialogs quote can be checked without scrolling (exposed for tests).
     * Plain text with a derived font: Burp's look and feel disables HTML rendering in labels.
     */
    internal val titleLabel = JLabel(title).apply { font = font.deriveFont(Font.BOLD, font.size2D + 1f) }
    private val baseTitle = title
    private var externalFilter: ((T) -> Boolean)? = null
    private var popup: JPopupMenu? = null
    private var onDoubleClick: ((T) -> Unit)? = null

    init {
        table.rowSorter = sorter
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.autoCreateColumnsFromModel = true
        table.fillsViewportHeight = true
        for ((i, c) in model.columns.withIndex()) table.columnModel.getColumn(i).preferredWidth = c.preferredWidth

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        header.add(titleLabel)
        header.add(JLabel("Filter:"))
        header.add(filterField)
        header.add(clearTableButton)
        add(header, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        preferredSize = Dimension(600, 160)

        // The x sits inside the field at its right edge; the margin keeps typed text clear of it.
        filterField.layout = BorderLayout()
        filterField.add(clearFilterButton, BorderLayout.EAST)
        val m = filterField.margin ?: Insets(0, 0, 0, 0)
        filterField.margin = Insets(m.top, m.left, m.bottom, m.right + clearFilterButton.preferredSize.width)
        filterField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CLEAR_FILTER_ACTION)
        filterField.actionMap.put(CLEAR_FILTER_ACTION, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = clearFilter()
        })

        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterChanged()
            override fun removeUpdate(e: DocumentEvent) = filterChanged()
            override fun changedUpdate(e: DocumentEvent) = filterChanged()
        })

        model.addTableModelListener { updateTitle() }
        sorter.addRowSorterListener { updateTitle() }
        updateTitle()

        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybePopup(e)
            override fun mouseReleased(e: MouseEvent) = maybePopup(e)
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    selectedItems().firstOrNull()?.let { onDoubleClick?.invoke(it) }
                }
            }
        })
    }

    /** Replaces the rows and restores the previous selection where the keys still exist. */
    fun reload(rows: List<T>) {
        val selectedKeys = selectedItems().map(model.key).toSet()
        model.setRows(rows)
        if (selectedKeys.isEmpty()) return
        table.clearSelection()
        for ((i, row) in rows.withIndex()) {
            if (model.key(row) in selectedKeys) {
                val view = table.convertRowIndexToView(i)
                if (view >= 0) table.addRowSelectionInterval(view, view)
            }
        }
    }

    /** The selected rows as model items, in view order. */
    fun selectedItems(): List<T> =
        table.selectedRows.toList().mapNotNull { view ->
            val m = table.convertRowIndexToModel(view)
            if (m in 0 until model.rowCount) model.rowAt(m) else null
        }

    /** Sets the filter field text; the row filter follows through the document listener. */
    fun setFilterText(text: String) {
        filterField.text = text
    }

    /** Empties the filter field (x button, Esc). */
    fun clearFilter() {
        if (filterField.text.isNotEmpty()) filterField.text = ""
    }

    /** A second filter combined with the text filter (the tool-row narrowing); null removes it. */
    fun setExternalFilter(predicate: ((T) -> Boolean)?) {
        externalFilter = predicate
        applyFilters()
    }

    /** The context menu shown on right-click over a row. */
    fun setPopupMenu(menu: JPopupMenu) {
        popup = menu
    }

    /** The context menu, if one was set (exposed for tests and for enabling items). */
    val popupMenu: JPopupMenu? get() = popup

    /** Current filter text. */
    val filterText: String get() = filterField.text

    /** Action for a double-click on a row (send to Repeater in the suite tab). */
    fun setOnDoubleClick(action: (T) -> Unit) {
        onDoubleClick = action
    }

    /** Called with the selected items whenever the selection settles. */
    fun addSelectionListener(listener: (List<T>) -> Unit) {
        table.selectionModel.addListSelectionListener { e -> if (!e.valueIsAdjusting) listener(selectedItems()) }
    }

    private fun filterChanged() {
        clearFilterButton.isVisible = filterField.text.isNotEmpty()
        applyFilters()
    }

    private fun updateTitle() {
        val total = model.rowCount
        // On a model change this runs before the table has updated the sorter, and an unfiltered, unsorted
        // sorter fires no event afterwards; without a row filter the view holds every row anyway.
        val shown = if (sorter.rowFilter == null) total else sorter.viewRowCount
        titleLabel.text = if (shown == total) "$baseTitle ($total)" else "$baseTitle ($shown of $total)"
    }

    private fun applyFilters() {
        val filters = ArrayList<RowFilter<SnapshotTableModel<T>, Int>>(2)
        val text = filterField.text.trim()
        if (text.isNotEmpty()) filters += RowFilter.regexFilter("(?i)" + Pattern.quote(text))
        externalFilter?.let { pred ->
            filters += object : RowFilter<SnapshotTableModel<T>, Int>() {
                override fun include(entry: Entry<out SnapshotTableModel<T>, out Int>): Boolean =
                    pred(entry.model.rowAt(entry.identifier))
            }
        }
        sorter.rowFilter = when (filters.size) {
            0 -> null
            1 -> filters[0]
            else -> RowFilter.andFilter(filters)
        }
    }

    private fun maybePopup(e: MouseEvent) {
        val menu = popup ?: return
        if (!e.isPopupTrigger) return
        val row = table.rowAtPoint(e.point)
        if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row)
        if (table.selectedRowCount > 0) menu.show(e.component, e.x, e.y)
    }

    private companion object {
        const val CLEAR_FILTER_ACTION = "canaryagent.clearFilter"
    }
}
