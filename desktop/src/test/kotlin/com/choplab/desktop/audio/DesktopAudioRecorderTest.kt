package com.choplab.desktop.audio

import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAudioRecorderTest {
    @Test
    fun stopDuringStartupPreventsLateWorkerPublication() {
        val calls = mutableMapOf<String, Int>()
        val startEntered = CountDownLatch(1)
        val allowStartToReturn = CountDownLatch(1)
        val line = Proxy.newProxyInstance(
            DesktopAudioRecorderTest::class.java.classLoader,
            arrayOf(TargetDataLine::class.java),
        ) { _, method, _ ->
            calls[method.name] = calls.getOrDefault(method.name, 0) + 1
            when (method.name) {
                "open" -> null
                "start" -> {
                    startEntered.countDown()
                    check(allowStartToReturn.await(2, TimeUnit.SECONDS)) { "test start gate timed out" }
                    null
                }
                "close" -> null
                else -> error("Unexpected TargetDataLine call: ${method.name}")
            }
        } as TargetDataLine
        val recorder = DesktopTargetLineRecorder(
            lineFactory = {
                DesktopCaptureLine(
                    line = line,
                    format = AudioFormat(48_000f, 16, 1, true, false),
                )
            },
            threadName = "DesktopAudioRecorderStartupCancellationTest",
        )
        val directory = Files.createTempDirectory("choplab-recorder-cancel-test").toFile()
        val output = directory.resolve("capture.wav").apply { writeText("partial") }
        val startResult = AtomicReference<Result<Unit>>()
        val starter = Thread { startResult.set(recorder.start(output)) }

        try {
            starter.start()
            assertTrue(startEntered.await(2, TimeUnit.SECONDS))

            val stopResult = recorder.stop()
            allowStartToReturn.countDown()
            starter.join(TimeUnit.SECONDS.toMillis(2))

            val result = checkNotNull(startResult.get())
            assertFalse(starter.isAlive)
            assertTrue(stopResult.isFailure)
            assertTrue(result.isFailure)
            assertEquals("録音の開始はキャンセルされました", result.exceptionOrNull()?.message)
            assertEquals(1, calls["open"])
            assertEquals(1, calls["start"])
            assertEquals(1, calls["close"])
            assertEquals(0, calls.getOrDefault("read", 0))
            assertFalse(recorder.isRecording)
            assertFalse(output.exists())
        } finally {
            allowStartToReturn.countDown()
            starter.join(TimeUnit.SECONDS.toMillis(2))
            recorder.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun startFailureClosesAcquiredLineAndClearsTemporaryState() {
        val calls = mutableMapOf<String, Int>()
        val line = Proxy.newProxyInstance(
            DesktopAudioRecorderTest::class.java.classLoader,
            arrayOf(TargetDataLine::class.java),
        ) { _, method, _ ->
            calls[method.name] = calls.getOrDefault(method.name, 0) + 1
            when (method.name) {
                "open" -> null
                "start" -> error("test start failure")
                "close" -> null
                else -> error("Unexpected TargetDataLine call: ${method.name}")
            }
        } as TargetDataLine
        val recorder = DesktopTargetLineRecorder(
            lineFactory = {
                DesktopCaptureLine(
                    line = line,
                    format = AudioFormat(48_000f, 16, 1, true, false),
                )
            },
            threadName = "DesktopAudioRecorderTest",
        )
        val directory = Files.createTempDirectory("choplab-recorder-test").toFile()
        val output = directory.resolve("capture.wav").apply { writeText("partial") }

        try {
            val result = recorder.start(output)

            assertTrue(result.isFailure)
            assertEquals("test start failure", result.exceptionOrNull()?.message)
            assertEquals(1, calls["open"])
            assertEquals(1, calls["start"])
            assertEquals(1, calls["close"])
            assertFalse(recorder.isRecording)
            assertFalse(output.exists())

            assertTrue(recorder.stop().isFailure)
            recorder.close()
            assertEquals(1, calls["close"])
        } finally {
            directory.deleteRecursively()
        }
    }
}
