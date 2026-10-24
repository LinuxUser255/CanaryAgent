package com.canaryagent.selftest

import com.canaryagent.CanaryAgentRuntime

/**
 * Dev-only store and heap sampler for the headless long-session run. Every [intervalSeconds] it logs
 * one line, `[self-test] STATS t=<s> traffic=<rows> tools=<n> markers=<n> heapAfterGcMb=<n>`, after
 * a forced garbage collection, so a harness can watch the bounded stores reach their caps and the heap
 * settle. Enabled by `-Dcanaryagent.selftest.statsEverySeconds=<n>`; the production JAR has no such
 * code path. The thread is a daemon and stops on [dispose].
 */
class SelfTestStats(private val runtime: CanaryAgentRuntime, private val intervalSeconds: Long) {
    companion object {
        const val PROP_STATS_EVERY_SECONDS = "canaryagent.selftest.statsEverySeconds"

        /** Starts a sampler when the property is a positive number; null otherwise. */
        fun startIfConfigured(runtime: CanaryAgentRuntime): SelfTestStats? {
            val seconds = System.getProperty(PROP_STATS_EVERY_SECONDS)?.toLongOrNull() ?: return null
            if (seconds <= 0) return null
            return SelfTestStats(runtime, seconds).also { it.start() }
        }
    }

    @Volatile private var running = true
    private val startedAt = System.currentTimeMillis()
    private val thread = Thread({ loop() }, "CanaryAgent-SelfTestStats").apply { isDaemon = true }

    private fun start() {
        runtime.log.info("[self-test] STATS sampler every ${intervalSeconds}s ($PROP_STATS_EVERY_SECONDS)")
        thread.start()
    }

    private fun loop() {
        while (running) {
            try {
                Thread.sleep(intervalSeconds * 1000)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            sample()
        }
    }

    private fun sample() {
        val rt = Runtime.getRuntime()
        System.gc()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val t = (System.currentTimeMillis() - startedAt) / 1000
        runtime.log.info(
            "[self-test] STATS t=$t traffic=${runtime.traffic.size()} tools=${runtime.inventory.size()} " +
                "markers=${runtime.tracker.size()} heapAfterGcMb=$usedMb",
        )
    }

    fun dispose() {
        running = false
        thread.interrupt()
    }
}
