package com.choplab.desktop.next

import com.choplab.core.model.FrameRange
import com.choplab.jvm.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Explicit actual-tool run; normal CI remains independent of a separately installed codec binary. */
class LongOriginalAudioDecoderTest {
    @Test fun fourHundredSecondFlacUsesOriginalBytesAndBoundedNativeDecodeInTheRealDesktopBackend() = runBlocking<Unit> {
        assumeTrue(System.getenv("CHOPLAB_LONG_CODEC_TEST") == "1", "Actual local codecs require explicit opt-in")
        val ffmpeg = Path.of(requireNotNull(System.getenv("CHOPLAB_TEST_FFMPEG")))
        val root = Files.createTempDirectory("long-codec-")
        try {
            val frames = 400 * 48_000
            val input = root.resolve("source.wav")
            fun sample(frame: Int, channel: Int) = if (channel == 0) (32_001 + frame % 97) / 8_388_608f else -(125_001 + frame % 137) / 8_388_608f
            Files.newOutputStream(input).use { out ->
                val writer = WavCodec.FloatWriter(out, frames.toLong())
                val buffer = FloatArray(4096 * 2)
                var first = 0
                while (first < frames) {
                    val count = minOf(4096, frames - first)
                    for (i in 0 until count * 2) buffer[i] = sample(first + i / 2, i % 2)
                    writer.write(buffer, frameCount = count); first += count
                }
                writer.finish()
            }
            val flac = root.resolve("source.flac")
            val process = ProcessBuilder(ffmpeg.toString(), "-nostdin", "-v", "error", "-i", input.toString(), "-c:a", "flac",
                "-sample_fmt", "s32", flac.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            try { assertTrue(process.waitFor(60, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()) }
            finally { if (process.isAlive) process.destroyForcibly() }
            DesktopOriginalAudioDecoder().use { decoder ->
                val info = decoder.inspect(flac, "test-handoff")
                assertEquals(frames.toLong(), info.frames)
                decoder.openPcm(flac, "test-handoff").use { source ->
                    val position = 390 * 48_000 + 17
                    val actual = source.read(position, 4096)
                    for (i in actual.indices) assertEquals(sample(position + i / 2, i % 2).toRawBits(), actual[i].toRawBits())
                }
            }
            NextBackend.create(root.resolve("profile"), sinkFactory = { error("No endpoint in synthetic codec run") }).use { backend ->
                assertTrue(backend.importAudio(flac).accepted)
                withTimeout(30_000) { while (backend.studio.work.value.jobId != null || backend.studio.work.value.preparationId != null) delay(5) }
                val asset = backend.studio.document.value.project.assets.single()
                assertEquals(frames.toLong(), asset.frames)
                assertEquals("flac", asset.extension)
                assertEquals(Files.size(flac), asset.byteCount)
                assertEquals(sha256(Files.readAllBytes(flac)), asset.hash)
                assertTrue(backend.loadPeaks(asset).any { it > 0 })
                val pad = com.choplab.core.model.Pad(0, asset.hash, FrameRange(375L * 48_000, 375L * 48_000 + 4096), reverse = true)
                val rendered = backend.renderPad(pad, asset)
                assertEquals(4096, rendered.frames)
                val audio = backend.assets.openVerified(rendered).use(WavCodec::read)
                for (i in audio.samples.indices) assertEquals(sample((pad.range!!.end - 1 - i / 2).toInt(), i % 2), audio.samples[i])
                val archive = root.resolve("saved.choplab")
                assertTrue(backend.saveProject(archive).accepted)
                withTimeout(30_000) { while (backend.studio.work.value.jobId != null) delay(5) }
                assertTrue(Files.size(archive) > 0)
            }
            NextBackend.create(root.resolve("profile"), sinkFactory = { error("No endpoint in synthetic codec run") }).use { backend ->
                assertEquals(frames.toLong(), backend.studio.document.value.project.assets.single().frames)
                assertTrue(backend.loadPeaks(backend.studio.document.value.project.assets.single(), 64).any { it > 0 })
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
