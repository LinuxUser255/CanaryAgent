package com.canaryagent.collaborator

import com.canaryagent.markers.MarkerTracker
import com.canaryagent.settings.CanaryAgentSettings
import com.canaryagent.util.CanaryLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Polls Collaborator for new interactions on a daemon scheduler, never on the EDT, and feeds them to
 * [CollaboratorCorrelator]. Ticks are no-ops until a payload has been generated. [dispose] on unload.
 *
 * The interval is read through [intervalSeconds] before every tick, never cached: Burp hands out stale
 * settings values during `initialize`, so anything read once at load would be wrong.
 */
class CollaboratorPoller(
    private val service: CollaboratorService,
    private val tracker: MarkerTracker,
    private val log: CanaryLog,
    private val intervalSeconds: () -> Long = { DEFAULT_INTERVAL_SECONDS },
) {
    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 30L
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "CanaryAgent-Collaborator").apply { isDaemon = true }
    }

    /** Schedules the first poll; each tick re-reads the interval setting before scheduling the next. */
    fun start() = scheduleNext()

    private fun scheduleNext() {
        if (executor.isShutdown) return
        val delay = try {
            intervalSeconds().coerceIn(
                CanaryAgentSettings.MIN_COLLABORATOR_INTERVAL.toLong(),
                CanaryAgentSettings.MAX_COLLABORATOR_INTERVAL.toLong(),
            )
        } catch (_: RuntimeException) {
            DEFAULT_INTERVAL_SECONDS
        }
        executor.schedule({ tick(); scheduleNext() }, delay, TimeUnit.SECONDS)
    }

    private fun tick() {
        try {
            if (tracker.size() == 0) return
            val interactions = service.fetchInteractions()
            if (interactions.isEmpty()) return
            val matched = CollaboratorCorrelator.apply(interactions, tracker)
            if (matched < interactions.size) {
                log.info("Collaborator: ${interactions.size - matched} interaction(s) did not match a tracked payload.")
            }
        } catch (t: Throwable) {
            // Never let one failure kill the scheduler; a repeated message is logged once.
            log.once("collab-poll:${t.javaClass.simpleName}", "Collaborator polling failed: ${t.message}")
        }
    }

    /** Stops the scheduler at once; called from the unloading handler so no poll outlives the extension. */
    fun dispose() {
        executor.shutdownNow()
    }
}
