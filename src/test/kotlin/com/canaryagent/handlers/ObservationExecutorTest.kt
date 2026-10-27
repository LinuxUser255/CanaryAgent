package com.canaryagent.handlers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservationExecutorTest {

    @Test
    fun `work runs on the background thread, not the caller`() {
        val executor = ObservationExecutor()
        try {
            val done = CountDownLatch(1)
            var workerName = ""
            executor.execute {
                workerName = Thread.currentThread().name
                done.countDown()
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "work must run")
            assertEquals("CanaryAgent-Observation", workerName)
        } finally {
            executor.dispose()
        }
    }

    @Test
    fun `overflow degrades to the caller thread instead of dropping work`() {
        val executor = ObservationExecutor(queueCapacity = 1)
        val blockWorker = CountDownLatch(1)
        try {
            val workerBusy = CountDownLatch(1)
            executor.execute { workerBusy.countDown(); blockWorker.await(5, TimeUnit.SECONDS) }
            assertTrue(workerBusy.await(5, TimeUnit.SECONDS))
            executor.execute { } // fills the queue
            var overflowThread = ""
            executor.execute { overflowThread = Thread.currentThread().name } // rejected -> caller runs
            assertEquals(Thread.currentThread().name, overflowThread, "overflow work must run inline, never be dropped")
        } finally {
            blockWorker.countDown()
            executor.dispose()
        }
    }

    @Test
    fun `after dispose, work still runs inline`() {
        val executor = ObservationExecutor()
        executor.dispose()
        var ran = false
        executor.execute { ran = true }
        assertTrue(ran, "post-dispose work must run inline, never be dropped")
    }
}
