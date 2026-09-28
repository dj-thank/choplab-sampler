package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.core.pattern.*
import com.choplab.engine.EngineCore
import com.choplab.engine.PlayMode
import com.choplab.engine.Tempo
import com.choplab.jvm.WavCodec
import com.choplab.ui.pattern.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

/** Real Studio, PAD-performance renderer, arrangement engine, WAV, archive and restart; no native devices. */
class StepPatternFlowTest {
    @Test fun twoEditedPatternsRepeatThroughPerformedClipsOneUndoStereoExportAndArchiveRestore() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("step-pattern-flow-")
        val profile = directory.resolve("profile")
        val backend = backend(profile)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var controller: StepPatternController? = null
        val saved: Project
        try {
            preparePads(backend, directory)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetTempo(Tempo(97_125, 710)))).accepted)
            val beforePattern = backend.studio.document.value.project
            val availability = MutableStateFlow(PatternAvailability.EDITABLE)
            val renderRequests = mutableListOf<PatternVoiceRender>()
            val ports = object : StepPatternPorts {
                override suspend fun apply(intent: Intent, expectedRevision: Long) =
                    backend.studio.dispatch(Action.Edit(intent, expectedRevision)).accepted
                override suspend fun render(pad: Pad, source: Asset, request: PatternVoiceRender): Asset {
                    renderRequests += request
                    return backend.renderPerformance(pad, source, request.releaseAt, request.limitFrames, request.stopAt)
                }
                override suspend fun selectPad(padId: Int) = backend.studio.dispatch(Action.SelectPad(padId)).accepted
            }
            val editor = StepPatternController(backend.studio.document, backend.studio.selection, availability, ports, scope).also { controller = it }
            suspend fun action(value: PatternAction) { assertTrue(editor.dispatch(value), value.toString()) }
            action(PatternAction.Name("A"))
            for (step in listOf(0, 4, 8, 12)) action(PatternAction.Toggle(step))
            assertEquals(beforePattern, backend.studio.document.value.project)
            val revision = backend.studio.document.value.revision
            action(PatternAction.Save)
            assertEquals(revision + 1, backend.studio.document.value.revision)
            val first = backend.studio.document.value.project
            assertTrue(backend.studio.dispatch(Action.Undo).accepted)
            assertEquals(beforePattern, backend.studio.document.value.project)
            assertTrue(backend.studio.dispatch(Action.Redo).accepted)
            assertEquals(first, backend.studio.document.value.project)
            action(PatternAction.Reload)
            action(PatternAction.New("B")); action(PatternAction.Resize(2)); action(PatternAction.SelectPad(1))
            waitUntil { editor.state.value.selectedPadId == 1 }
            action(PatternAction.Velocity(.75f))
            for (step in listOf(1, 9, 17, 25)) action(PatternAction.Toggle(step))
            action(PatternAction.Save)
            val b = editor.state.value.draft.id
            val patterns = backend.studio.document.value.project.patterns
            assertEquals(listOf(1, 2), patterns.map { it.bars })
            action(PatternAction.Select(patterns.first().id)); action(PatternAction.Repeats(2)); action(PatternAction.Queue)
            action(PatternAction.Select(b)); action(PatternAction.Queue); action(PatternAction.FirstBar(2))
            val beforePlace = backend.studio.document.value
            val plan = PatternPlacement.plan(beforePlace.project, editor.state.value.sequence, PatternEdits.BAR_TICKS.toLong())
            action(PatternAction.Place("Patterns"))
            saved = backend.studio.document.value.project
            assertEquals(beforePlace.revision + 1, backend.studio.document.value.revision)
            assertEquals(16, saved.clips.size)
            assertEquals(plan.voices.map { it.startTick }, saved.clips.map { it.startTick })
            assertTrue(renderRequests.size < saved.clips.size, "Repeated identical voices render once")
            assertEquals(patterns, saved.patterns)
            assertEquals(1, backend.studio.selection.value.padId, "Placement must not select another PAD")
            assertTrue(saved.clips.all { it.timelineStartFrame == null && it.pan == 0f })
            for ((voice, clip) in plan.voices.zip(saved.clips)) {
                val asset = saved.asset(clip.assetHash)
                assertEquals(48_000, asset.sampleRate); assertEquals(2, asset.channels)
                assertEquals(voice.velocity, clip.gain)
                assertEquals(FrameRange(0, asset.frames), clip.range)
            }
            assertTrue(backend.studio.dispatch(Action.Undo).accepted)
            assertEquals(beforePlace.project, backend.studio.document.value.project, "The entire queue is one Undo")
            assertTrue(backend.studio.dispatch(Action.Redo).accepted)
            assertEquals(saved, backend.studio.document.value.project)
            val end = ProgramCompiler.clipTickToFrame(plan.endTick, saved.tempo) + EngineCore.STEAL_FADE_FRAMES
            val output = directory.resolve("patterns.wav")
            val exportStarted = System.nanoTime()
            assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(output), end.toInt() + 256, bits = 24), PlaybackTarget.Arrangement())).accepted)
            idle(backend)
            println("STEP arrangement export ms=${(System.nanoTime() - exportStarted) / 1_000_000} frames=${end + 256} clips=${saved.clips.size}")
            val audio = Files.newInputStream(output).use { WavCodec.read(it) }
            assertEquals((end.toInt() + 256) * 2, audio.samples.size)
            val from = ProgramCompiler.clipTickToFrame(PatternEdits.BAR_TICKS.toLong(), saved.tempo).toInt()
            assertTrue(audio.samples.take(from * 2).all { abs(it) <= 2f / 8_388_608 }, "No note before the inclusive start")
            assertTrue(audio.samples.drop(end.toInt() * 2).all { abs(it) <= 2f / 8_388_608 }, "LOOP and stop fade end; no infinite tail")
            val energyLeft = audio.samples.indices.filter { it % 2 == 0 }.sumOf { audio.samples[it].toDouble() * audio.samples[it] }
            val energyRight = audio.samples.indices.filter { it % 2 == 1 }.sumOf { audio.samples[it].toDouble() * audio.samples[it] }
            assertTrue(energyLeft > energyRight && energyRight > 1.0, "Stereo identity and nonzero output survive placement")
            val archive = directory.resolve("patterns.choplab")
            assertTrue(backend.saveProject(archive).accepted); idle(backend)
            ZipFile(archive.toFile()).use { zip -> saved.assets.forEach { assertNotNull(zip.getEntry(it.entryName)) } }
            assertTrue(backend.openProject(archive).accepted); idle(backend)
            assertEquals(saved, backend.studio.document.value.project)
            backend.flushAutosave()
        } finally { controller?.close(); scope.cancel(); backend.shutdown() }
        val restored = backend(profile)
        try { assertEquals(saved, restored.studio.document.value.project) } finally { restored.shutdown() }
    }

    @Test fun actualActorRejectsAQueueWhoseRevisionChangesAfterRendering() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("step-pattern-stale-")
        val backend = backend(directory.resolve("profile"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var controller: StepPatternController? = null
        var notice: Notice? = null
        try {
            preparePads(backend, directory)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.PutPattern(Pattern("pattern-1", notes = frozenListOf(Note(0, 0)))))).accepted)
            val editor = StepPatternController(backend.studio.document, backend.studio.selection, MutableStateFlow(PatternAvailability.EDITABLE), object : StepPatternPorts {
                override suspend fun apply(intent: Intent, expectedRevision: Long): Boolean {
                    reached.complete(Unit); release.await()
                    return backend.studio.dispatch(Action.Edit(intent, expectedRevision)).also { notice = it.notice }.accepted
                }
                override suspend fun render(pad: Pad, source: Asset, request: PatternVoiceRender) =
                    backend.renderPerformance(pad, source, request.releaseAt, request.limitFrames, request.stopAt)
                override suspend fun selectPad(padId: Int) = false
            }, scope).also { controller = it }
            assertTrue(editor.dispatch(PatternAction.Queue))
            val placing = async { editor.dispatch(PatternAction.Place("Patterns")) }
            withTimeout(5_000) { reached.await() }
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.Rename("Changed while preparing"))).accepted)
            val current = backend.studio.document.value
            release.complete(Unit)
            assertFalse(placing.await())
            assertEquals(Notice.StaleCompletion, notice)
            assertEquals(current, backend.studio.document.value)
            assertTrue(current.project.clips.isEmpty())
        } finally { release.complete(Unit); controller?.close(); scope.cancel(); backend.shutdown() }
    }

    private fun backend(profile: Path) = NextBackend.create(profile, sinkFactory = { error("No native device in this test") }, microphone = { null })
    private suspend fun preparePads(backend: NextBackend, directory: Path) {
        val input = directory.resolve("source.wav")
        val samples = FloatArray(19_200) { i -> (sin(i / 2 * .05) * if (i % 2 == 0) .3 else -.15).toFloat() }
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, samples, 48_000, 2) }
        assertTrue(backend.importAudio(input).accepted); idle(backend)
        val hash = requireNotNull(backend.studio.document.value.project.source).assetHash
        for (id in 0..1) {
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(hash, FrameRange(id * 4800L, (id + 1) * 4800L), id))).accepted)
            val pad = backend.studio.document.value.project.pads[id]
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetPad(pad.copy(mode = if (id == 0) PlayMode.GATE else PlayMode.LOOP,
                pitchSemitones = if (id == 0) 0.0 else -5.0, reverse = id == 1, chokeGroup = 1, releaseFrames = 192, gain = .7f,
                pan = if (id == 0) -.25f else .25f, tone = .8f)))).accepted)
        }
    }
    private suspend fun idle(backend: NextBackend) = waitUntil { backend.studio.work.value.jobId == null && backend.studio.work.value.preparationId == null }
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(5) }
}
