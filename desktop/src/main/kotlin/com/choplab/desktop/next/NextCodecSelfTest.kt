package com.choplab.desktop.next

import com.choplab.jvm.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Synthetic, actual-tool acceptance. No provider, native endpoint or user profile is opened. */
object NextCodecSelfTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2) { "Use <temporary-directory> <ffmpeg>" }
        val directory = Files.createDirectories(Path.of(args[0]))
        val ffmpeg = Path.of(args[1])
        val input = directory.resolve("precision.wav")
        val samples = FloatArray(96_000 * 2) { (((it / 2 * 37L % 524_287) - 262_143) * if (it % 2 == 0) 3 else -1).toFloat() / 8_388_608 }
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, samples) }
        fun encode(path: Path, codec: String, extra: List<String> = emptyList(), source: Path = input) {
            val process = ProcessBuilder(listOf(ffmpeg.toString(), "-nostdin", "-v", "error", "-i", source.toString(), "-c:a", codec) +
                extra + path.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            try { check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) }
            finally { if (process.isAlive) process.destroyForcibly() }
        }
        val fixtures = listOf(Triple("precision.flac", "flac", listOf("-sample_fmt", "s32")),
            Triple("precision.mp3", "libmp3lame", emptyList()), Triple("precision.m4a", "aac", emptyList()),
            Triple("precision.aac", "aac", emptyList()), Triple("precision.ogg", "vorbis", listOf("-strict", "-2")),
            Triple("precision.opus", "libopus", emptyList()), Triple("lossless.m4a", "alac", emptyList()),
            Triple("precision.aiff", "pcm_s24be", emptyList()), Triple("precision.aif", "pcm_s24be", emptyList()),
            Triple("precision.mp4", "aac", emptyList()), Triple("precision.webm", "libopus", emptyList()))
        for ((name, codec, extra) in fixtures) {
            val path = directory.resolve(name); encode(path, codec, extra)
            DesktopOriginalAudioDecoder().use { decoder ->
                val hash = sha256(Files.readAllBytes(path))
                val started = System.nanoTime()
                val info = decoder.inspect(path, hash)
                val audio = decoder.decode(path, hash)
                check(info.sampleRate == 48_000 && info.channels == 2 && info.frames >= 96_000 && info.frames < 100_000)
                check(audio.samples.all(Float::isFinite) && audio.samples.any { it != 0f })
                if (codec == "flac" || codec == "alac" || codec == "pcm_s24be") check(audio.samples.contentEquals(samples)) { "Lossless precision/channel identity changed" }
                println("CODEC_PASS $name frames=${info.frames} milliseconds=${(System.nanoTime() - started) / 1_000_000}")
            }
        }
        val flac = directory.resolve("precision.flac")
        val receipt = NextSelfTest.run(directory.resolve("production"), flac)
        ZipFile(receipt.runDirectory.resolve("roundtrip.choplab").toFile()).use { archive ->
            val encoded = archive.entries().asSequence().single { it.name.endsWith(".flac") }
            check(archive.getInputStream(encoded).use { it.readBytes() }.contentEquals(Files.readAllBytes(flac)))
        }
        NextBackend.create(receipt.runDirectory.resolve("profile"), sinkFactory = { error("No endpoint in codec test") }).use { reopened ->
            val asset = reopened.studio.document.value.project.assets.single { it.extension == "flac" }
            check(reopened.assets.verified(asset) && reopened.loadPeaks(asset).any { it > 0f })
        }
        // Mono 44.1 kHz stays mono at source, then the shared resampler supplies exact ceil frames in stereo.
        val mono = directory.resolve("mono.wav")
        Files.newOutputStream(mono).use { WavCodec.writeFloat(it, FloatArray(44_101) { .12500012f }, 44_100, 1) }
        val monoFlac = directory.resolve("mono.flac"); encode(monoFlac, "flac", listOf("-sample_fmt", "s32"), mono)
        DesktopOriginalAudioDecoder().use { decoder ->
            val store = FileAssetStore(directory.resolve("mono-assets"), decoder = decoder)
            val asset = OriginalAudioImportPort(store, { monoFlac }, decoder).import(com.choplab.core.Location("mono"))
            check(asset.frames == 44_101L && asset.sampleRate == 44_100 && asset.channels == 1)
            WavPcmPort(store, decoder = decoder).use { port ->
                val pcm = port.load(asset)
                check(pcm.frameCount == 48_002 && pcm.sample(100, 0) == pcm.sample(100, 1))
            }
        }
        val broken = directory.resolve("broken.flac"); Files.writeString(broken, "not audio")
        DesktopOriginalAudioDecoder(maxResidentBytes = 48_000 * 8L).use { decoder ->
            check(runCatching { decoder.inspect(flac, sha256(Files.readAllBytes(flac))) }.exceptionOrNull() is IllegalArgumentException)
        }
        DesktopOriginalAudioDecoder(timeoutMillis = 1).use { decoder ->
            check(runCatching { decoder.inspect(flac, sha256(Files.readAllBytes(flac))) }.exceptionOrNull() is IllegalArgumentException)
        }
        DesktopOriginalAudioDecoder().use { decoder ->
            check(runCatching { decoder.inspect(broken, sha256(Files.readAllBytes(broken))) }.isFailure)
            var checks = 0
            check(runCatching { decoder.inspect(flac, sha256(Files.readAllBytes(flac))) { ++checks >= 4 } }.exceptionOrNull() is CancellationException)
            check(checks >= 4)
            check(decoder.inspect(flac, sha256(Files.readAllBytes(flac))).frames == 96_000L) // retry after cancellation
        }
        println("""{"status":"LOCAL_PASS","scope":"packaged-original-codecs","formats":11,"lossless24BitExact":true,"originalArchiveExact":true,"freshReopen":true,"cancelRetry":true,"nativeAudio":false}""")
    }
}
