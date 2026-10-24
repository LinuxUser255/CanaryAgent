package com.canaryagent.markers

import burp.api.montoya.core.ToolType
import com.canaryagent.model.TrafficChannel

/**
 * Decides whether a marker match counts as a hit.
 *
 * Outbound traffic (requests, client-to-server WebSocket frames) from user-driven tools such as
 * Repeater, Intruder or another extension is the user re-sending their own marker, never a hit.
 * Outbound traffic from the Proxy counts, except the request the marker was inserted into.
 * Inbound traffic (responses, server-to-client frames) always counts.
 */
object HitPolicy {
    /** True when a match seen in [channel] from [tool] is a hit; [isOrigin] marks the request the marker was inserted into. */
    fun shouldCount(channel: TrafficChannel, tool: ToolType, isOrigin: Boolean): Boolean =
        if (channel.isOutbound) tool == ToolType.PROXY && !isOrigin else true
}
