package com.canaryagent.ui

import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Coalesces bursts of store changes into one EDT refresh. [requestRefresh] may be called from any
 * thread; [action] runs on the EDT at most once per [delayMs] window.
 */
class RefreshCoalescer(delayMs: Int = DEFAULT_DELAY_MS, private val action: () -> Unit) {
    companion object {
        const val DEFAULT_DELAY_MS = 250
    }

    private val timer = Timer(delayMs) { action() }.apply { isRepeats = false }

    /** Arms the timer unless it is already running; safe from any thread. */
    fun requestRefresh() {
        SwingUtilities.invokeLater { if (!timer.isRunning) timer.start() }
    }

    /** Runs [action] now on the EDT (or schedules it) regardless of the timer. */
    fun refreshNow() {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }

    /** Cancels a pending refresh; called on unload so no timer outlives the extension. */
    fun stop() {
        timer.stop()
    }
}
