package com.canaryagent.persistence

import com.canaryagent.model.Attribution
import com.canaryagent.model.MarkerEncoding
import com.canaryagent.model.MarkerHit
import com.canaryagent.model.MarkerKind
import com.canaryagent.model.MarkerOrigin
import com.canaryagent.model.MarkerStatus
import com.canaryagent.model.TrafficChannel
import com.canaryagent.model.TrafficRef

/** Writes a [MarkerHit] into one node and reads it back. Unknown enum names make [read] return null. */
object MarkerCodec {

    /** Stores every field of [hit] under fixed keys; the last-hit request and response go into the node's message slots. */
    fun write(node: KeyValueNode, hit: MarkerHit) {
        node.setString("token", hit.token)
        node.setString("label", hit.label)
        node.setString("kind", hit.kind.name)
        node.setString("status", hit.status.name)
        hit.agentPathStatus?.let { node.setString("agentPathStatus", it.name) } ?: node.deleteString("agentPathStatus")
        // Written before 2026-10-21 under the broader rule (any in-band sighting); never read back.
        node.deleteString("inBandStatus")
        node.setString("encodings", hit.encodings.joinToString(",") { it.name })
        node.setString("lastEncodings", hit.lastEncodings.joinToString(",") { it.name })
        node.setLong("hitCount", hit.hitCount.toLong())
        node.setLong("placedAt", hit.placedAt)
        node.setLong("lastHitAt", hit.lastHitAt ?: -1L)

        val o = hit.origin
        if (o == null) {
            node.deleteString("origin.tool")
        } else {
            node.setString("origin.tool", o.tool)
            node.setString("origin.method", o.method)
            node.setString("origin.host", o.host)
            node.setString("origin.path", o.path)
            node.setString("origin.location", o.location)
            node.setString("origin.bodyHash", o.bodyHash)
        }

        val h = hit.lastHit
        if (h == null) {
            node.deleteString("hit.channel")
            node.deleteString("hit.attribution")
            node.deleteString("hit.attributionSignals")
            node.deleteRequest("hit.request")
            node.deleteResponse("hit.response")
        } else {
            node.setString("hit.channel", h.channel.name)
            node.setString("hit.tool", h.tool)
            node.setString("hit.method", h.method)
            node.setString("hit.url", h.url)
            node.setLong("hit.timestamp", h.timestamp)
            node.setString("hit.excerpt", h.excerpt ?: "")
            node.setString("hit.messageId", h.messageId)
            h.attribution?.let { node.setString("hit.attribution", it.name) } ?: node.deleteString("hit.attribution")
            if (h.attributionSignals.isEmpty()) {
                node.deleteString("hit.attributionSignals")
            } else {
                node.setString("hit.attributionSignals", h.attributionSignals.joinToString(SIGNAL_SEPARATOR))
            }
            h.request?.let { node.setRequest("hit.request", it) } ?: node.deleteRequest("hit.request")
            h.response?.let { node.setResponse("hit.response", it) } ?: node.deleteResponse("hit.response")
        }

        // The labelled interaction keeps only what the Attribution cell shows; no request/response copies.
        val i = hit.labelledInteraction
        node.setString(INTERACTION_TRACKED, "1")
        if (i == null) {
            for (k in INTERACTION_KEYS) node.deleteString(k)
        } else {
            node.setString("interaction.method", i.method)
            node.setString("interaction.url", i.url)
            node.setString("interaction.messageId", i.messageId)
            node.setString("interaction.timestamp", i.timestamp.toString())
            i.attribution?.let { node.setString("interaction.attribution", it.name) } ?: node.deleteString("interaction.attribution")
            if (i.attributionSignals.isEmpty()) {
                node.deleteString("interaction.attributionSignals")
            } else {
                node.setString("interaction.attributionSignals", i.attributionSignals.joinToString(SIGNAL_SEPARATOR))
            }
        }
    }

