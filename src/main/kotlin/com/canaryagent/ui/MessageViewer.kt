package com.canaryagent.ui

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.ui.editor.EditorOptions
import com.canaryagent.model.AnnotatedTrafficItem
import javax.swing.JComponent
import javax.swing.JSplitPane

/** Read-only request/response display for the selected traffic row. */
interface MessageViewer {
    val component: JComponent

    /** Shows [item]'s stored copies, or empties the viewer when null. */
    fun show(item: AnnotatedTrafficItem?)
}

/** Montoya's read-only editors side by side. Needs a running Burp. */
class MontoyaMessageViewer(api: MontoyaApi) : MessageViewer {
    private val requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY)
    private val responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY)

    override val component: JComponent =
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, requestEditor.uiComponent(), responseEditor.uiComponent()).apply {
            resizeWeight = 0.5
            isContinuousLayout = true
        }

    override fun show(item: AnnotatedTrafficItem?) {
        requestEditor.setRequest(item?.request ?: HttpRequest.httpRequest())
        responseEditor.setResponse(item?.response ?: HttpResponse.httpResponse())
    }
}
