package com.canaryagent.ui

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JOptionPane

/** Side effects the panels need from Burp or the desktop, behind a seam so panels can be tested headless. */
interface UiActions {
    /** Opens [request] in a new Repeater tab named [tabName]. */
    fun sendToRepeater(request: HttpRequest, tabName: String)

    /** Modal confirmation; true when the user accepts. */
    fun confirm(message: String): Boolean

    /** Puts [text] on the system clipboard. */
    fun copyToClipboard(text: String)
}

/** Production implementation: Repeater, a dialog parented to the suite frame, the system clipboard. */
class MontoyaUiActions(private val api: MontoyaApi) : UiActions {
    override fun sendToRepeater(request: HttpRequest, tabName: String) = api.repeater().sendToRepeater(request, tabName)

    override fun confirm(message: String): Boolean =
        JOptionPane.showConfirmDialog(
            api.userInterface().swingUtils().suiteFrame(),
            message,
            "CanaryAgent",
            JOptionPane.OK_CANCEL_OPTION,
        ) == JOptionPane.OK_OPTION

    override fun copyToClipboard(text: String) =
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
