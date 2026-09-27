package com.choplab.desktop.next

import com.choplab.jvm.WavAudio
import com.choplab.jvm.WavCodec
import com.choplab.jvm.WavInfo
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NextSeparationTest {
    private fun source() = WavAudio(WavInfo(44_100, 2, 2, 32, true), floatArrayOf(.000001f, -.000002f, 1.25f, -1.5f))
    private fun idle(worker: NextSeparation) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (worker.state.value.busy) { check(System.nanoTime() < end); Thread.sleep(5) }
    }
    @Test fun resultPreservesFloatBytesAndTitleAndFailureDoesNotSelect() {
        val root = Files.createTempDirectory("next-separation-job-")
        try {
            NextSeparation(root, {}, { source() }, { audio, output, progress, _ ->
                Files.newOutputStream(output).use { WavCodec.writeFloat(it, audio.samples, 44_100) }
                progress(.5f)
            }, "ドラム").use { worker ->
                assertNull(worker.state.value.result)
                assertTrue(worker.start()); idle(worker)
                val result = assertNotNull(worker.state.value.result)
                assertEquals("ドラム", result.title)
                assertContentEquals(source().samples, Files.newInputStream(result.path).use { WavCodec.read(it) }.samples)
                assertEquals(NextSeparation.Status.DONE, worker.state.value.status)
            }
            NextSeparation(root, {}, { error("Decode failed") }, { _, _, _, _ -> error("Must not render") }, "x").use { worker ->
                assertTrue(worker.start()); idle(worker)
                assertEquals(NextSeparation.Status.FAILED, worker.state.value.status)
                assertNull(worker.state.value.result)
            }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun cancellationRejectsLateResultAndKeepsBusyUntilNativeJobActuallyReleases() {
        val root = Files.createTempDirectory("next-separation-job-")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            NextSeparation(root, {}, { source() }, { audio, output, _, _ ->
                entered.countDown()
                while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
                Files.newOutputStream(output).use { WavCodec.writeFloat(it, audio.samples, 44_100) }
            }, "x").use { worker ->
                assertTrue(worker.start()); assertTrue(entered.await(5, TimeUnit.SECONDS))
                worker.cancel(); assertTrue(worker.state.value.busy); assertFalse(worker.start())
                release.countDown(); idle(worker)
                assertEquals(NextSeparation.Status.CANCELLED, worker.state.value.status)
                assertNull(worker.state.value.result)
                assertTrue(worker.start()); idle(worker)
                assertEquals(NextSeparation.Status.DONE, worker.state.value.status)
            }
        } finally { release.countDown(); root.toFile().deleteRecursively() }
    }
}
