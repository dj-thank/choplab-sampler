package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import com.choplab.jvm.WavCodec
import com.choplab.jvm.sha256
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

data class NextSelfTestReceipt(
    val runDirectory: Path,
    val projectHash: String,
    val wav16Hash: String,
    val wav24Hash: String,
    val exportFrames: Int,
    val savedRevision: Long,
    val renderedFrames: Long,
    val leftEnergy: Double,
    val rightEnergy: Double,
) {
    /** No machine/user paths in the console receipt. Files live in caller-selected runDirectory. */
    fun json() = """{"status":"LOCAL_PASS","scope":"headless-file-studio-engine","projectSha256":"$projectHash","wav16Sha256":"$wav16Hash","wav24Sha256":"$wav24Hash","exportFrames":$exportFrames,"savedRevision":$savedRevision,"renderedFrames":$renderedFrames,"leftEnergy":$leftEnergy,"rightEnergy":$rightEnergy,"nativeAudio":false,"gui":false}"""
}

/** No native audio/window APIs are invoked in this entry point, including error paths. */
object NextSelfTest {
    @JvmStatic fun main(args: Array<String>) {
        val index = args.indexOf("--self-test")
        require(index >= 0 && index + 1 < args.size) { "Use --self-test <temporary-directory>" }
        println(runBlocking { run(Path.of(args[index + 1])).json() })
    }

    suspend fun run(directory: Path): NextSelfTestReceipt {
        Files.createDirectories(directory)
        val run = Files.createDirectory(directory.resolve("next-self-test-${UUID.randomUUID()}"))
        val input = run.resolve("Demo.wav")
        writeDemo(input)
        val sink = CountingTestSink()
        val backend = NextBackend.create(run.resolve("profile"), sinkFactory = { sink })
        try {
            withTimeout(5_000) { while (backend.engine.status.value.phase != DriverPhase.ATTACHED) delay(5) }
            check(backend.importAudio(input).accepted)
            await(backend) { it.source != null }
            val studio = backend.studio
            suspend fun edit(intent: Intent) { check(studio.dispatch(Action.Edit(intent)).accepted) }
            edit(Intent.SetSourceRange(FrameRange(4_800, 43_200)))
            edit(Intent.EqualChop(4))
            edit(Intent.AssignSlice(0, 0))
            edit(Intent.AssignSlice(1, 1))
            edit(Intent.SetTempo(Tempo(100_000)))
            val oldPattern = studio.document.value.project.patterns.first()
            edit(Intent.PutPattern(oldPattern.copy(bars = 2)))
            for (step in listOf(0, 4, 8, 12, 16, 20, 24, 28)) edit(Intent.SetNote(oldPattern.id, Note(step * 240, step / 4 % 2), true))
            check(backend.loadPeaks(studio.document.value.project.assets.first()).any { it > 0f })
            val saved = studio.document.value.project
            val project = run.resolve("roundtrip.choplab")
            check(backend.saveProject(project).accepted)
            await(backend) { Files.isRegularFile(project) && studio.document.value.savedRevision == studio.document.value.revision }
            val savedRevision = studio.document.value.revision
            edit(Intent.SetTempo(Tempo(110_000)))
            check(studio.dispatch(Action.Undo).accepted)
            check(studio.document.value.project == saved)
            check(studio.dispatch(Action.Redo).accepted)
            check(studio.document.value.project.tempo.milliBpm == 110_000)
            check(studio.dispatch(Action.Undo).accepted)
            check(backend.openProject(project).accepted)
            await(backend) { it == saved }
            val frames = NextBackend.patternFrames(saved, saved.patterns.first())
            val out16 = run.resolve("mix-16.wav")
            val out24 = run.resolve("mix-24.wav")
            check(backend.exportPattern(out16, 16).accepted)
            await(backend) { Files.isRegularFile(out16) }
            check(backend.exportPattern(out24, 24).accepted)
            await(backend) { Files.isRegularFile(out24) }
            for ((file, bits) in listOf(out16 to 16, out24 to 24)) {
                val wave = Files.newInputStream(file).use { WavCodec.read(it) }
                check(wave.info.frames == frames.toLong() && wave.info.bits == bits && wave.info.channels == 2 && wave.info.sampleRate == 48_000)
                check(wave.samples.any { abs(it) > .01f })
                check(wave.samples.indices.step(2).any { abs(wave.samples[it] - wave.samples[it + 1]) > .01f })
            }
            check(studio.dispatch(Action.Play).accepted)
            withTimeout(5_000) { while (sink.leftEnergy < 1.0 || sink.rightEnergy < .1) delay(10) }
            check(studio.dispatch(Action.Stop).accepted)
            withTimeout(5_000) { while (backend.engine.snapshot().activeVoices != 0) delay(5) }
            check(!backend.engine.snapshot().playing)
            val receipt = NextSelfTestReceipt(run, sha256(Files.readAllBytes(project)), sha256(Files.readAllBytes(out16)),
                sha256(Files.readAllBytes(out24)), frames, savedRevision, sink.frames, sink.leftEnergy, sink.rightEnergy)
            Files.writeString(run.resolve("receipt.json"), receipt.json() + "\n", Charsets.UTF_8)
            return receipt
        } finally { backend.shutdown() }
    }

    private suspend fun await(backend: NextBackend, condition: (Project) -> Boolean) = withTimeout(15_000) {
        while (backend.studio.work.value.jobId != null || !condition(backend.studio.document.value.project)) delay(10)
    }

    fun writeDemo(path: Path) {
        val frames = 48_000
        val samples = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val t = frame / 48_000.0
            val fade = minOf(frame / 240.0, (frames - 1 - frame) / 240.0, 1.0).coerceAtLeast(0.0)
            val pulse = .35 + .65 * abs(sin(4 * PI * t))
            samples[frame * 2] = (sin(2 * PI * 220 * t) * .36 * fade * pulse).toFloat()
            samples[frame * 2 + 1] = (sin(2 * PI * 659 * t) * .14 * fade * pulse).toFloat()
        }
        Files.newOutputStream(path).use { WavCodec.writeFloat(it, samples) }
    }
}

/** Headless, paced fake endpoint. Counts asymmetric stereo, never touches AudioSystem. */
internal class CountingTestSink : AudioSink {
    override val encoding = SinkEncoding.FLOAT32
    @Volatile var frames = 0L
        private set
    @Volatile var leftEnergy = 0.0
        private set
    @Volatile var rightEnergy = 0.0
        private set
    @Volatile var closed = false
        private set
    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
        check(!closed && length % 8 == 0)
        fun sample(at: Int): Float = Float.fromBits((bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
            ((bytes[at + 2].toInt() and 255) shl 16) or (bytes[at + 3].toInt() shl 24))
        var left = leftEnergy; var right = rightEnergy
        for (at in offset until offset + length step 8) {
            val a = sample(at); val b = sample(at + 4)
            check(a.isFinite() && b.isFinite())
            left += a.toDouble() * a; right += b.toDouble() * b
        }
        leftEnergy = left; rightEnergy = right; frames += length / 8
        LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000)
        return length
    }
    override fun close() { closed = true }
}
