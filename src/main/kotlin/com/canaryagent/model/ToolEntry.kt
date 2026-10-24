package com.canaryagent.model

/** One observed agent tool. Immutable; the inventory replaces entries atomically. */
data class ToolEntry(
    /** First-seen display casing. The inventory key is the lowercased name. */
    val name: String,
    val parameterKeys: Set<String> = emptySet(),
    /** Number of distinct messages the tool appeared in (one increment per message). */
    val messageCount: Int = 0,
    val firstSeen: Long,
    val lastSeen: Long,
    /** Request URL or short identifier of where the tool was first observed. */
    val source: String = "",
)
