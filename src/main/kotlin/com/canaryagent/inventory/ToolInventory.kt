package com.canaryagent.inventory

import com.canaryagent.model.ToolEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Thread-safe tool store keyed by lowercased name. Entries are immutable and replaced atomically. */
class ToolInventory {
    /** Notified after each change, on the caller's thread; UI listeners hop to the EDT themselves. */
    fun interface Listener {
        /** The new state of one tool; [isNew] is true the first time a name is seen. */
        fun onToolChanged(entry: ToolEntry, isNew: Boolean)

        /** The whole inventory was emptied. */
        fun onInventoryCleared() {}
    }

    private val tools = ConcurrentHashMap<String, ToolEntry>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Call once per message per tool. Parameter keys are kept sorted for a stable display. */
    fun addOrUpdate(name: String, keys: Set<String>, source: String, now: Long = System.currentTimeMillis()): ToolEntry {
        var created = false
        val entry = tools.compute(name.lowercase()) { _, e ->
            if (e == null) {
                created = true
                ToolEntry(name, keys.toSortedSet(), 1, now, now, source)
            } else {
                e.copy(
                    parameterKeys = (e.parameterKeys + keys).toSortedSet(),
                    messageCount = e.messageCount + 1,
                    lastSeen = now,
                )
            }
        }!!
        for (l in listeners) try { l.onToolChanged(entry, created) } catch (_: RuntimeException) { }
        return entry
    }

    /** Records every tool of one message; a message counts once per tool even if it appears twice. */
    fun record(toolsInMessage: Collection<ObservedTool>, source: String, now: Long = System.currentTimeMillis()) {
        for (t in toolsInMessage.distinctBy { it.name.lowercase() }) addOrUpdate(t.name, t.parameterKeys, source, now)
    }

    /** Puts a stored entry back without notifying listeners. */
    fun restore(entry: ToolEntry) {
        tools[entry.name.lowercase()] = entry
    }

    /** Entry by tool name, case-insensitive. */
    operator fun get(name: String): ToolEntry? = tools[name.lowercase()]

    /** Number of distinct tool names seen. */
    fun size(): Int = tools.size

    /** Immutable snapshot, safe to hand to the EDT. Ordered by first seen. */
    fun snapshot(): List<ToolEntry> = tools.values.sortedBy { it.firstSeen }

    /** Forgets every tool and notifies listeners once; the persisted copies are removed by the persistence service. */
    fun clear() {
        tools.clear()
        for (l in listeners) try { l.onInventoryCleared() } catch (_: RuntimeException) { }
    }

    /** Registers [l]; listeners are detached again on unload. */
    fun addListener(l: Listener) { listeners += l }

    /** Detaches [l]; a no-op when it was never added. */
    fun removeListener(l: Listener) { listeners -= l }
}
