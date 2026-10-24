package com.canaryagent.annotation

import com.canaryagent.model.AnnotatedTrafficItem
import com.canaryagent.model.AnnotationSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, thread-safe list of annotated traffic. Oldest items are evicted past [capacity].
 * An item whose [AnnotatedTrafficItem.messageKey] matches a stored row is merged into that row (notes
 * joined like the Proxy note), so one message with both a marker hit and an agent-like note is one row.
 */
class AnnotatedTrafficStore(private val capacity: Int = DEFAULT_CAPACITY) {
    /** Notified after every add, merge or clear, on the caller's thread; UI listeners hop to the EDT themselves. */
    fun interface Listener {
        fun onTrafficChanged()
    }

    companion object {
        const val DEFAULT_CAPACITY = 5000
    }

    private val lock = Any()
    private val items = ArrayDeque<AnnotatedTrafficItem>()
    private val ids = AtomicLong()
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Adds the item built by [build] with a fresh id, or merges it into the row for the same message. */
    fun add(build: (id: Long) -> AnnotatedTrafficItem): AnnotatedTrafficItem {
        val item = build(ids.incrementAndGet())
        val stored = synchronized(lock) {
            val index = if (item.messageKey.isEmpty()) -1 else items.indexOfLast { it.messageKey == item.messageKey }
            if (index >= 0) {
                merge(items[index], item).also { items[index] = it }
            } else {
                items.addLast(item)
                while (items.size > capacity) items.removeFirst()
                item
            }
        }
        for (l in listeners) try { l.onTrafficChanged() } catch (_: RuntimeException) { }
        return stored
    }

    /** The existing row with [incoming]'s note appended; a marker hit wins the Source column. */
    private fun merge(existing: AnnotatedTrafficItem, incoming: AnnotatedTrafficItem): AnnotatedTrafficItem =
        existing.copy(
            note = TrafficAnnotator.joinNotes(existing.note, incoming.note),
            source = if (incoming.source == AnnotationSource.MARKER_HIT) incoming.source else existing.source,
            request = existing.request ?: incoming.request,
            response = existing.response ?: incoming.response,
            sessionHint = existing.sessionHint.ifEmpty { incoming.sessionHint },
        )

    /** Current row count, at most the capacity. */
    fun size(): Int = synchronized(lock) { items.size }

    /** Immutable snapshot, oldest first. */
    fun snapshot(): List<AnnotatedTrafficItem> = synchronized(lock) { items.toList() }

    /** Drops every row and notifies listeners once. */
    fun clear() {
        synchronized(lock) { items.clear() }
        for (l in listeners) try { l.onTrafficChanged() } catch (_: RuntimeException) { }
    }

    /** Registers [l]; listeners are detached again on unload. */
    fun addListener(l: Listener) { listeners += l }

    /** Detaches [l]; a no-op when it was never added. */
    fun removeListener(l: Listener) { listeners -= l }
}
