package com.canaryagent.contextmenu

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.HttpRequestResponse
import com.canaryagent.annotation.TrafficAnnotator
import com.canaryagent.collaborator.CollaboratorService
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import burp.api.montoya.ui.contextmenu.InvocationType
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse
import burp.api.montoya.ui.contextmenu.WebSocketContextMenuEvent
import burp.api.montoya.ui.contextmenu.WebSocketEditorEvent
import com.canaryagent.markers.MarkerGenerator
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.MarkerKind
import com.canaryagent.model.MarkerOrigin
import com.canaryagent.util.CanaryLog
import com.canaryagent.util.Hashing
import java.awt.Component
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import burp.api.montoya.core.ByteArray as BurpByteArray

/**
 * "CanaryAgent" context-menu actions: insert a generated or custom marker (or, on Professional, a
 * Collaborator payload) into the open editor, track selected text as a marker, copy a fresh marker or
 * payload to the clipboard, mark messages as agent content, send the original request to Repeater
 * named after a tool, and copy a tool snippet. [collaborator] is null on Community.
 */
class CanaryAgentContextMenuProvider(
    private val api: MontoyaApi,
    private val tracker: MarkerTracker,
    private val annotator: TrafficAnnotator,
    private val collaborator: CollaboratorService?,
    private val log: CanaryLog,
) : ContextMenuItemsProvider {

    /** Text to insert plus how to track it. For plain markers token == text. */
    private class Placement(val text: String, val token: String, val kind: MarkerKind)

    override fun provideMenuItems(event: ContextMenuEvent): List<Component> {
        val items = ArrayList<Component>()
        val editor = event.messageEditorRequestResponse().orElse(null)
        val tool = event.toolType().toString()
        val collab = collaborator?.takeIf { it.isAvailable() }

        if (editor != null) {
            val editable = event.isFrom(InvocationType.MESSAGE_EDITOR_REQUEST, InvocationType.MESSAGE_EDITOR_RESPONSE)
            if (editable) {
                // Each action checks the caret first so a refusal costs no token, dialog or Collaborator payload.
                items += menuItem("Insert new marker") { ifInsertable(editor) { insertMarker(editor, tool, markerPlacement(generate())) } }
                items += menuItem("Insert custom marker...") {
                    ifInsertable(editor) { askForToken()?.let { insertMarker(editor, tool, markerPlacement(it)) } }
                }
                if (collab != null) {
                    items += menuItem("Insert Collaborator payload") {
                        ifInsertable(editor) { collaboratorPlacement(collab)?.let { insertMarker(editor, tool, it) } }
                    }
                }
            }
            val selected = safely { MarkerInserter.selectedText(editor) }?.trim()
            if (!selected.isNullOrEmpty() && selected.length <= MAX_SELECTION_TOKEN) {
                items += menuItem("Track selected text as marker") {
                    val origin = MarkerInserter.originOf(editor, tool, "selection", MarkerInserter.requestBodyHash(editor))
                    track(selected, origin)
                }
            }
        }
        items += menuItem("Copy new marker to clipboard") { copyNewMarker() }
        if (collab != null) items += menuItem("Copy Collaborator payload to clipboard") { copyCollaboratorPayload(collab) }

        val targets = targetMessages(event)
        if (targets.isNotEmpty()) {
            items += menuItem(if (targets.size == 1) "Mark as agent content" else "Mark ${targets.size} items as agent content") {
                for (rr in targets) safely { annotator.markManually(rr, tool) }
            }
            if (targets.size == 1) {
                val rr = targets[0]
                val tools = safely { toolsIn(rr) } ?: emptyList()
                for (t in tools.take(MAX_TOOL_ITEMS)) {
                    items += menuItem("Send to Repeater as tool: ${t.name}") {
                        safely { api.repeater().sendToRepeater(rr.request(), "tool: ${t.name}") }
                    }
                }
                if (tools.isNotEmpty()) {
                    items += menuItem("Copy tool snippet") {
                        copyToClipboard(tools.joinToString("\n") { "${it.name}(${it.parameterKeys.joinToString(", ")})" })
                    }
                }
            }
        }
        return items
    }

    /** The message in the editor, or the selected history rows (capped). */
    private fun targetMessages(event: ContextMenuEvent): List<HttpRequestResponse> {
        event.messageEditorRequestResponse().orElse(null)?.let { return listOf(it.requestResponse()) }
        return event.selectedRequestResponses().take(MAX_SELECTED)
    }

    /** Tools visible in a request/response pair; bodies above a small cap are skipped to keep the EDT snappy. */
    private fun toolsIn(rr: HttpRequestResponse): List<com.canaryagent.inventory.ObservedTool> {
        val request = rr.request() ?: return emptyList()
        if (request.body().length() > MENU_PARSE_CAP) return emptyList()
        var analysis = annotator.analyze(request)
        val response = rr.response()
        if (response != null && response.body().length() <= MENU_PARSE_CAP) analysis = analysis.merge(annotator.analyze(response, request))
        return analysis.tools
    }

    override fun provideMenuItems(event: WebSocketContextMenuEvent): List<Component> {
        val items = ArrayList<Component>()
        val editor = event.messageEditorWebSocket().orElse(null)
        val tool = event.toolType().toString()
        val collab = collaborator?.takeIf { it.isAvailable() }
        if (editor != null) {
            if (!editor.isReadOnly) {
                items += menuItem("Insert new marker") { insertIntoWebSocket(editor, tool, markerPlacement(generate())) }
                items += menuItem("Insert custom marker...") { askForToken()?.let { insertIntoWebSocket(editor, tool, markerPlacement(it)) } }
                if (collab != null) {
                    items += menuItem("Insert Collaborator payload") { collaboratorPlacement(collab)?.let { insertIntoWebSocket(editor, tool, it) } }
                }
            }
            val selected = safely { selectedWebSocketText(editor) }?.trim()
            if (!selected.isNullOrEmpty() && selected.length <= MAX_SELECTION_TOKEN) {
                items += menuItem("Track selected text as marker") {
                    track(selected, webSocketOrigin(editor, tool, "selection", Hashing.sha256Hex(editor.contents.bytes)))
                }
            }
        }
        items += menuItem("Copy new marker to clipboard") { copyNewMarker() }
        if (collab != null) items += menuItem("Copy Collaborator payload to clipboard") { copyCollaboratorPayload(collab) }
        return items
    }

    private fun generate(): String = MarkerGenerator.nextUnique { tracker.contains(it) }

    private fun markerPlacement(token: String) = Placement(token, token, kindOf(token))

    /** A fresh payload tracked by its id, labelled with the full hostname; null (with a dialog) if unavailable. */
    private fun collaboratorPlacement(collab: CollaboratorService): Placement? {
        val payload = safely { collab.newPayload() }
        if (payload == null) {
            warn("Burp Collaborator is unavailable. Check Settings -> Project -> Collaborator.")
            return null
        }
        return Placement(payload.toString(), payload.id().toString(), MarkerKind.COLLABORATOR)
    }

    /** Runs [action] when the caret/selection is somewhere a marker can go; otherwise explains why not. */
    private fun ifInsertable(editor: MessageEditorHttpRequestResponse, action: () -> Unit) {
        val refusal = safely { MarkerInserter.refusal(editor) }
        if (refusal != null) warn(refusal) else action()
    }

    private fun place(p: Placement, origin: MarkerOrigin?) {
        tracker.place(p.token, p.kind, origin, label = p.text)
    }

    private fun insertMarker(editor: MessageEditorHttpRequestResponse, tool: String, p: Placement) {
        safely {
            when (val r = MarkerInserter.insert(editor, p.text)) {
                is MarkerInserter.Result.Inserted -> place(p, MarkerInserter.originOf(editor, tool, r.field, r.requestBodyHash))
                is MarkerInserter.Result.Refused -> warn(r.message)
            }
        }
    }

    private fun insertIntoWebSocket(editor: WebSocketEditorEvent, tool: String, p: Placement) {
        safely {
            val original = editor.contents.bytes
            val (start, end) = MarkerInserter.targetRange(editor.selectionOffsets().orElse(null), editor.caretPosition(), original.size)
            val updated = MarkerInserter.splice(original, start, end, p.text.toByteArray(Charsets.ISO_8859_1))
            editor.setContents(BurpByteArray.byteArray(*updated))
            place(p, webSocketOrigin(editor, tool, "websocket payload", Hashing.sha256Hex(updated)))
        }
    }

    private fun copyCollaboratorPayload(collab: CollaboratorService) {
        val p = collaboratorPlacement(collab) ?: return
        safely {
            copyToClipboard(p.text)
            place(p, null)
        }
    }

    private fun selectedWebSocketText(editor: WebSocketEditorEvent): String? {
        val range = editor.selectionOffsets().orElse(null) ?: return null
        val bytes = editor.contents.bytes
        val s = range.startIndexInclusive().coerceIn(0, bytes.size)
        val e = range.endIndexExclusive().coerceIn(s, bytes.size)
        return if (e > s) String(bytes, s, e - s, Charsets.ISO_8859_1) else null
    }

    private fun webSocketOrigin(editor: WebSocketEditorEvent, tool: String, field: String, payloadHash: String): MarkerOrigin? {
        val upgrade = editor.webSocketMessage().upgradeRequest() ?: return null
        return MarkerOrigin(tool, "WS", upgrade.httpService()?.host() ?: "", upgrade.pathWithoutQuery(), location = field, bodyHash = payloadHash)
    }

    private fun track(text: String, origin: MarkerOrigin?) {
        safely { tracker.place(text, kindOf(text), origin) }
    }

    private fun copyNewMarker() {
        safely {
            val token = generate()
            copyToClipboard(token)
            tracker.place(token, MarkerKind.TOKEN, null)
        }
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    private fun kindOf(token: String): MarkerKind =
        if (MarkerGenerator.isWellFormed(token.lowercase())) MarkerKind.TOKEN else MarkerKind.CUSTOM

    private fun askForToken(): String? {
        val input = JOptionPane.showInputDialog(
            api.userInterface().swingUtils().suiteFrame(),
            "Marker text (letters and digits recommended; matched case-insensitively):",
            "CanaryAgent: custom marker",
            JOptionPane.PLAIN_MESSAGE,
        ) as String? ?: return null
        val token = input.trim()
        if (token.length < MIN_CUSTOM_TOKEN) {
            warn("Marker must be at least $MIN_CUSTOM_TOKEN characters.")
            return null
        }
        return token
    }

    private fun warn(message: String) {
        JOptionPane.showMessageDialog(api.userInterface().swingUtils().suiteFrame(), message, "CanaryAgent", JOptionPane.WARNING_MESSAGE)
    }

    private fun menuItem(text: String, action: () -> Unit): JMenuItem =
        JMenuItem(text).apply { addActionListener { action() } }

    private inline fun <T> safely(block: () -> T): T? =
        try {
            block()
        } catch (t: RuntimeException) {
            log.error("Context-menu action failed", t)
            null
        }

    private companion object {
        const val MIN_CUSTOM_TOKEN = 4
        const val MAX_SELECTION_TOKEN = 256
        const val MAX_SELECTED = 50
        const val MAX_TOOL_ITEMS = 8
        const val MENU_PARSE_CAP = 256 * 1024
    }
}
