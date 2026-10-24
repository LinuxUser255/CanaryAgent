package com.canaryagent.persistence

import com.canaryagent.model.ToolEntry

/** Writes a [ToolEntry] into one node and reads it back. */
object ToolCodec {
    /** Stores every field of [entry]; parameter keys are joined with newlines. */
    fun write(node: KeyValueNode, entry: ToolEntry) {
        node.setString("name", entry.name)
        node.setString("keys", entry.parameterKeys.joinToString("\n"))
        node.setLong("messageCount", entry.messageCount.toLong())
        node.setLong("firstSeen", entry.firstSeen)
        node.setLong("lastSeen", entry.lastSeen)
        node.setString("source", entry.source)
    }

    /** Rebuilds an entry; null when the name or first-seen time is missing, so the row is skipped. */
    fun read(node: KeyValueNode): ToolEntry? {
        val name = node.getString("name")?.takeIf { it.isNotEmpty() } ?: return null
        val firstSeen = node.getLong("firstSeen") ?: return null
        return ToolEntry(
            name = name,
            parameterKeys = node.getString("keys").orEmpty().split('\n').filter { it.isNotEmpty() }.toSortedSet(),
            messageCount = (node.getLong("messageCount") ?: 0L).toInt(),
            firstSeen = firstSeen,
            lastSeen = node.getLong("lastSeen") ?: firstSeen,
            source = node.getString("source") ?: "",
        )
    }
}
