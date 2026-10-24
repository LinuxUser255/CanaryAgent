package com.canaryagent.markers

import com.canaryagent.model.MarkerHit
import com.canaryagent.util.CanaryLog
import com.canaryagent.util.Timestamps

/** Writes one output line per marker placement or hit, so markers are observable before the UI tab exists. */
class MarkerHitLogger(private val log: CanaryLog) : MarkerTracker.Listener {

    companion object {
        /**
         * `HH:mm:ss.SSS HIT [status] in <request|response|ws->|ws<-> METHOD URL (encodings) tool=X id=Y`
         * for a hit, with ` attribution=<label> [signals]` appended for Collaborator interactions, or
         * `HH:mm:ss.SSS PLACED token at METHOD host/path (location) tool=X` for a placement.
         */
        fun format(hit: MarkerHit): String {
            val last = hit.lastHit
            if (hit.hitCount == 0 || last == null) {
                val ts = Timestamps.hms(hit.placedAt)
                val origin = hit.origin ?: return "$ts PLACED ${hit.label} (no origin)"
                return "$ts PLACED ${hit.label} at ${origin.description} tool=${origin.tool}"
            }
            val ts = Timestamps.hms(hit.lastHitAt ?: last.timestamp)
            val where = buildString {
                append(last.channel.label)
                if (last.method.isNotEmpty()) append(' ').append(last.method)
                append(' ').append(last.url)
            }
            val enc = if (hit.lastEncodings.isEmpty()) "" else " (" + hit.lastEncodings.joinToString(",") { it.label } + ")"
            val attribution = last.attribution?.let { a ->
                " attribution=${a.label}" + if (last.attributionSignals.isEmpty()) "" else " [" + last.attributionSignals.joinToString(", ") + "]"
            } ?: ""
            return "$ts HIT [${hit.status.label}] in $where$enc tool=${last.tool} id=${last.messageId}$attribution"
        }
    }

    override fun onMarkerChanged(token: String, hit: MarkerHit?) {
        if (hit == null) {
            log.info("REMOVED $token")
            return
        }
        log.info(format(hit))
    }
}
