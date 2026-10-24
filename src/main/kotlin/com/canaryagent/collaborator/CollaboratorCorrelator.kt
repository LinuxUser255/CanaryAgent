package com.canaryagent.collaborator

import burp.api.montoya.collaborator.Interaction
import burp.api.montoya.collaborator.InteractionType
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.MarkerStatus
import com.canaryagent.model.TrafficChannel
import com.canaryagent.model.TrafficRef

/**
 * Matches Collaborator interactions to tracked payloads. A payload is tracked by its interaction id
 * (lowercase), so `interaction.id()` is the lookup key. Pure logic, unit-tested with fake interactions.
 *
 * Every matching interaction is recorded and promotes the row; [InteractionAttribution] only labels
 * what the evidence suggests caused it. Its tier-2 input is the row's agent-path status, which no
 * interaction changes: Collaborator delivers the DNS lookups before the HTTP fetch, so by the time the
 * fetch is evaluated the plain status has already been promoted by the first lookup.
 */
object CollaboratorCorrelator {
    const val TOOL = "Collaborator"
    private const val EXCERPT_CHARS = 200

    /** Records one hit per matching interaction. Returns how many matched. */
    fun apply(interactions: List<Interaction>, tracker: MarkerTracker, now: Long = System.currentTimeMillis()): Int {
        var matched = 0
        for (interaction in interactions) {
            val token = interaction.id().toString().lowercase()
            val prior = tracker[token] ?: continue
            val ref = refFor(interaction, now, prior.agentPathStatus)
            if (tracker.recordHit(token, inToolCall = false, encodings = emptySet(), ref = ref, now = now) != null) matched++
        }
        return matched
    }

    /**
     * `oob DNS from 10.0.0.5:53`, with request/response copies for HTTP interactions and the attribution
     * label derived from the captured request and [agentPathStatus] (the row's highest agent-path status).
     */
    fun refFor(interaction: Interaction, now: Long, agentPathStatus: MarkerStatus? = null): TrafficRef {
        val ip = interaction.clientIp()?.hostAddress ?: "?"
        val http = interaction.httpDetails().orElse(null)?.requestResponse()
        val attributed = InteractionAttribution.attribute(http?.request(), agentPathStatus)
        val excerpt = when (interaction.type()) {
            InteractionType.DNS -> interaction.dnsDetails().orElse(null)?.queryType()?.toString() ?: ""
            InteractionType.HTTP -> http?.request()?.toString()?.lineSequence()?.firstOrNull() ?: ""
            InteractionType.SMTP -> interaction.smtpDetails().orElse(null)?.conversation() ?: ""
            else -> ""
        }
        return TrafficRef(
            channel = TrafficChannel.COLLABORATOR,
            tool = TOOL,
            method = interaction.type().name,
            url = "from $ip:${interaction.clientPort()}",
            timestamp = interaction.timeStamp()?.toInstant()?.toEpochMilli() ?: now,
            request = http?.request()?.copyToTempFile(),
            response = http?.response()?.copyToTempFile(),
            excerpt = excerpt.take(EXCERPT_CHARS),
            messageId = interaction.id().toString(),
            attribution = attributed.attribution,
            attributionSignals = attributed.signals,
        )
    }
}
