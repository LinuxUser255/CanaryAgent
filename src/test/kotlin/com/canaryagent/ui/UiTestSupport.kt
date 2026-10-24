package com.canaryagent.ui

import burp.api.montoya.http.message.requests.HttpRequest
import com.canaryagent.model.AnnotatedTrafficItem
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.event.PopupMenuEvent

/** Records what the panels asked Burp or the desktop to do. */
class FakeActions : UiActions {
    val sent = ArrayList<Pair<HttpRequest, String>>()
    val copied = ArrayList<String>()
    var confirmAnswer = true
    val confirmations = ArrayList<String>()

    override fun sendToRepeater(request: HttpRequest, tabName: String) { sent += request to tabName }
    override fun confirm(message: String): Boolean { confirmations += message; return confirmAnswer }
    override fun copyToClipboard(text: String) { copied += text }
}

/** Records which traffic item the panel asked to display. */
class FakeViewer : MessageViewer {
    override val component: JComponent = JPanel()
    val shown = ArrayList<AnnotatedTrafficItem?>()
    override fun show(item: AnnotatedTrafficItem?) { shown += item }
}

/** Finds a menu item by its text. */
fun JPopupMenu.item(text: String): JMenuItem =
    components.filterIsInstance<JMenuItem>().first { it.text == text }

/** Runs the item's action listeners without a display. */
fun JMenuItem.click() {
    val e = ActionEvent(this, ActionEvent.ACTION_PERFORMED, text)
    for (l in actionListeners) l.actionPerformed(e)
}

/** Fires the menu's "about to show" hook so items can update their enabled state. */
fun JPopupMenu.aboutToShow() {
    val e = PopupMenuEvent(this)
    for (l in popupMenuListeners) l.popupMenuWillBecomeVisible(e)
}

/** Dispatches a left double-click on the given view row. */
fun JTable.doubleClickRow(viewRow: Int) {
    setRowSelectionInterval(viewRow, viewRow)
    val rect = getCellRect(viewRow, 0, true)
    val e = MouseEvent(this, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, rect.x + 1, rect.y + 1, 2, false, MouseEvent.BUTTON1)
    for (l in mouseListeners) l.mouseClicked(e)
}
