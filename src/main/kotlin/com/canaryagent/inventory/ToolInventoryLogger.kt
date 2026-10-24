package com.canaryagent.inventory

import com.canaryagent.model.ToolEntry
import com.canaryagent.util.CanaryLog
import com.canaryagent.util.Timestamps

/** Logs one line the first time a tool is observed, so detection is visible outside Proxy notes. */
class ToolInventoryLogger(private val log: CanaryLog) : ToolInventory.Listener {
    override fun onToolChanged(entry: ToolEntry, isNew: Boolean) {
        if (!isNew) return
        log.info("${Timestamps.hms(entry.firstSeen)} NEW TOOL ${entry.name} (${entry.parameterKeys.joinToString(", ")}) source=${entry.source}")
    }
}
