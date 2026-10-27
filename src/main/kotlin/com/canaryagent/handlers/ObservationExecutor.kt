package com.canaryagent.handlers

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Bounded background lane for the observation work that does not shape the returned annotations:
 * tracker hits, store rows, inventory updates and attribution analysis (F-review [3]). One daemon
 * thread, a bounded queue, and caller-runs on overflow - under load the handler degrades to doing
 * the work inline, so nothing is ever dropped (attribution, never suppression).
 *
 * [dispose] on unload; work submitted after disposal also runs inline for the same reason.
 */
class ObservationExecutor(queueCapacity: Int = DEFAULT_QUEUE_CAPACITY) : Executor {

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 512

        /** Runs everything on the calling thread; the default for tests and unwired components. */
        val DIRECT: Executor = Executor { it.run() }
    }

    private val pool = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue(queueCapacity),
        { r -> Thread(r, "CanaryAgent-Observation").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    override fun execute(command: Runnable) {
        if (pool.isShutdown) command.run() else pool.execute(command)
    }

    /** Stops accepting new work (later work runs inline); already queued work still completes. */
    fun dispose() {
        pool.shutdown()
    }
}
