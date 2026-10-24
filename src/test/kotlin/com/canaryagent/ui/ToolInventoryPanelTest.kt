package com.canaryagent.ui

import com.canaryagent.inventory.ToolInventory
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolInventoryPanelTest {
    private val inventory = ToolInventory()
    private val actions = FakeActions()
    private val selected = ArrayList<String?>()
    private val panel = ToolInventoryPanel(inventory, actions) { selected += it }

    init {
        inventory.addOrUpdate("search_docs", setOf("query"), "https://a", now = 1)
        inventory.addOrUpdate("fetch_url", setOf("url"), "https://b", now = 2)
        panel.reload()
    }

    @Test
    fun `selecting a tool reports its name and clearing reports null`() {
        assertEquals(2, panel.panel.table.rowCount)
        panel.panel.table.setRowSelectionInterval(1, 1)
        panel.panel.table.clearSelection()
        panel.panel.table.setRowSelectionInterval(0, 1) // two rows selected: no single tool
        assertEquals(listOf("fetch_url", null, null), selected)
    }

    @Test
    fun `clear table asks with the count and clears only on confirm`() {
        actions.confirmAnswer = false
        panel.panel.clearTableButton.doClick(0)
        assertEquals(listOf("Remove 2 tool(s) from the inventory and the saved history? This cannot be undone."), actions.confirmations)
        assertEquals(2, inventory.size())

        actions.confirmAnswer = true
        panel.panel.clearTableButton.doClick(0)
        assertEquals(0, inventory.size())
        panel.reload()
        assertEquals(0, panel.panel.table.rowCount)
    }
}
