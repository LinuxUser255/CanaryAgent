package com.canaryagent.ui

import com.canaryagent.inventory.ToolInventory
import com.canaryagent.model.ToolEntry

/** Tool Inventory section. Selecting a row narrows the Annotated Traffic table to that tool. */
class ToolInventoryPanel(
    private val inventory: ToolInventory,
    private val actions: UiActions,
    onToolSelected: (String?) -> Unit,
) {
    val panel = FilterableTablePanel(
        title = "Tool Inventory",
        model = SnapshotTableModel(Columns.tools) { e: ToolEntry -> e.name.lowercase() },
        onClear = ::clearAll,
    )

    init {
        panel.addSelectionListener { selected -> onToolSelected(selected.singleOrNull()?.name) }
    }

    /** EDT only. */
    fun reload() = panel.reload(inventory.snapshot())

    /** "Clear table": drops every tool and its persisted copy, after confirmation. */
    private fun clearAll() {
        val n = inventory.size()
        if (n == 0) return
        if (actions.confirm("Remove $n tool(s) from the inventory and the saved history? This cannot be undone.")) inventory.clear()
    }
}