    /** Rebuilds a hit; null when the token is missing or an enum name is unknown, so a stale row is skipped rather than failing the restore. */
    fun read(node: KeyValueNode): MarkerHit? {
        val token = node.getString("token")?.takeIf { it.isNotEmpty() } ?: return null
        val kind = enumOrNull<MarkerKind>(node.getString("kind")) ?: return null
        val status = enumOrNull<MarkerStatus>(node.getString("status")) ?: return null
        val placedAt = node.getLong("placedAt") ?: return null

        val origin = node.getString("origin.tool")?.let { tool ->
            MarkerOrigin(
                tool = tool,
                method = node.getString("origin.method") ?: "",
                host = node.getString("origin.host") ?: "",
                path = node.getString("origin.path") ?: "",
                location = node.getString("origin.location") ?: "",
                bodyHash = node.getString("origin.bodyHash") ?: "",
            )
        }

        val lastHit = enumOrNull<TrafficChannel>(node.getString("hit.channel"))?.let { channel ->
            TrafficRef(
                channel = channel,
                tool = node.getString("hit.tool") ?: "",
                method = node.getString("hit.method") ?: "",
                url = node.getString("hit.url") ?: "",
                timestamp = node.getLong("hit.timestamp") ?: placedAt,
                request = node.getRequest("hit.request"),
                response = node.getResponse("hit.response"),
                excerpt = node.getString("hit.excerpt")?.takeIf { it.isNotEmpty() },
                messageId = node.getString("hit.messageId") ?: "",
                attribution = enumOrNull<Attribution>(node.getString("hit.attribution")),
                attributionSignals = node.getString("hit.attributionSignals")?.split(SIGNAL_SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList(),
            )
        }

        return MarkerHit(
            token = token,
            label = node.getString("label") ?: token,
            kind = kind,
            origin = origin,
            placedAt = placedAt,
            status = status,
            agentPathStatus = enumOrNull<MarkerStatus>(node.getString("agentPathStatus")),
            encodings = encodings(node.getString("encodings")),
            lastEncodings = encodings(node.getString("lastEncodings")),
            hitCount = (node.getLong("hitCount") ?: 0L).toInt(),
            lastHitAt = node.getLong("lastHitAt")?.takeIf { it >= 0 },
            lastHit = lastHit,
            // Rows written before the field existed fall back to their last hit when that was an interaction.
            labelledInteraction = if (node.getString(INTERACTION_TRACKED) != null) {
                readInteraction(node, placedAt)
            } else {
                lastHit?.takeIf { it.channel == TrafficChannel.COLLABORATOR }
            },
        )
    }

    /** Present on every row written since the labelled interaction was added; absent means an older row. */
    private const val INTERACTION_TRACKED = "interaction.tracked"

    private fun readInteraction(node: KeyValueNode, placedAt: Long): TrafficRef? {
        val method = node.getString("interaction.method") ?: return null
        return TrafficRef(
            channel = TrafficChannel.COLLABORATOR,
            tool = COLLABORATOR_TOOL,
            method = method,
            url = node.getString("interaction.url") ?: "",
            timestamp = node.getString("interaction.timestamp")?.toLongOrNull() ?: placedAt,
            messageId = node.getString("interaction.messageId") ?: "",
            attribution = enumOrNull<Attribution>(node.getString("interaction.attribution")),
            attributionSignals = node.getString("interaction.attributionSignals")?.split(SIGNAL_SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList(),
        )
    }

    private const val COLLABORATOR_TOOL = "Collaborator"

    private val INTERACTION_KEYS = listOf(
        "interaction.method", "interaction.url", "interaction.messageId", "interaction.timestamp",
        "interaction.attribution", "interaction.attributionSignals",
    )

    /** Signals are free text (header values, a User-Agent), so they are joined on a control character. */
    private const val SIGNAL_SEPARATOR = "\u001f"

    private fun encodings(csv: String?): Set<MarkerEncoding> =
        csv.orEmpty().split(',').mapNotNull { enumOrNull<MarkerEncoding>(it.trim()) }.toSet()

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }
}
