package com.canaryagent.handlers

import burp.api.montoya.core.ToolType
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.websocket.Direction
import com.canaryagent.markers.HitPolicy
import com.canaryagent.markers.MarkerMatch
import com.canaryagent.markers.MarkerScanner
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.TrafficChannel
import com.canaryagent.model.TrafficRef
import com.canaryagent.util.CanaryLog
import com.canaryagent.util.Hashing

/** Identity of one WebSocket connection, derived from its upgrade request. */
data class WebSocketEndpoint(val url: String, val host: String, val path: String, val tool: ToolType) {
    companion object {
        /** `wss://host[:port]/path` built from the upgrade request's service, since `url()` reports `https`. */
        fun of(upgrade: HttpRequest, tool: ToolType): WebSocketEndpoint {
            val service = upgrade.httpService()
            val secure = service?.secure() ?: true
            val host = service?.host() ?: ""
            val port = service?.port() ?: if (secure) 443 else 80
            val defaultPort = if (secure) 443 else 80
            val path = upgrade.path().ifEmpty { "/" }
            val url = buildString {
                append(if (secure) "wss://" else "ws://").append(host)
                if (port != defaultPort) append(':').append(port)
                append(path)
            }
            return WebSocketEndpoint(url, host, upgrade.pathWithoutQuery(), tool)
        }
    }
}

/** Marker scanning shared by the generic and the Proxy WebSocket handlers. */
class WebSocketMarkerObserver(
    private val tracker: MarkerTracker,
    private val scanner: MarkerScanner,
    private val log: CanaryLog,
    /** Whether a frame is agent-shaped traffic, for attribution; consulted only when a frame has hits. */
    private val agentShaped: (String) -> Boolean = { false },
) {
    /** Scans one frame and records hits per [HitPolicy]. Returns the hits (empty when nothing counted). */
    fun observe(payload: String, direction: Direction, endpoint: WebSocketEndpoint): List<MarkerMatch> {
        if (tracker.size() == 0) return emptyList()
        return try {
            val channel = if (direction == Direction.CLIENT_TO_SERVER) TrafficChannel.WS_OUT else TrafficChannel.WS_IN
            val matches = scanner.scan(payload)
            if (matches.isEmpty()) return emptyList()
            val payloadHash = Hashing.sha256Hex(payload)
            val hits = matches.filter { m ->
                HitPolicy.shouldCount(channel, endpoint.tool, tracker.isOrigin(m.token, "WS", endpoint.host, endpoint.path, payloadHash))
            }
            if (hits.isEmpty()) return emptyList()
            val ref = TrafficRef(
                channel = channel,
                tool = endpoint.tool.toString(),
                method = "",
                url = endpoint.url,
                timestamp = System.currentTimeMillis(),
                excerpt = payload.take(EXCERPT_CHARS),
                messageId = "sha:" + payloadHash.take(8),
            )
            val agentContext = agentShaped(payload)
            val fingerprint = MarkerTracker.fingerprint(channel, "WS", endpoint.url, payloadHash)
            for (m in hits) {
                tracker.recordHit(m.token, m.inToolCall, m.encodings, ref, agentContext = agentContext, inToolResult = m.inToolResult, fingerprint = fingerprint)
            }
            hits
        } catch (t: RuntimeException) {
            log.error("Marker scan failed for WebSocket message on ${endpoint.url}", t)
            emptyList()
        }
    }

    private companion object {
        const val EXCERPT_CHARS = 200
    }
}
