package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.jvm.MicInput
import com.choplab.jvm.WavCodec
import com.choplab.ui.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.sin

/** Production presenter/ports, float capture, chop, arrangement export and restart with a synthetic input. */
object NextMicrophoneSelfTest {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Provide an isolated evidence directory" }
        runBlocking { run(Path.of(args[0])) }
        println("""{"status":"LOCAL_PASS","scope":"microphone-source-production","sourceRate":44100,"sourceFrames":44100,"exportFrames":48000,"originalSamplesExact":true,"nativeMicrophone":false,"humanAcceptance":false}""")
    }

    suspend fun run(directory: Path) {
        Files.createDirectories(directory)
        val profile = directory.resolve("profile")
        check(!Files.exists(profile)) { "Use a fresh isolated profile" }
        val samples = FloatArray(44_100) { (.13 * sin(it * 2 * Math.PI * 440 / 44_100)).toFloat() + .000_001f }
        val mic = object : MicInput {
            override val sampleRate = 44_100
            private var position = 0
            @Volatile var stopped = false
            @Volatile var closed = false
            override fun read(buffer: FloatArray): Int {
                if (position < samples.size) {
                    val count = minOf(buffer.size, samples.size - position)
                    samples.copyInto(buffer, endIndex = position + count, startIndex = position)
                    position += count
                    return count
                }
                while (!stopped) Thread.sleep(2)
                return -1
            }
            override fun stop() { stopped = true }
            override fun close() { closed = true }
        }
        val backend = NextBackend.create(profile, sinkFactory = { error("No native output in this self-test") }, microphone = { mic })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val presenter = ContinuousEditorPresenter(backend.studio, scope, DesktopEditorPorts(backend) { null })
        val saved: com.choplab.core.model.Project
        try {
            check(presenter.dispatch(ContinuousEditorAction.RecordSource))
            withTimeout(5_000) { while (backend.voice.recordedMillis < 1_000) delay(5) }
            check(presenter.finishRecording()) // The same path runs before a host's final autosave.
            check(mic.closed)
            val recorded = backend.studio.document.value.project
            val asset = recorded.assets.single()
            check(asset.channels == 1 && asset.sampleRate == 44_100 && asset.frames == 44_100L)
            check(backend.assets.openVerified(asset).use { WavCodec.read(it) }.samples.contentEquals(samples))
            check(presenter.dispatch(ContinuousEditorAction.Undo))
            check(backend.studio.document.value.project.source == null)
            check(presenter.dispatch(ContinuousEditorAction.Redo))
            check(backend.studio.document.value.project == recorded)
            check(presenter.dispatch(ContinuousEditorAction.AssignSourceRange(0)))
            check(presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            val output = directory.resolve("recorded-song.wav")
            check(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(output), 48_000, bits = 24),
                PlaybackTarget.Arrangement())).accepted)
            idle(backend)
            val exported = Files.newInputStream(output).use { WavCodec.read(it) }
            check(exported.info.sampleRate == 48_000 && exported.info.channels == 2 && exported.info.frames == 48_000L)
            check(exported.samples.maxOf { abs(it) } > .05f)
            val archive = directory.resolve("recorded.choplab")
            check(backend.saveProject(archive).accepted)
            idle(backend)
            ZipFile(archive.toFile()).use { zip ->
                val entry = requireNotNull(zip.getEntry("assets/${asset.hash}.wav"))
                check(zip.getInputStream(entry).use { it.readBytes() }.contentEquals(backend.assets.read(asset)))
            }
            backend.flushAutosave()
            saved = backend.studio.document.value.project
        } finally { presenter.close(); backend.shutdown(); scope.cancel() }
        check(Files.list(profile.resolve("voice-scratch")).use { it.count() } == 0L)
        val reopened = NextBackend.create(profile, sinkFactory = { error("No native output in this self-test") }, microphone = { null })
        try {
            check(reopened.studio.document.value.project == saved)
            check(reopened.assets.openVerified(saved.assets.single()).use { WavCodec.read(it) }.samples.contentEquals(samples))
        } finally { reopened.shutdown() }
    }

    private suspend fun idle(backend: NextBackend) = withTimeout(10_000) {
        while (backend.studio.work.value.jobId != null || backend.studio.work.value.preparationId != null) delay(5)
    }
}
