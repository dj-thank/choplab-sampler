package com.choplab.sampler.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

class SamplerStartupTest {
    private fun await(latch: CountDownLatch) {
        check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for startup test seam" }
    }

    @Test
    fun outputAndRecoveryOverlapOffCallerThreadAndGateReadiness() = runBlocking {
        val caller = Thread.currentThread()
        val opened = CountDownLatch(1)
        val loaded = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val audioThread = AtomicReference<Thread>()
        val recoveryThread = AtomicReference<Thread>()
        val stops = AtomicInteger()
        val ready = AtomicBoolean(false)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            prepareSamplerStartup(
                startAudio = {
                    audioThread.set(Thread.currentThread())
                    opened.countDown()
                    await(releaseOpen)
                },
                stopAudio = { stops.incrementAndGet() },
                loadProject = {
                    recoveryThread.set(Thread.currentThread())
                    loaded.countDown()
                    "recovered"
                },
            ).also { ready.set(true) }
        }
        try {
            await(opened)
            await(loaded)
            assertFalse(ready.get())
            assertNotEquals(caller, audioThread.get())
            assertNotEquals(caller, recoveryThread.get())
        } finally {
            releaseOpen.countDown()
        }
        assertEquals("recovered", withTimeout(5_000) { result.await() })
        assertTrue(ready.get())
        assertEquals(0, stops.get())
    }

    @Test
    fun cancellationDuringOpenClosesAfterTheLateOpenAndNeverBecomesReady() = runBlocking {
        val opening = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val outputAlive = AtomicBoolean(false)
        val ready = AtomicBoolean(false)
        val stops = AtomicInteger()
        val startup = launch(Dispatchers.Default) {
            prepareSamplerStartup(
                startAudio = {
                    opening.countDown()
                    await(releaseOpen)
                    outputAlive.set(true)
                },
                stopAudio = { outputAlive.set(false); stops.incrementAndGet() },
                loadProject = { Unit },
            )
            ready.set(true)
        }
        await(opening)
        startup.cancel()
        releaseOpen.countDown()
        withTimeout(5_000) { startup.join() }
        assertFalse(outputAlive.get())
        assertFalse(ready.get())
        assertEquals(1, stops.get())
    }

    @Test
    fun cancellationDuringRecoveryClosesAlreadyOpenedOutput() = runBlocking {
        val opened = CountDownLatch(1)
        val loading = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val outputAlive = AtomicBoolean(false)
        val startup = launch(Dispatchers.Default) {
            prepareSamplerStartup(
                startAudio = { outputAlive.set(true); opened.countDown() },
                stopAudio = { outputAlive.set(false) },
                loadProject = { loading.countDown(); await(releaseLoad) },
            )
        }
        await(opened)
        await(loading)
        startup.cancel()
        releaseLoad.countDown()
        withTimeout(5_000) { startup.join() }
        assertFalse(outputAlive.get())
    }

    @Test
    fun recoveryFailureWaitsForPendingOpenBeforeCleanup() = runBlocking {
        supervisorScope {
            val opening = CountDownLatch(1)
            val releaseOpen = CountDownLatch(1)
            val outputAlive = AtomicBoolean(false)
            val stops = AtomicInteger()
            val result = async(Dispatchers.Default) {
                runCatching {
                    prepareSamplerStartup(
                        startAudio = { opening.countDown(); await(releaseOpen); outputAlive.set(true) },
                        stopAudio = { outputAlive.set(false); stops.incrementAndGet() },
                        loadProject = { await(opening); error("recovery failed") },
                    )
                }
            }
            await(opening)
            releaseOpen.countDown()
            assertEquals("recovery failed", withTimeout(5_000) { result.await() }.exceptionOrNull()?.message)
            assertFalse(outputAlive.get())
            assertEquals(1, stops.get())
        }
    }

    @Test
    fun failedRecoveryResultIsReturnedUnchangedForExistingRecoveryPolicy() = runBlocking {
        val failure = IllegalStateException("corrupt archive")
        var stops = 0
        val result = prepareSamplerStartup(
            startAudio = {},
            stopAudio = { stops++ },
            loadProject = { Result.failure<String>(failure) },
        )
        assertEquals(failure, result.exceptionOrNull())
        assertEquals(0, stops)
    }
}
