package com.canaryagent.markers

import com.canaryagent.model.Attribution
import com.canaryagent.model.MarkerEncoding
import com.canaryagent.model.MarkerHit
import com.canaryagent.model.MarkerKind
import com.canaryagent.model.MarkerOrigin
import com.canaryagent.model.MarkerStatus
import com.canaryagent.model.TrafficChannel
import com.canaryagent.model.TrafficRef
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe store of placed markers, keyed by lowercased token.
 * Entries are immutable [MarkerHit]s replaced atomically via `compute`.
 */
class MarkerTracker : MarkerIndex {

    /** Receives the new state of a marker after it is placed, hit, or removed (null on removal). */
    fun interface Listener {
        fun onMarkerChanged(token: String, hit: MarkerHit?)

        /** Every marker was removed at once. */
        fun onMarkersCleared() {}
    }

    private val markers = ConcurrentHashMap<String, MarkerHit>()
    private val customTokens = ConcurrentHashMap.newKeySet<String>()
    private val lengthCounts = ConcurrentHashMap<Int, AtomicInteger>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Per marker, the fingerprints of messages already counted (F8). In memory only; bounded per marker. */
    private val fingerprints = ConcurrentHashMap<String, LinkedHashSet<String>>()

    companion object {
        /** Distinct messages remembered per marker; past this the oldest may count once more. */
        const val MAX_FINGERPRINTS = 256

        /** Identity of one message for hit counting: direction, endpoint (with query) and body hash. */
        fun fingerprint(channel: TrafficChannel, method: String, url: String, bodyHash: String): String =
            "${channel.name}|${method.uppercase()} $url|$bodyHash"
    }

    override val prefix: String = MarkerGenerator.PREFIX

    override fun contains(token: String): Boolean = markers.containsKey(token)

    override fun prefixedTokenLengths(): Set<Int> = lengthCounts.filterValues { it.get() > 0 }.keys

    override fun customTokens(): Collection<String> = customTokens

    operator fun get(token: String): MarkerHit? = markers[token.lowercase()]

    /**
     * Records a marker as placed. Re-placing an existing token keeps its history and updates the origin.
     * [label] is the display form; it defaults to the token but can differ (a Collaborator payload is
     * tracked by its id while the label is the full hostname).
     */
    fun place(
        rawToken: String,
        kind: MarkerKind,
        origin: MarkerOrigin?,
        now: Long = System.currentTimeMillis(),
        label: String = rawToken.trim(),
    ): MarkerHit {
        val token = rawToken.trim().lowercase()
        require(token.isNotEmpty()) { "marker token must not be blank" }
        val result = markers.compute(token) { _, existing ->
            existing?.copy(origin = origin ?: existing.origin)
                ?: MarkerHit(token = token, label = label, kind = kind, origin = origin, placedAt = now)
        }!!
        if (token.startsWith(prefix)) {
            lengthCounts.computeIfAbsent(token.length) { AtomicInteger() }.incrementAndGet()
        } else {
            customTokens.add(token)
        }
        notifyChanged(token, result)
        return result
    }

    /**
     * Records an observation of [token]. The observed status comes from the channel of [ref]
     * (outbound -> seen in request, inbound -> seen in response) or tool-call context; the stored
     * status only ever moves up in rank; [inToolCall] outranks [inToolResult] when both hold. In-band
     * observations in agent-shaped traffic ([inToolCall], [inToolResult], or
     * [agentContext] when the message is one the detector flags as agent traffic) also raise
     * [MarkerHit.agentPathStatus], which a Collaborator interaction never touches. A marker without an
     * origin (clipboard) takes the first message it is seen in as its origin. Returns the updated hit, or
     * null if the token is not tracked.
     *
     * F8: [MarkerHit.hitCount] counts distinct messages. A [fingerprint] (see [fingerprint]) already seen
     * for this marker is a repeat (a poll, a page reload, the same history re-served): it still updates
     * status, last hit and everything else, but not the count. Collaborator interactions always count.
     */
    fun recordHit(
        token: String,
        inToolCall: Boolean,
        encodings: Set<MarkerEncoding>,
        ref: TrafficRef?,
        now: Long = System.currentTimeMillis(),
        agentContext: Boolean = false,
        inToolResult: Boolean = false,
        fingerprint: String? = null,
    ): MarkerHit? {
        val key = token.lowercase()
        if (!markers.containsKey(key)) return null
        val observed = when {
            ref?.channel == TrafficChannel.COLLABORATOR -> MarkerStatus.OOB_INTERACTION
            inToolCall -> MarkerStatus.SEEN_IN_TOOL_CALL
            inToolResult -> MarkerStatus.SEEN_IN_TOOL_RESULT
            ref?.channel?.isOutbound == true -> MarkerStatus.SEEN_IN_REQUEST
            else -> MarkerStatus.SEEN_IN_RESPONSE
        }
        val onAgentPath = observed != MarkerStatus.OOB_INTERACTION && (inToolCall || inToolResult || agentContext)
        val repeat = fingerprint != null && observed != MarkerStatus.OOB_INTERACTION && !rememberFingerprint(key, fingerprint)
        val updated = markers.computeIfPresent(key) { _, e ->
            e.copy(
                origin = e.origin ?: ref?.let { MarkerOrigin.firstSeenIn(it) },
                status = if (observed.rank > e.status.rank) observed else e.status,
                agentPathStatus = when {
                    !onAgentPath -> e.agentPathStatus
                    e.agentPathStatus == null || observed.rank > e.agentPathStatus.rank -> observed
                    else -> e.agentPathStatus
                },
                encodings = e.encodings + encodings,
                lastEncodings = encodings,
                hitCount = if (repeat) e.hitCount else e.hitCount + 1,
                lastHitAt = now,
                lastHit = ref ?: e.lastHit,
                labelledInteraction = labelledAfter(e.labelledInteraction, ref),
            )
        }
        if (updated != null) notifyChanged(updated.token, updated)
        return updated
    }

