package com.canaryagent.selftest

import com.canaryagent.CanaryAgentRuntime
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Run button plus a log area. The run happens on a daemon thread; the EDT only appends text.
 * "Run automatically on load" (kept in Burp's user preferences) lets a rebuilt JAR, auto-reloaded by
 * Burp, run the suite without a click, and "Clear stores first" keeps persisted markers from piling up
 * across runs.
 */
class SelfTestTab(private val runtime: CanaryAgentRuntime) {
    companion object {
        const val PREF_AUTORUN = "canaryagent.selftest.autorun"
        const val PREF_CLEAR_FIRST = "canaryagent.selftest.clearFirst"
        /** System property `-Dcanaryagent.selftest.autorun=true`: same as the checkbox, for headless runs with no UI to tick. */
        const val PROP_AUTORUN = "canaryagent.selftest.autorun"
        /**
         * System property `-Dcanaryagent.selftest.unloadAfterRun=true`: once the RESULT line is out, unload
         * this extension through Montoya so the unloading handlers run for real. A headless harness then
         * checks the log for "Unloaded." and takes a thread dump; SIGTERM alone never runs the handlers.
         */
        const val PROP_UNLOAD_AFTER_RUN = "canaryagent.selftest.unloadAfterRun"
        private const val AUTORUN_DELAY_MS = 3000
    }

    private val prefs = runtime.api.persistence().preferences()
    private val output = JTextArea().apply { isEditable = false; lineWrap = false }
    private val runButton = JButton("Run self-test")
    private val clearButton = JButton("Clear stores")
    private val autoRun = JCheckBox("Run automatically on load", prefs.getBoolean(PREF_AUTORUN) ?: false)
    private val clearFirst = JCheckBox("Clear stores first", prefs.getBoolean(PREF_CLEAR_FIRST) ?: true)
    private val status = JLabel("Idle")
    private var runner: SelfTestRunner? = null
    private var worker: Thread? = null
    private var autoRunTimer: Timer? = null

    val component: JComponent = JPanel(BorderLayout()).apply {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        bar.add(runButton)
        bar.add(clearButton)
        bar.add(autoRun)
        bar.add(clearFirst)
        bar.add(status)
        bar.add(JLabel("Sends test traffic to httpbin.org and echo.websocket.org through Burp. A2 needs the proxy listener on 127.0.0.1:8080."))
        add(bar, BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
        runtime.api.userInterface().applyThemeToComponent(this)
    }

    init {
        runButton.addActionListener { runNow() }
        clearButton.addActionListener { clearStores(); append("Stores cleared.") }
        autoRun.addActionListener { prefs.setBoolean(PREF_AUTORUN, autoRun.isSelected) }
        clearFirst.addActionListener { prefs.setBoolean(PREF_CLEAR_FIRST, clearFirst.isSelected) }
        if (autoRun.isSelected || System.getProperty(PROP_AUTORUN) == "true") {
            autoRunTimer = Timer(AUTORUN_DELAY_MS) { runNow() }.apply { isRepeats = false; start() }
        }
    }

    /** Starts a run unless one is in progress. Safe to call from any thread. */
    fun runNow() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater { runNow() }; return }
        if (worker?.isAlive == true) return
        output.text = ""
        runButton.isEnabled = false
        status.text = "Running..."
        if (clearFirst.isSelected) { clearStores(); append("Stores cleared before run.") }
        val r = SelfTestRunner(runtime) { line -> append(line); runtime.log.info("[self-test] $line") }
        runner = r
        worker = Thread({
            try {
                val results = r.runAll()
                val failed = results.count { it.outcome == SelfTestRunner.Outcome.FAIL }
                SwingUtilities.invokeLater { status.text = if (failed == 0) "Done: no failures" else "Done: $failed failure(s)" }
            } catch (t: Throwable) {
                append("Self-test aborted: $t")
                SwingUtilities.invokeLater { status.text = "Aborted" }
            } finally {
                SwingUtilities.invokeLater { runButton.isEnabled = true }
                if (System.getProperty(PROP_UNLOAD_AFTER_RUN) == "true") {
                    runtime.log.info("[self-test] unloading after run (${PROP_UNLOAD_AFTER_RUN}=true)")
                    runtime.api.extension().unload()
                }
            }
        }, "CanaryAgent-SelfTest").apply { isDaemon = true }
        worker?.start()
    }

    private fun clearStores() {
        runtime.tracker.clear()
        runtime.inventory.clear()
        runtime.traffic.clear()
        runtime.persistence?.flushNow()
    }

    private fun append(line: String) {
        SwingUtilities.invokeLater {
            output.append(line)
            output.append("\n")
            output.caretPosition = output.document.length
        }
    }

    fun dispose() {
        autoRunTimer?.stop()
        runner?.cancel()
        worker?.interrupt()
    }
}
