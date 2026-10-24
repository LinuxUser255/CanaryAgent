package com.canaryagent.ui

import com.canaryagent.annotation.AnnotatedTrafficStore
import com.canaryagent.model.AnnotatedTrafficItem
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JSplitPane
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Annotated Traffic section: the table plus a read-only request/response viewer for the selected row.
 * Double-click or the context menu sends the original request to Repeater. No editing here.
 */
class AnnotatedTrafficPanel(
    private val store: AnnotatedTrafficStore,
    private val viewer: MessageViewer,
    private val actions: UiActions,
) {
    val table = FilterableTablePanel(
        title = "Annotated Traffic",
        model = SnapshotTableModel(Columns.traffic) { i: AnnotatedTrafficItem -> i.id },
        onClear = ::clearAll,
    )

    /** Table above the viewer. */
    val component: JComponent = JSplitPane(JSplitPane.VERTICAL_SPLIT, table, viewer.component).apply {
        resizeWeight = 0.45
        isContinuousLayout = true
    }

    init {
        table.addSelectionListener { selected -> viewer.show(selected.singleOrNull()) }
        table.setOnDoubleClick { sendToRepeater(listOf(it)) }

        val menu = JPopupMenu()
        menu.add(JMenuItem("Send to Repeater").apply { addActionListener { sendToRepeater(table.selectedItems()) } })
        menu.add(JMenuItem("Copy URL").apply {
            addActionListener { actions.copyToClipboard(table.selectedItems().joinToString("\n") { it.url }) }
        })
        menu.addSeparator()
        val sameSession = JMenuItem("Show only this session")
        sameSession.addActionListener {
            table.selectedItems().firstOrNull()?.sessionHint?.takeIf { it.isNotEmpty() }?.let { table.setFilterText(it) }
        }
        menu.add(sameSession)
        menu.add(JMenuItem("Show all").apply {
            addActionListener {
                table.setFilterText("")
                setToolFilter(null)
            }
        })
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                sameSession.isEnabled = table.selectedItems().firstOrNull()?.sessionHint?.isNotEmpty() == true
            }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}
            override fun popupMenuCanceled(e: PopupMenuEvent) {}
        })
        table.setPopupMenu(menu)
    }

    /** EDT only. */
    fun reload() = table.reload(store.snapshot())

    /** "Clear table": the list lives in memory only, so the dialog says what stays (Proxy history annotations). */
    private fun clearAll() {
        val n = store.size()
        if (n == 0) return
        val ok = actions.confirm(
            "Clear $n annotated traffic row(s)? This list is kept in memory only (nothing persisted is removed); " +
                "Proxy history notes and highlights stay.",
        )
        if (ok) store.clear()
    }

    /** Narrows the table to rows whose note mentions [toolName]; null removes the narrowing. */
    fun setToolFilter(toolName: String?) {
        table.setExternalFilter(toolName?.let { name -> { item: AnnotatedTrafficItem -> item.note.contains(name, ignoreCase = true) } })
    }

    private fun sendToRepeater(items: List<AnnotatedTrafficItem>) {
        for (item in items.take(MAX_SEND)) {
            val req = item.request ?: continue
            actions.sendToRepeater(req, "CanaryAgent")
        }
    }

    private companion object {
        const val MAX_SEND = 20
    }
}