    /** [MarkerHit.labelledInteraction] after [ref]: a labelled interaction replaces anything; an unattributed one only another unattributed one. */
    private fun labelledAfter(current: TrafficRef?, ref: TrafficRef?): TrafficRef? {
        if (ref?.channel != TrafficChannel.COLLABORATOR) return current
        val informative = ref.attribution != null && ref.attribution != Attribution.UNATTRIBUTED
        val currentInformative = current?.attribution != null && current.attribution != Attribution.UNATTRIBUTED
        return if (informative || !currentInformative) ref else current
    }

    /** Adds [fingerprint] to [key]'s bounded set; true when it was new. The oldest entry goes past the cap. */
    private fun rememberFingerprint(key: String, fingerprint: String): Boolean {
        val seen = fingerprints.computeIfAbsent(key) { LinkedHashSet() }
        synchronized(seen) {
            if (!seen.add(fingerprint)) return false
            if (seen.size > MAX_FINGERPRINTS) seen.remove(seen.first())
            return true
        }
    }

    /**
     * True only for the exact message [token] was inserted into: same endpoint and the same body hash.
     * An origin recorded without a hash never excludes anything, so a later turn to the same endpoint
     * is a hit.
     */
    fun isOrigin(token: String, method: String, host: String, path: String, bodyHash: String): Boolean {
        val o = markers[token.lowercase()]?.origin ?: return false
        if (o.bodyHash.isEmpty()) return false
        return o.method.equals(method, ignoreCase = true) &&
            o.host.equals(host, ignoreCase = true) &&
            o.path == path &&
            o.bodyHash == bodyHash
    }

    /** Puts a stored marker back without notifying listeners; rebuilds the lookup indexes. */
    fun restore(hit: MarkerHit) {
        val token = hit.token.lowercase()
        val previous = markers.put(token, hit.copy(token = token))
        if (previous != null) return
        if (token.startsWith(prefix)) {
            lengthCounts.computeIfAbsent(token.length) { AtomicInteger() }.incrementAndGet()
        } else {
            customTokens.add(token)
        }
    }

    /** Forgets [token] and its F8 fingerprints; listeners see null for it. Unknown tokens are ignored. */
    fun remove(token: String) {
        val key = token.lowercase()
        val removed = markers.remove(key) ?: return
        fingerprints.remove(key)
        if (key.startsWith(prefix)) lengthCounts[key.length]?.decrementAndGet() else customTokens.remove(key)
        notifyChanged(removed.token, null)
    }

    /** Forgets every marker, fingerprint and custom token, and notifies listeners once. */
    fun clear() {
        markers.clear()
        fingerprints.clear()
        customTokens.clear()
        lengthCounts.clear()
        for (l in listeners) try { l.onMarkersCleared() } catch (_: RuntimeException) { }
    }

    /** Number of tracked markers. */
    fun size(): Int = markers.size

    /** Immutable snapshot ordered by placement time; safe to hand to the EDT. */
    fun snapshot(): List<MarkerHit> = markers.values.sortedBy { it.placedAt }

    /** Registers [l]; listeners are detached again on unload. */
    fun addListener(l: Listener) { listeners += l }

    /** Detaches [l]; a no-op when it was never added. */
    fun removeListener(l: Listener) { listeners -= l }

    private fun notifyChanged(token: String, hit: MarkerHit?) {
        for (l in listeners) {
            try { l.onMarkerChanged(token, hit) } catch (_: RuntimeException) { /* listener bugs must not break tracking */ }
        }
    }
}
