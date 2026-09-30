package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.model.Project
import com.choplab.core.separation.*
import com.choplab.jvm.PcmMemoryBudget
import com.choplab.jvm.WavCodec
import com.choplab.jvm.separation.*
import com.choplab.ui.*
import com.choplab.ui.separation.FourStemPhase
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Pinned native model through the production host; synthetic input and no audio device or provider. */
object NextFourStemProductionSelfTest {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Pass the pinned four-stem model path" }
        val model = Path.of(args[0]).toAbsolutePath()
        require(model.fileName.toString() == FourStemSpec.MODEL_FILE)
        FourStemModelStore.verify(model)
        val directory = Files.createTempDirectory("choplab-four-stem-production-")
        try { runBlocking { run(directory, model) } }
        finally { check(directory.toFile().deleteRecursively()) }
    }

    private suspend fun run(directory: Path, model: Path) {
        val memory = PcmMemoryBudget.shared
        check(memory.statistics().usedBytes == 0L) { "Run this acceptance in its own JVM" }
        val frames = 24_000
        val input = directory.resolve("Synthetic original.wav")
        Files.newOutputStream(input).use { output ->
            WavCodec.writePcm(output, FloatArray(frames * 2) { index ->
                val frame = index / 2
                val time = frame / 48_000.0
                val envelope = exp(-((frame % 6_000) / 48_000.0) * 35)
                (sin(time * Math.PI * 2 * if (index % 2 == 0) 110.0 else 173.0) * envelope * .3).toFloat()
            }, bits = 24, dither = false)
        }
        val originalBytes = Files.readAllBytes(input)
        val backend = NextBackend.create(directory.resolve("profile"),
            sinkFactory = { error("No native audio in four-stem acceptance") }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ports = DesktopEditorPorts(backend,
            fourStemSessions = OnnxFourStemFactory(FourStemModelStore(model.parent)),
            fourStemMemory = FourStemMemoryProbe()::sample) { null }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        val archive = directory.resolve("song.choplab")
        val export = directory.resolve("song.wav")
        val saved: Project
        val receipt: SeparationMemoryReceipt
        val preparedMillis: Long
        suspend fun ready() = withTimeout(15_000) {
            while (backend.studio.work.value.jobId != null || backend.studio.work.value.preparationId != null) delay(5)
        }
        try {
            check(backend.importAudio(input).accepted); ready()
            val before = backend.studio.document.value
            val original = before.project.asset(requireNotNull(before.project.source).assetHash)
            check(presenter.dispatch(ContinuousEditorAction.OpenFourStems))
            val editor = requireNotNull(presenter.fourStems.value)
            check(!editor.state.value.allowModelDownload)
            val started = System.nanoTime()
            check(editor.start())
            withTimeout(180_000) {
                while (editor.state.value.phase == FourStemPhase.PREPARING) delay(10)
            }
            check(editor.state.value.phase == FourStemPhase.READY) { "Four-stem preparation refused: ${editor.state.value.problem}" }
            preparedMillis = (System.nanoTime() - started) / 1_000_000
            receipt = requireNotNull(editor.state.value.memoryReceipt)
            val prepared = requireNotNull(editor.state.value.prepared)
            check(prepared.modelSha256 == FourStemSpec.MODEL_SHA256)
            check(prepared.stems.map { it.part } == StemPart.entries)
            check(prepared.stems.map { it.asset.hash }.distinct().size == 4)
            for (stem in prepared.stems) {
                val audio = backend.assets.read(stem.asset).inputStream().use(WavCodec::read)
                check(audio.info.floatingPoint && audio.info.bits == 32 && audio.info.channels == 2)
                check(audio.info.sampleRate == 44_100 && audio.info.frames == 22_050L)
                check(audio.samples.all { it.isFinite() } && audio.samples.any { abs(it) > .000001f })
            }
            check(before == backend.studio.document.value)
            check(editor.settings(mix = StemMix.INSTRUMENTAL))
            check(editor.apply())
            saved = backend.studio.document.value.project
            check(backend.studio.document.value.revision == before.revision + 1)
            check(saved.source == before.project.source && saved.clips.size == 4)
            check(saved.tracks.map { it.mute } == listOf(false, false, false, true))
            check(presenter.dispatch(ContinuousEditorAction.CloseFourStems))
            check(presenter.dispatch(ContinuousEditorAction.Undo)); check(backend.studio.document.value.project == before.project)
            check(presenter.dispatch(ContinuousEditorAction.Redo)); check(backend.studio.document.value.project == saved)
            check(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(export), frames, tailFrames = 0, bits = 24),
                PlaybackTarget.Arrangement())).accepted)
            ready()
            val rendered = Files.newInputStream(export).use(WavCodec::read)
            check(rendered.info.bits == 24 && rendered.info.channels == 2 && rendered.info.frames == frames.toLong())
            check(rendered.samples.all { it.isFinite() } && rendered.samples.any { abs(it) > .000001f })
            check(backend.saveProject(archive).accepted); ready()
            NextBackend.create(directory.resolve("fresh-store"),
                sinkFactory = { error("No native audio in four-stem acceptance") }, microphone = { null }).use { fresh ->
                check(fresh.openProject(archive).accepted)
                withTimeout(15_000) { while (fresh.studio.work.value.jobId != null) delay(5) }
                check(fresh.studio.document.value.project == saved)
                saved.assets.forEach { check(fresh.assets.read(it).contentEquals(backend.assets.read(it))) }
                check(fresh.assets.read(original).contentEquals(originalBytes))
            }
            backend.flushAutosave()
            check(Files.list(directory.resolve("profile/four-stem-temporary")).use { it.count() } == 0L)
        } finally { presenter.close(); ports.close(); backend.shutdown(); scope.cancel() }
        NextBackend.create(directory.resolve("profile"),
            sinkFactory = { error("No native audio in four-stem acceptance") }, microphone = { null }).use { reopened ->
            check(reopened.studio.document.value.project == saved)
        }
        withTimeout(10_000) { while (memory.statistics().usedBytes != 0L) delay(5) }
        val stats = memory.statistics()
        check(stats.peakBytes <= stats.limitBytes)
        println("""{"status":"LOCAL_PASS","scope":"four-stem-production","realModel":true,"modelSha256":"${FourStemSpec.MODEL_SHA256}","modelBytes":${FourStemSpec.MODEL_BYTES},"sourceFrames":$frames,"stemFrames":22050,"stems":4,"preparedMillis":$preparedMillis,"memorySource":"${receipt.source}","totalBytes":${receipt.totalBytes},"availableBytes":${receipt.availableBytes},"lowMemory":${receipt.lowMemory},"measuredAtEpochMillis":${receipt.measuredAtEpochMillis},"pcmPeakBytes":${stats.peakBytes},"pcmLimitBytes":${stats.limitBytes},"pcmRetainedBytes":${stats.usedBytes},"originalBytesExact":true,"explicitApply":true,"oneUndoRedo":true,"instrumentalMute":true,"export24":true,"freshArchive":true,"autosaveRestart":true,"nativeDialogs":false,"humanAcceptance":false}""")
    }
}
