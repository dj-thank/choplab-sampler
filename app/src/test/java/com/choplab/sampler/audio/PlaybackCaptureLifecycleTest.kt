package com.choplab.sampler.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCaptureLifecycleTest {
    @Test
    fun stopDuringStartupPreventsRecordingAndAllowsOnlyANewGeneration() {
        val lifecycle = PlaybackCaptureLifecycle()
        val first = requireNotNull(lifecycle.beginStart())

        assertTrue(lifecycle.requestStop(first))
        assertFalse(lifecycle.markRecording(first))
        assertNull(lifecycle.beginStart())
        assertTrue(lifecycle.finish(first))

        val second = lifecycle.beginStart()
        assertNotNull(second)
        assertFalse(lifecycle.finish(first))
        assertTrue(lifecycle.markRecording(requireNotNull(second)))
    }

    @Test
    fun blockedReadIsReleasedAndJoinedWithinTheBoundedStopContract() {
        val releaseRead = CountDownLatch(1)
        val worker = Thread { releaseRead.await() }.apply { start() }
        val stopCalled = AtomicBoolean(false)
        val releaseCalled = AtomicBoolean(false)

        try {
            val stopped = stopCaptureWorkerBounded(
                worker = worker,
                stopInput = { stopCalled.set(true) },
                releaseInput = {
                    releaseCalled.set(true)
                    releaseRead.countDown()
                },
                stopTimeoutMillis = 20L,
                releaseTimeoutMillis = 500L,
            )

            assertTrue(stopped)
            assertTrue(stopCalled.get())
            assertTrue(releaseCalled.get())
            assertFalse(worker.isAlive)
        } finally {
            releaseRead.countDown()
            worker.join(TimeUnit.SECONDS.toMillis(1))
        }
    }
}
