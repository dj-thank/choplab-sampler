package com.choplab.sampler.separation

import com.choplab.sampler.model.PcmAudio
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrumSeparationServiceTest {
    private class CountingBackend : ChunkInference, AutoCloseable {
        val closed = AtomicInteger()
        override fun infer(chunk: FloatArray): FloatArray {
            val segment = SeparatorSpec.SEGMENT_SAMPLES
            return FloatArray(4 * 2 * segment).also { chunk.copyInto(it, SeparatorSpec.DRUM_STEM_INDEX * 2 * segment) }
        }
        override fun close() { closed.incrementAndGet() }
    }

    private fun wavDataBytes(file: File): Long = RandomAccessFile(file, "r").use { wav ->
        wav.seek(40)
        val b = ByteArray(4)
        wav.readFully(b)
        (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)
    }

    @Test
    fun inMemorySourceWithProvidedModelStreamsAStereoStemAndReleasesTheSession() {
        val work = Files.createTempDirectory("separation-service").toFile()
        val model = work.resolve("model.onnx").apply { writeBytes(byteArrayOf(1)) }
        val backend = CountingBackend()
        val modelProgress = AtomicInteger()
        val done = CountDownLatch(1)
        val result = AtomicReference<File?>()
        val error = AtomicReference<String?>()
        val audio = PcmAudio(name = "loop", samples = ShortArray(48_000) { (it % 200 - 100).toShort() }, sampleRate = 48_000, channelCount = 1)
        val service = DrumSeparationService(
            modelsDir = work.resolve("unused"),
            decode = { error("in-memory source must not be decoded") },
            backendFactory = { file -> assertEquals(model, file); backend },
            modelProvider = { progress, _ -> progress(1f); modelProgress.incrementAndGet(); model },
            releaseBackendAfterJob = true,
        )
        try {
            assertTrue(service.separate(DrumSeparationService.Request(
                sourceAudio = audio,
                outputFile = work.resolve("out/loop-drums.wav"),
                onDone = { result.set(it); done.countDown() },
                onError = { error.set(it); done.countDown() },
                onCancelled = { error.set("cancelled"); done.countDown() },
            )))
            assertTrue(done.await(30, TimeUnit.SECONDS))
            assertEquals(null, error.get())
            val stem = requireNotNull(result.get())
            val frames = SeparatorSourceReader(audio).frames
            assertEquals(frames * 2L * Short.SIZE_BYTES, wavDataBytes(stem))
            assertEquals(1, modelProgress.get())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (backend.closed.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, backend.closed.get())
            assertFalse(service.isRunning)
        } finally {
            service.close()
            work.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun requestNeedsExactlyOneSource() {
        DrumSeparationService.Request(outputFile = File("x.wav"))
    }
}
