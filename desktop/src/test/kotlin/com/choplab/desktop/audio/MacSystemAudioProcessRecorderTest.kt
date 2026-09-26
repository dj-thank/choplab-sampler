package com.choplab.desktop.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Helper-process recorder contracts with a portable fake helper; not real ScreenCaptureKit capture. */
class MacSystemAudioProcessRecorderTest {
    private val helper = File("fake-choplab-sck-audio")
    private fun output() = kotlin.io.path.createTempFile("choplab-system-audio", ".wav").toFile().apply { delete() }
    private fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) { check(System.currentTimeMillis() < deadline) { "Timed out" }; Thread.sleep(10) }
    }

    @Test fun recordsFramesSplitAcrossReadsIntactUntilStop() {
        val recorder = MacSystemAudioProcessRecorder(helper, FakeSystemAudioHelper.launcher("normal"))
        val file = output()
        try {
            assertTrue(recorder.start(file).isSuccess)
            Thread.sleep(400)
            val stopped = recorder.stop()
            assertTrue(stopped.isSuccess, stopped.exceptionOrNull()?.toString())
            val data = ByteBuffer.wrap(file.readBytes(), 44, (file.length() - 44).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            val frames = data.remaining() / 4
            assertTrue(frames > 4_800, "only $frames frames")
            // Every frame keeps its left/right pair and order even though the helper split them.
            repeat(frames) { index ->
                val left = data.short.toInt(); val right = data.short.toInt()
                assertEquals(FakeSystemAudioHelper.pattern(index), left, "left of frame $index")
                assertEquals(-left, right, "right of frame $index")
            }
        } finally { recorder.close(); file.delete() }
    }

    @Test fun captureThatEndsBeforeStopIsReportedInsteadOfSaved() {
        val recorder = MacSystemAudioProcessRecorder(helper, FakeSystemAudioHelper.launcher("dies"))
        val file = output()
        try {
            assertTrue(recorder.start(file).isSuccess)
            waitUntil { !recorder.isRecording }
            val stopped = recorder.stop()
            assertTrue(stopped.isFailure)
            assertTrue("途中で止まりました" in stopped.exceptionOrNull()?.message.orEmpty(), stopped.exceptionOrNull()?.message)
        } finally { recorder.close(); file.delete() }
    }

    @Test fun slowPermissionAnswerDoesNotHoldTheCaller() {
        val recorder = MacSystemAudioProcessRecorder(helper, FakeSystemAudioHelper.launcher("late", "1500"), quickStartMillis = 200)
        val file = output()
        try {
            val began = System.nanoTime()
            assertTrue(recorder.start(file).isSuccess)
            assertTrue((System.nanoTime() - began) / 1_000_000 < 1_200, "start blocked the caller")
            Thread.sleep(2_200)
            val stopped = recorder.stop()
            assertTrue(stopped.isSuccess, stopped.exceptionOrNull()?.toString())
            assertTrue(file.length() > 44 + 4 * 4_800)
        } finally { recorder.close(); file.delete() }
    }

    @Test fun stopBeforeTheHelperAnswersSaysCaptureNeverStarted() {
        val recorder = MacSystemAudioProcessRecorder(helper, FakeSystemAudioHelper.launcher("late", "10000"), quickStartMillis = 100)
        val file = output()
        try {
            assertTrue(recorder.start(file).isSuccess)
            val stopped = recorder.stop()
            assertTrue(stopped.isFailure)
            assertTrue("開始できませんでした" in stopped.exceptionOrNull()?.message.orEmpty(), stopped.exceptionOrNull()?.message)
            assertTrue(!recorder.isRecording)
        } finally { recorder.close(); file.delete() }
    }

    @Test fun refusedPermissionFailsAtStart() {
        val recorder = MacSystemAudioProcessRecorder(helper, FakeSystemAudioHelper.launcher("refused"))
        val file = output()
        try {
            val started = recorder.start(file)
            assertTrue(started.isFailure)
            assertTrue("許可" in started.exceptionOrNull()?.message.orEmpty(), started.exceptionOrNull()?.message)
            assertTrue(!recorder.isRecording && !file.exists())
        } finally { recorder.close(); file.delete() }
    }

    @Test fun missingDisplayExplainsUnlockWithoutRequestingAnotherPermission() {
        val recorder = MacSystemAudioProcessRecorder(helper, FakeSystemAudioHelper.launcher("no-display"))
        val file = output()
        try {
            val started = recorder.start(file)
            assertTrue(started.isFailure)
            val message = started.exceptionOrNull()?.message.orEmpty()
            assertTrue("画面ロックを解除" in message && "もう一度録音" in message, message)
            assertTrue("許可" !in message && "NO_DISPLAY" !in message, message)
            assertTrue(!recorder.isRecording && !file.exists())
        } finally { recorder.close(); file.delete() }
    }

    @Test fun loopbackEnabledAfterLaunchIsUsedWithoutRestart() {
        var enabled = false
        var starts = 0
        val loopback = object : DesktopAudioRecorder {
            override var isRecording = false
            override fun start(file: File): Result<Unit> { starts++; isRecording = true; return Result.success(Unit) }
            override fun stop(): Result<File> { isRecording = false; return Result.failure(IllegalStateException("empty")) }
            override fun close() = Unit
        }
        val recorder = DesktopSystemAudioRecorder({ enabled }, { null }, macOs = false, { loopback }, { error("Windows has no helper") })
        val file = output()
        val missing = recorder.start(file)
        assertTrue(missing.isFailure)
        assertTrue("ステレオ ミキサー" in missing.exceptionOrNull()?.message.orEmpty())
        // Stereo Mix switched on while the app keeps running.
        enabled = true
        assertTrue(recorder.start(file).isSuccess)
        assertEquals(1, starts)
        assertTrue(recorder.isRecording)
        recorder.stop()
    }
}
