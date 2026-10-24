package com.canaryagent.util

import burp.api.montoya.logging.Logging
import java.util.concurrent.ConcurrentHashMap

/** Thin wrapper over Montoya logging with a consistent prefix and one-time messages. */
class CanaryLog(private val logging: Logging) {
    private val onceKeys = ConcurrentHashMap.newKeySet<String>()

    /** One line on the extension's Output tab, prefixed. */
    fun info(message: String) = logging.logToOutput("[CanaryAgent] $message")

    /** One line on the Errors tab, with the stack trace when [t] is given. */
    fun error(message: String, t: Throwable? = null) {
        if (t != null) logging.logToError("[CanaryAgent] $message", t) else logging.logToError("[CanaryAgent] $message")
    }

    /** Logs [message] only the first time [key] is seen; use for warnings that would otherwise flood. */
    fun once(key: String, message: String) {
        if (onceKeys.add(key)) info(message)
    }
}
