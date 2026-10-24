package com.canaryagent.inventory

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolInventoryTest {
    @Test
    fun `entries merge keys, count messages once per tool, keep first casing`() {
        val inv = ToolInventory()
        inv.addOrUpdate("Search_Docs", setOf("query"), "https://a/x", now = 1)
        inv.addOrUpdate("search_docs", setOf("limit"), "https://b/y", now = 2)
        inv.record(listOf(ObservedTool("SEARCH_DOCS", setOf("page")), ObservedTool("search_docs", setOf("q2"))), "https://c/z", now = 3)

        val e = inv["search_docs"]!!
        assertEquals("Search_Docs", e.name)
        assertEquals(setOf("query", "limit", "page"), e.parameterKeys)
        assertEquals(3, e.messageCount)
        assertEquals(1L, e.firstSeen)
        assertEquals(3L, e.lastSeen)
        assertEquals("https://a/x", e.source)
        assertEquals(1, inv.snapshot().size)
    }

    @Test
    fun `listeners learn whether a tool is new`() {
        val inv = ToolInventory()
        val events = ArrayList<Pair<String, Boolean>>()
        inv.addListener { entry, isNew -> events += entry.name to isNew }
        inv.addOrUpdate("a", emptySet(), "")
        inv.addOrUpdate("A", setOf("k"), "")
        inv.addOrUpdate("b", emptySet(), "")
        assertEquals(listOf("a" to true, "a" to false, "b" to true), events)
        inv.clear()
        assertEquals(0, inv.size())
    }
}
