package com.canaryagent.handlers

import burp.api.montoya.core.Annotations
import burp.api.montoya.core.ToolType
import burp.api.montoya.proxy.websocket.BinaryMessageReceivedAction
import burp.api.montoya.proxy.websocket.BinaryMessageToBeSentAction
import burp.api.montoya.proxy.websocket.InterceptedBinaryMessage
import burp.api.montoya.proxy.websocket.InterceptedTextMessage
import burp.api.montoya.proxy.websocket.ProxyMessageHandler
import burp.api.montoya.proxy.websocket.ProxyWebSocketCreation
import burp.api.montoya.proxy.websocket.ProxyWebSocketCreationHandler
import burp.api.montoya.proxy.websocket.TextMessageReceivedAction
import burp.api.montoya.proxy.websocket.TextMessageToBeSentAction
import com.canaryagent.annotation.TrafficAnnotator
import com.canaryagent.markers.MarkerMatch
import com.canaryagent.util.toLatin1

/**
 * Marker scanning for Proxy WebSockets. Frames are scanned once, when received from either side,
 * and hits are written to the frame's annotations (highlight + note) so they show in
 * Proxy -> WebSockets history. Never modifies payloads.
 */
class ProxyWebSocketObservationHandler(private val observer: WebSocketMarkerObserver) : ProxyWebSocketCreationHandler {

    override fun handleWebSocketCreation(creation: ProxyWebSocketCreation) {
        val endpoint = WebSocketEndpoint.of(creation.upgradeRequest(), ToolType.PROXY)

        creation.proxyWebSocket().registerProxyMessageHandler(object : ProxyMessageHandler {
            override fun handleTextMessageReceived(message: InterceptedTextMessage): TextMessageReceivedAction {
                annotate(message.annotations(), observer.observe(message.payload(), message.direction(), endpoint))
                return TextMessageReceivedAction.continueWith(message)
            }

            override fun handleTextMessageToBeSent(message: InterceptedTextMessage): TextMessageToBeSentAction =
                TextMessageToBeSentAction.continueWith(message)

            override fun handleBinaryMessageReceived(message: InterceptedBinaryMessage): BinaryMessageReceivedAction {
                annotate(message.annotations(), observer.observe(message.payload().toLatin1(), message.direction(), endpoint))
                return BinaryMessageReceivedAction.continueWith(message)
            }

            override fun handleBinaryMessageToBeSent(message: InterceptedBinaryMessage): BinaryMessageToBeSentAction =
                BinaryMessageToBeSentAction.continueWith(message)
        })
    }

    private fun annotate(annotations: Annotations, hits: List<MarkerMatch>) {
        if (hits.isEmpty()) return
        val note = TrafficAnnotator.markerHitNote(hits)
        annotations.setNotes(TrafficAnnotator.joinNotes(annotations.notes(), note))
        annotations.setHighlightColor(TrafficAnnotator.MARKER_COLOR)
    }
}
