package com.canaryagent.ui

import com.canaryagent.model.AnnotatedTrafficItem
import com.canaryagent.model.MarkerHit
import com.canaryagent.model.MarkerOrigin
import com.canaryagent.model.ToolEntry
import com.canaryagent.util.Timestamps

/** Column definitions for the three tables. Plain Kotlin so they can be unit-tested without Swing or Burp. */
object Columns {

    val tools: List<Column<ToolEntry>> = listOf(
        Column("Tool", 160) { it.name },
        Column("Parameters", 260) { it.parameterKeys.joinToString(", ") },
        Column("Messages", 70, Integer::class.java) { it.messageCount },
        Column("First seen", 90) { Timestamps.hms(it.firstSeen) },
        Column("Last seen", 90) { Timestamps.hms(it.lastSeen) },
        Column("Source", 320) { it.source },
    )

    val markers: List<Column<MarkerHit>> = listOf(
        Column("Marker", 130) { it.label },
        Column("Kind", 80) { it.kind.label },
        Column("Placed at", 260) { it.origin?.description ?: MarkerOrigin.CLIPBOARD },
        Column("Status", 120) { it.status.label },
        Column("Attribution", 260) { attributionText(it) },
        Column("Encodings", 140) { it.encodings.sortedBy { e -> e.ordinal }.joinToString(", ") { e -> e.label } },
        Column("Hits", 50, Integer::class.java) { it.hitCount },
        Column("Last hit", 360) { lastHitText(it) },
    )

    val traffic: List<Column<AnnotatedTrafficItem>> = listOf(
        Column("Time", 90) { Timestamps.hms(it.timestamp) },
        Column("Tool", 80) { it.tool },
        Column("Method", 60) { it.method },
        Column("URL", 360) { it.url },
        Column("Note", 360) { it.note },
        Column("Source", 90) { it.source.label },
        Column("Session", 70) { it.sessionHint },
    )

    /**
     * `browser-attributed [Sec-Fetch-Mode: navigate, path /favicon.ico, User-Agent: ...]` for the row's
     * [MarkerHit.labelledInteraction]; it stays after later in-band hits. Empty before the first interaction.
     */
    fun attributionText(hit: MarkerHit): String {
        val shown = hit.labelledInteraction ?: return ""
        val a = shown.attribution ?: return ""
        return if (shown.attributionSignals.isEmpty()) a.label else "${a.label} [${shown.attributionSignals.joinToString(", ")}]"
    }

    /** `12:34:56.789 response POST https://...` or empty before the first hit. */
    fun lastHitText(hit: MarkerHit): String {
        val last = hit.lastHit ?: return ""
        val at = hit.lastHitAt ?: last.timestamp
        return buildString {
            append(Timestamps.hms(at)).append(' ').append(last.channel.label)
            if (last.method.isNotEmpty()) append(' ').append(last.method)
            append(' ').append(last.url)
        }
    }
}
