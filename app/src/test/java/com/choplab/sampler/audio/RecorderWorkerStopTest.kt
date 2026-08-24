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
        val startupFailure = AtomicReference<String>()
        val failureObserved = CountDownLatch(1)
        val stopResult = AtomicReference<Result<java.io.File>>()
        val startResult = recorder.start(
            file = output,
            onFailure = { message ->
                startupFailure.set(message)
                failureObserved.countDown()
            },
        )

        try {
            assertTrue("start must return after admitting background startup", startResult.isSuccess)
            assertTrue(creationEntered.await(1, TimeUnit.SECONDS))
            val stopThread = Thread { stopResult.set(recorder.stop()) }.apply { start() }
            val stopObservedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (recorder.isRecording && System.nanoTime() < stopObservedDeadline) {
                Thread.yield()
            }
            assertFalse("stop must publish the stopped state before initialization completes", recorder.isRecording)
            allowCreation.countDown()
            stopThread.join(1_000L)

            assertTrue(failureObserved.await(1, TimeUnit.SECONDS))
            assertTrue(startupFailure.get().contains("キャンセル"))
            assertTrue(stopResult.get().isFailure)
            assertFalse(recorder.isRecording)
            assertEquals(0, startCalls.get())
            assertEquals(1, releaseCalls.get())
            assertFalse("cancelled startup must not retain its app-owned output", output.exists())
        } finally {
            allowCreation.countDown()
            output.delete()
        }
    }

    @Test
    fun stopDuringBlockedRecorderStartReleasesPendingInputAndPreventsWorker() {
        val startEntered = CountDownLatch(1)
        val releaseEntered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val finishStarting = CountDownLatch(1)
        val creationCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)
        val readCalls = AtomicInteger(0)
        val fakeInput = object : RecorderInput {
            override val recordingState: Int = AudioRecord.RECORDSTATE_RECORDING

            override fun startRecording() {
                startEntered.countDown()
                finishStarting.await()
            }

            override fun read(buffer: ShortArray): Int {
                readCalls.incrementAndGet()
                return 0
            }

            override fun stop() {
                stopCalls.incrementAndGet()
            }

            override fun release() {
                releaseCalls.incrementAndGet()
                releaseEntered.countDown()
                allowRelease.await()
            }
        }
        val recorder = MicrophoneRecorder(RecorderInputFactory {
            creationCalls.incrementAndGet()
            fakeInput
        })
        val output = Files.createTempFile("choplab-mic-blocked-start", ".wav").toFile()
        val replacementOutput = Files.createTempFile("choplab-mic-replacement", ".wav").toFile()
        val startupFailure = AtomicReference<String>()
        val failureObserved = CountDownLatch(1)
        val stopResult = AtomicReference<Result<java.io.File>>()
        val startResult = recorder.start(
            file = output,
            onFailure = { message ->
                startupFailure.set(message)
                failureObserved.countDown()
            },
        )
        var stopThread: Thread? = null

        try {
            assertTrue("start must return while native startup is blocked", startResult.isSuccess)
            assertTrue(startEntered.await(1, TimeUnit.SECONDS))
            stopThread = Thread { stopResult.set(recorder.stop()) }.apply { start() }

            assertTrue(
                "stop must hand release to a cancellation thread without waiting for native start",
                releaseEntered.await(1, TimeUnit.SECONDS),
            )
            assertEquals("STOP must not enter the framework stop lock during startup", 0, stopCalls.get())
            val replacementResult = recorder.start(replacementOutput)
            assertTrue("replacement start must be rejected while cancellation unwinds", replacementResult.isFailure)
            assertEquals(1, creationCalls.get())

            finishStarting.countDown()
            stopThread.join(1_000L)

            assertFalse(stopThread.isAlive)
            assertTrue("STOP must finish while release remains blocked", failureObserved.await(1, TimeUnit.SECONDS))
            assertTrue(startupFailure.get().contains("キャンセル"))
            assertTrue(stopResult.get().isFailure)
            assertFalse(recorder.isRecording)
            assertEquals(0, stopCalls.get())
            assertEquals(1, releaseCalls.get())
            assertEquals(0, readCalls.get())
            assertFalse("cancelled startup must delete its app-owned output", output.exists())
        } finally {
            finishStarting.countDown()
            allowRelease.countDown()
            stopThread?.join(1_000L)
            output.delete()
            replacementOutput.delete()
        }
    }

    @Test
    fun stopTimeoutDoesNotWaitForBlockedNativeStartOrRelease() {
        val startEntered = CountDownLatch(1)
        val releaseEntered = CountDownLatch(1)
        val allowStart = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val stopCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)
        val fakeInput = object : RecorderInput {
            override val recordingState: Int = AudioRecord.RECORDSTATE_RECORDING

            override fun startRecording() {
                startEntered.countDown()
                allowStart.await()
            }

            override fun read(buffer: ShortArray): Int = 0

            override fun stop() {
                stopCalls.incrementAndGet()
            }

            override fun release() {
                releaseCalls.incrementAndGet()
                releaseEntered.countDown()
                allowRelease.await()
            }
        }
        val recorder = MicrophoneRecorder(
            inputFactory = RecorderInputFactory { fakeInput },
            startupStopTimeoutMillis = 25L,
        )
        val output = Files.createTempFile("choplab-mic-start-timeout", ".wav").toFile()
        val replacementOutput = Files.createTempFile("choplab-mic-timeout-replacement", ".wav").toFile()
        val stopResult = AtomicReference<Result<java.io.File>>()
        var stopThread: Thread? = null

        try {
            assertTrue(recorder.start(output).isSuccess)
            assertTrue(startEntered.await(1, TimeUnit.SECONDS))
            stopThread = Thread { stopResult.set(recorder.stop()) }.apply { start() }
            assertTrue(releaseEntered.await(1, TimeUnit.SECONDS))

            stopThread.join(1_000L)

            assertFalse("STOP must honor its startup timeout", stopThread.isAlive)
            assertTrue(stopResult.get().isFailure)
            assertTrue(stopResult.get().exceptionOrNull()?.message.orEmpty().contains("開始取消"))
            assertFalse(recorder.isRecording)
            assertEquals(0, stopCalls.get())
            assertEquals(1, releaseCalls.get())
            assertTrue(
                "a replacement must remain fail-closed until blocked startup unwinds",
                recorder.start(replacementOutput).isFailure,
            )
        } finally {
            allowStart.countDown()
            allowRelease.countDown()
            stopThread?.join(1_000L)
            output.delete()
            replacementOutput.delete()
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
