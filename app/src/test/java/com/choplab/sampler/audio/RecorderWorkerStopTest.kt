package com.choplab.sampler.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import android.media.AudioRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderWorkerStopTest {
    @Test
    fun stopDuringRecorderInitializationPreventsLateWorkerStart() {
        val creationEntered = CountDownLatch(1)
        val allowCreation = CountDownLatch(1)
        val startCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)
        val fakeInput = object : RecorderInput {
            override val recordingState: Int = AudioRecord.RECORDSTATE_RECORDING
            override fun startRecording() { startCalls.incrementAndGet() }
            override fun read(buffer: ShortArray): Int = 0
            override fun stop() = Unit
            override fun release() { releaseCalls.incrementAndGet() }
        }
        val recorder = MicrophoneRecorder(RecorderInputFactory {
            creationEntered.countDown()
            allowCreation.await(1, TimeUnit.SECONDS)
            fakeInput
        })
        val output = Files.createTempFile("choplab-mic-start-race", ".wav").toFile()
        val startResult = AtomicReference<Result<Unit>>()
        val stopResult = AtomicReference<Result<java.io.File>>()
        val startThread = Thread { startResult.set(recorder.start(output)) }.apply { start() }

        try {
            assertTrue(creationEntered.await(1, TimeUnit.SECONDS))
            val stopThread = Thread { stopResult.set(recorder.stop()) }.apply { start() }
            val stopObservedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (recorder.isRecording && System.nanoTime() < stopObservedDeadline) {
                Thread.yield()
            }
            assertFalse("stop must publish the stopped state before initialization completes", recorder.isRecording)
            allowCreation.countDown()
            startThread.join(1_000L)
            stopThread.join(1_000L)

            assertTrue(startResult.get().isFailure)
            assertTrue(stopResult.get().isFailure)
            assertFalse(recorder.isRecording)
            assertEquals(0, startCalls.get())
            assertEquals(1, releaseCalls.get())
            assertFalse("cancelled startup must not retain its app-owned output", output.exists())
        } finally {
            allowCreation.countDown()
            startThread.join(1_000L)
            output.delete()
        }
    }

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
