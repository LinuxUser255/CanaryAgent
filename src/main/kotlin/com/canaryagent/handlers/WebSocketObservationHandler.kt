package com.canaryagent.handlers

import burp.api.montoya.core.ToolType
import burp.api.montoya.websocket.BinaryMessage
import burp.api.montoya.websocket.BinaryMessageAction
import burp.api.montoya.websocket.MessageHandler
import burp.api.montoya.websocket.TextMessage
import burp.api.montoya.websocket.TextMessageAction
import burp.api.montoya.websocket.WebSocketCreated
import burp.api.montoya.websocket.WebSocketCreatedHandler
import com.canaryagent.util.toLatin1

/**
 * Attaches marker scanning to every non-Proxy WebSocket (Repeater, extensions, ...), both directions.
 * Proxy sockets are handled by [ProxyWebSocketObservationHandler], which can also annotate them.
 * Never modifies messages.
 */
class WebSocketObservationHandler(private val observer: WebSocketMarkerObserver) : WebSocketCreatedHandler {

    override fun handleWebSocketCreated(created: WebSocketCreated) {
        val tool = created.toolSource().toolType()
        if (tool == ToolType.PROXY) return
        val endpoint = WebSocketEndpoint.of(created.upgradeRequest(), tool)

        created.webSocket().registerMessageHandler(object : MessageHandler {
            override fun handleTextMessage(message: TextMessage): TextMessageAction {
                observer.observe(message.payload(), message.direction(), endpoint)
                return TextMessageAction.continueWith(message)
            }

            override fun handleBinaryMessage(message: BinaryMessage): BinaryMessageAction {
                observer.observe(message.payload().toLatin1(), message.direction(), endpoint)
                return BinaryMessageAction.continueWith(message)
            }
        })
    }
}
