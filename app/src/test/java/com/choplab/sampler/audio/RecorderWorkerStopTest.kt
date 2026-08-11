package com.choplab.sampler.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderWorkerStopTest {
    @Test
    fun liveWorkerAfterTimeoutIsNotReportedAsAFinishedRecording() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            entered.countDown()
            release.await()
        }.apply { start() }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(awaitRecorderWorker(worker, timeoutMillis = 5L))
        } finally {
            release.countDown()
            worker.join(1_000L)
        }
    }

    @Test
    fun completedWorkerIsSafeToDecode() {
        val worker = Thread {}.apply { start() }

        assertTrue(awaitRecorderWorker(worker, timeoutMillis = 1_000L))
    }
}
