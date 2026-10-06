package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.PlayMode
import com.choplab.engine.MixSettings
import com.choplab.engine.MixDelay
import com.choplab.engine.MixReverb
import com.choplab.engine.MixInsert
import com.choplab.engine.MixEq
import com.choplab.engine.MixFilter
import com.choplab.engine.MixFilterMode
import com.choplab.engine.MixCompressor
import com.choplab.engine.TrackFx
import com.choplab.engine.MixerProgram
import com.choplab.engine.Arrangement
import com.choplab.engine.ArrangementClip
import com.choplab.engine.Tempo
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.engine.OfflineRender
import com.choplab.engine.PcmAsset
import com.choplab.jvm.*
import com.choplab.ui.*
import com.choplab.ui.ai.VocalGuidePort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

/** Real presenter, desktop ports, streaming engine and files, with a paced synthetic sink. No devices. */
class NextLoopOverdubTest {
    @Test fun nextPassAudioFinishesOneUndoAndSurvives24bitArchiveAndRestartWithOriginalBytes() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("overdub-production-")
        val profile = directory.resolve("profile")
        val input = directory.resolve("source.wav")
        val samples = FloatArray(8_000 * 2) { (sin(it / 2 * .05) * if (it % 2 == 0) .16 else -.055).toFloat() }
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, samples, 48_000, 2) }
        val original = Files.readAllBytes(input)
        val sink = CountingSink()
        val backend = NextBackend.create(profile, sinkFactory = { sink }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend) { null }
        var capture: LoopOverdubCapture? = null
        val ports = object : ContinuousEditorPorts by host {
            override suspend fun createLoopOverdub(startFrame: Long, frames: Int, grid: IntArray, routes: List<com.choplab.engine.LoopOverdubRoute>) =
                host.createLoopOverdub(startFrame, frames, grid, routes).also { capture = it }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        val saved: Project
        try {
            assertTrue(backend.importAudio(input).accepted); idle(backend)
            val source = backend.studio.document.value.project.asset(backend.studio.document.value.project.source!!.assetHash)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(source.hash, FrameRange(0, 8_000), 0))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetTempo(Tempo(240_000, 710)))).accepted)
            val pad = backend.studio.document.value.project.pads[0].copy(mode = PlayMode.GATE, releaseFrames = 800)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetPad(pad))).accepted)
            withTimeout(10_000) { presenter.state.first { it.permits(ContinuousCapability.LOOP_OVERDUB) } }
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.SIXTEENTH_TRIPLET)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetNoteRepeat(ContinuousNoteRepeat.SIXTEENTH_TRIPLET)))
            val before = backend.studio.document.value
            assertTrue(presenter.dispatch(ContinuousEditorAction.RecordLoopOverdub(1)))
            val take = requireNotNull(capture).take
            assertEquals(48_000, take.frames)
            assertFalse(presenter.dispatch(ContinuousEditorAction.RecordHits))
            assertFalse(presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertFalse(presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE)))
            assertFalse(presenter.dispatch(ContinuousEditorAction.Undo))
            await { take.elapsedFrames >= 44_000 }
            assertTrue(presenter.dispatch(ContinuousEditorAction.HoldPad(0)))
            await { take.elapsedFrames >= 57_000 }
            assertTrue(presenter.dispatch(ContinuousEditorAction.ReleasePad(0)))
            assertTrue(take.elapsedFrames > 48_000)
            val afterRelease = sink.nonzero
            await { take.elapsedFrames >= 150_000 }
            assertTrue(sink.nonzero > afterRelease + 8_000, "A completed phrase returns on later passes without another PAD command")
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetNoteRepeat(ContinuousNoteRepeat.OFF)))
            val gateAt = take.elapsedFrames
            assertTrue(presenter.dispatch(ContinuousEditorAction.HoldPad(0)))
            await { take.elapsedFrames >= gateAt + 4_000 }
            assertTrue(presenter.dispatch(ContinuousEditorAction.ReleasePad(0)))
            assertEquals(2, take.acceptedPresses)
            assertEquals(before, backend.studio.document.value, "No provisional document edits")
            assertTrue(presenter.dispatch(ContinuousEditorAction.StopHits))
            saved = backend.studio.document.value.project
            assertTrue(take.completed)
            assertEquals(before.revision + 1, backend.studio.document.value.revision)
            val clip = saved.clips.single()
            val asset = saved.asset(clip.assetHash)
            assertEquals(48_000, asset.frames)
            assertNull(clip.timelineStartFrame); assertEquals(0, clip.startTick)
            assertEquals(1f, clip.gain); assertEquals(0f, clip.pan)
            val pcm = backend.assets.openVerified(asset).use { WavCodec.read(it) }
            assertTrue(pcm.samples.take(12_000).any { it != 0f }, "The phrase crossing the seam has audio at the loop head")
            assertTrue(pcm.samples.takeLast(12_000).any { it != 0f }, "The pressed phrase keeps its loop-tail audio")
            assertTrue(presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(before.project, backend.studio.document.value.project)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(saved, backend.studio.document.value.project)
            assertContentEquals(original, backend.assets.read(source)); assertContentEquals(original, Files.readAllBytes(input))
            val output = directory.resolve("loop.wav")
            assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(output), 48_000, bits = 24), PlaybackTarget.Arrangement())).accepted)
            idle(backend)
            val wav = Files.newInputStream(output).use { WavCodec.read(it) }
            assertEquals(48_000, wav.info.frames); assertEquals(24, wav.info.bits); assertEquals(2, wav.info.channels)
            assertTrue(wav.samples.maxOf { abs(it) } > .05f)
            val archive = directory.resolve("loop.choplab")
            assertTrue(backend.saveProject(archive).accepted); idle(backend)
            ZipFile(archive.toFile()).use { zip ->
                assertContentEquals(original, zip.getInputStream(zip.getEntry("assets/${source.hash}.wav")).use { it.readBytes() })
                assertContentEquals(backend.assets.read(asset), zip.getInputStream(zip.getEntry("assets/${asset.hash}.wav")).use { it.readBytes() })
            }
            backend.flushAutosave()
            assertTrue(backend.openProject(archive).accepted); idle(backend)
            assertEquals(saved, backend.studio.document.value.project)
        } finally { presenter.close(); backend.shutdown(); host.close(); scope.cancel() }
        val restarted = NextBackend.create(profile, sinkFactory = { error("No device for file readback") }, microphone = { null })
        try {
            assertEquals(saved, restarted.studio.document.value.project)
            val output = directory.resolve("reopened.wav")
            assertTrue(restarted.studio.dispatch(Action.Export(ExportRequest(restarted.files.register(output), 48_000, bits = 24), PlaybackTarget.Arrangement())).accepted)
            idle(restarted)
            assertContentEquals(Files.readAllBytes(directory.resolve("loop.wav")), Files.readAllBytes(output))
        } finally { restarted.shutdown() }
    }

    @Test fun twoMixedBanksAndAnUnroutedPadCommitAllDryLayersOnceAndReplayTheSameGraphAfterMixChangesAndRestart() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("overdub-mixer-")
        val profile = directory.resolve("profile")
        val input = directory.resolve("source.wav")
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, FloatArray(8_000 * 2) {
            (sin(it / 2 * .043) * if (it % 2 == 0) .18 else -.06).toFloat()
        }, 48_000, 2) }
        val original = Files.readAllBytes(input)
        val sink = CountingSink()
        val backend = NextBackend.create(profile, sinkFactory = { sink }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend) { null }
        var capture: LoopOverdubCapture? = null
        val ports = object : ContinuousEditorPorts by host {
            override suspend fun createLoopOverdub(startFrame: Long, frames: Int, grid: IntArray, routes: List<com.choplab.engine.LoopOverdubRoute>) =
                host.createLoopOverdub(startFrame, frames, grid, routes).also { capture = it }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        val finalProject: Project
        suspend fun export(name: String): ByteArray {
            val path = directory.resolve(name)
            assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(path), 48_000, bits = 24), PlaybackTarget.Arrangement())).accepted)
            idle(backend)
            return Files.readAllBytes(path)
        }
        suspend fun expected(project: Project): FloatArray {
            // Explicit dry clips -> track fader -> shared mixer. No compiler or capture implementation in this oracle.
            val ids = project.clips.map { it.trackId }.distinct()
            val tracks = ids.map { id -> project.tracks.first { it.id == id } }
            val pcm = project.assets.associate { asset -> asset.hash to PcmAsset.fromInterleaved(backend.assets.openVerified(asset).use { WavCodec.read(it).samples }) }
            val mixer = MixerProgram(tracks.map { it.fx }, project.mix, ids)
            val arrangement = Arrangement(project.clips.map { clip ->
                val track = tracks.first { it.id == clip.trackId }
                assertEquals(1f, clip.gain); assertEquals(0f, clip.pan)
                ArrangementClip(clip.id, pcm.getValue(clip.assetHash), clip.timelineStartFrame ?: 0, clip.range.start.toInt(), clip.range.end.toInt(),
                    track.gain, track.pan, ids.indexOf(track.id))
            }, 48_000)
            return OfflineRender.render(EngineProgram(arrangement = arrangement, mixer = mixer),
                listOf(EngineCommand.StartSequence(0, 1), EngineCommand.Stop(48_000, 2)), 48_000, mixer.tailFrames)
        }
        fun matchesGraph(expected: FloatArray, bytes: ByteArray) {
            val wav = bytes.inputStream().use(WavCodec::read)
            assertEquals(24, wav.info.bits); assertEquals(2, wav.info.channels); assertEquals(expected.size / 2L, wav.info.frames)
            var largest = 0f
            for (i in expected.indices) largest = maxOf(largest, abs(expected[i] - wav.samples[i]))
            assertTrue(largest <= 2f / 8_388_608, "One track fader and one shared FX/master graph: max difference $largest")
        }
        try {
            assertTrue(backend.importAudio(input).accepted); idle(backend)
            val source = backend.studio.document.value.project.assets.single()
            for (id in listOf(0, 16, 32)) {
                assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(source.hash, FrameRange(0, 8_000), id))).accepted)
                if (id != 32) assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetPad(
                    backend.studio.document.value.project.pads[id].copy(mode = PlayMode.GATE, gain = if (id == 0) .7f else 1.2f, reverse = id == 16)))).accepted)
            }
            val a = Track("bank-a", "A", TrackKind.BANK, gain = .4f, pan = .7f,
                fx = TrackFx(MixInsert(eq = MixEq(3f, -1f, 2f), compressor = MixCompressor(true, -30f, 3f)), .4f, .2f))
            val b = Track("bank-b", "B", TrackKind.BANK, gain = 1.3f, pan = -.4f,
                fx = TrackFx(MixInsert(filter = MixFilter(MixFilterMode.LOW_PASS, 2100f)), .3f, .35f))
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetBankMix(0, a))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetBankMix(1, b))).accepted)
            val backing = Track("backing", "Backing", TrackKind.SOURCE, gain = .08f)
            val project = backend.studio.document.value.project
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement((project.tracks + backing).frozen(),
                frozenListOf(Clip("backing", backing.id, source.hash, FrameRange(0, 2_000), 0)), project.takes))).accepted)
            val settings = MixSettings(MixDelay(true, 211, .3f, .4f), MixReverb(true, .1f, .2f, .15f), MixInsert(eq = MixEq(-1f, 2f, 0f)), .8f)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetMasterMix(settings))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetTempo(Tempo(240_000)))).accepted)
            withTimeout(10_000) { presenter.state.first { it.permits(ContinuousCapability.LOOP_OVERDUB) } }
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE)))
            val before = backend.studio.document.value
            assertTrue(presenter.dispatch(ContinuousEditorAction.RecordLoopOverdub(1)))
            val take = requireNotNull(capture).take
            assertEquals(3, take.routeCount)
            assertTrue(presenter.dispatch(ContinuousEditorAction.HoldPad(0)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.HoldPad(16)))
            await { take.elapsedFrames >= 6_000 }
            assertTrue(presenter.dispatch(ContinuousEditorAction.ReleasePad(0)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.ReleasePad(16)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.TapPad(32)))
            await { take.elapsedFrames >= 60_000 }
            val heard = sink.nonzero
            await { take.elapsedFrames >= 110_000 }
            assertTrue(sink.nonzero > heard + 8_000)
            assertEquals(before, backend.studio.document.value)
            assertTrue(presenter.dispatch(ContinuousEditorAction.StopHits))
            val saved = backend.studio.document.value.project
            assertEquals(before.revision + 1, backend.studio.document.value.revision)
            assertEquals(4, saved.clips.size); assertEquals(4, saved.assets.size)
            assertEquals(a, saved.tracks.first { it.id == a.id }); assertEquals(b, saved.tracks.first { it.id == b.id })
            assertEquals(settings, saved.mix)
            val added = saved.clips.filter { it.id != "backing" }
            assertEquals(3, added.map { it.trackId }.distinct().size)
            assertTrue(added.any { it.trackId == a.id }); assertTrue(added.any { it.trackId == b.id })
            val unrouted = saved.tracks.single { it.id !in setOf(a.id, b.id, backing.id) }
            assertEquals(1f, unrouted.gain); assertEquals(0f, unrouted.pan); assertEquals(TrackFx(), unrouted.fx)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(before.project, backend.studio.document.value.project)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(saved, backend.studio.document.value.project)
            val first = export("recorded.wav")
            matchesGraph(expected(saved), first)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetMasterMix(settings.copy(masterGain = .55f,
                master = MixInsert(filter = MixFilter(MixFilterMode.HIGH_PASS, 180f)))))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetTrackMix(a.copy(gain = .2f, pan = -.9f)))).accepted)
            finalProject = backend.studio.document.value.project
            val changed = export("changed.wav")
            assertFalse(first.contentEquals(changed)); matchesGraph(expected(finalProject), changed)
            assertContentEquals(original, backend.assets.read(source))
            val archive = directory.resolve("mixed.choplab")
            assertTrue(backend.saveProject(archive).accepted); idle(backend)
            ZipFile(archive.toFile()).use { zip -> for (asset in finalProject.assets) {
                assertContentEquals(backend.assets.read(asset), zip.getInputStream(zip.getEntry("assets/${asset.hash}.wav")).use { it.readBytes() })
            } }
            assertTrue(backend.openProject(archive).accepted); idle(backend)
            assertEquals(finalProject, backend.studio.document.value.project)
            assertContentEquals(changed, export("reopened.wav"))
            backend.flushAutosave()
        } finally { presenter.close(); backend.shutdown(); host.close(); scope.cancel() }
        val restarted = NextBackend.create(profile, sinkFactory = { error("No device") }, microphone = { null })
        try {
            assertEquals(finalProject, restarted.studio.document.value.project)
            val path = directory.resolve("restarted.wav")
            assertTrue(restarted.studio.dispatch(Action.Export(ExportRequest(restarted.files.register(path), 48_000, bits = 24), PlaybackTarget.Arrangement())).accepted)
            idle(restarted)
            assertContentEquals(Files.readAllBytes(directory.resolve("changed.wav")), Files.readAllBytes(path))
        } finally { restarted.shutdown() }
    }

    @Test fun threeConsecutiveSourceToolsYieldOneOwnerToLoopAndRepeatedCancelStopCannotPublish() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("overdub-source-owners-")
        val input = directory.resolve("source.wav")
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, FloatArray(48_000 * 2) { if (it % 2 == 0) .03f else -.015f }, 48_000, 2) }
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { CountingSink() }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend) { null }
        val preview = host.vocalGuide.preview
        val ports = object : ContinuousEditorPorts by host {
            // This fixture opens the editor only; it never invokes a native speech provider.
            override val vocalGuide = object : VocalGuidePort {
                override val preview = host.vocalGuide.preview
                override fun createSynthesis() = object : VocalSynthesisPort {
                    override suspend fun voices() = TtsResult.Success(frozenListOf<TtsVoice>())
                    override suspend fun prepare(row: FlowRow, tempo: Tempo, voice: TtsVoice, settings: TtsSettings, regenerate: Boolean) = error("No synthesis requested")
                    override fun close() = Unit
                }
            }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        try {
            assertTrue(backend.importAudio(input).accepted); idle(backend)
            val source = backend.studio.document.value.project.assets.single()
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(source.hash, FrameRange(0, source.frames), 0))).accepted)
            val track = Track("voice", "Voice", TrackKind.VOCAL)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement(frozenListOf(track),
                frozenListOf(Clip("voice", track.id, source.hash, FrameRange(0, source.frames))),
                frozenListOf(Take("take", track.id, source.hash, FrameRange(0, source.frames), 0))))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetTempo(Tempo(240_000)))).accepted)
            withTimeout(10_000) { presenter.state.first { it.permits(ContinuousCapability.LOOP_OVERDUB) } }
            val before = backend.studio.document.value
            val tools = listOf(ContinuousEditorAction.OpenSourceAnalysis, ContinuousEditorAction.AutoChop,
                ContinuousEditorAction.OpenVocalGuide, ContinuousEditorAction.OpenBeatStretch(StretchTarget(StretchKind.PAD, "0")),
                ContinuousEditorAction.OpenVocalPitch, ContinuousEditorAction.OpenVocalCoach)
            for (index in tools.indices) {
                val first = tools[index]; val second = tools[(index + 1) % tools.size]
                assertTrue(presenter.dispatch(first), first.toString())
                assertTrue(presenter.dispatch(second), "$first -> $second")
                val owner = when (second) {
                    ContinuousEditorAction.AutoChop -> VocalPreviewOwner.CHOP
                    ContinuousEditorAction.OpenVocalGuide -> VocalPreviewOwner.GUIDE
                    is ContinuousEditorAction.OpenBeatStretch -> VocalPreviewOwner.STRETCH
                    ContinuousEditorAction.OpenVocalPitch -> VocalPreviewOwner.PITCH
                    ContinuousEditorAction.OpenVocalCoach -> VocalPreviewOwner.COACH
                    else -> null
                }
                if (owner != null) {
                    val started = if (owner == VocalPreviewOwner.CHOP) preview.startRange(source, FrameRange(0, source.frames), before.revision, owner)
                        else preview.start(source.copy(role = AssetRole.RENDERED), before.revision, true, owner)
                    assertIs<TtsResult.Success<Unit>>(started, "$second: $started")
                    withTimeout(10_000) { preview.state.first { it.owner == owner && it.phase == VocalPreviewPhase.PLAYING } }
                }
                assertTrue(presenter.dispatch(ContinuousEditorAction.RecordLoopOverdub(1)), "$first -> $second -> loop")
                assertNull(presenter.sourceAnalysis.value); assertNull(presenter.autoChop.value)
                assertNull(presenter.vocalGuide.value); assertNull(presenter.beatStretch.value)
                assertNull(presenter.vocalPitch.value); assertNull(presenter.vocalCoach.value)
                assertFalse(preview.state.value.ownsSource)
                withTimeout(10_000) { presenter.state.first { it.recordingHits } }
                assertTrue(presenter.dispatch(ContinuousEditorAction.SetNoteRepeat(ContinuousNoteRepeat.SIXTEENTH_TRIPLET)))
                assertTrue(presenter.dispatch(ContinuousEditorAction.HoldPad(0)))
                assertTrue(presenter.dispatch(ContinuousEditorAction.CancelLoopOverdub))
                assertTrue(presenter.dispatch(ContinuousEditorAction.CancelLoopOverdub))
                assertTrue(presenter.dispatch(ContinuousEditorAction.StopAll))
                assertTrue(presenter.dispatch(ContinuousEditorAction.ReleasePad(0)))
                await { !presenter.state.value.recordingHits && backend.engine.snapshot().activeVoices == 0 }
                assertEquals(before, backend.studio.document.value)
                assertFalse(preview.state.value.ownsSource)
                assertContentEquals(Files.readAllBytes(input), backend.assets.read(source))
            }
        } finally { presenter.close(); backend.shutdown(); host.close(); scope.cancel() }
    }

    @Test fun discardCountInStopAndOutputLossRespectTheTakeOwner() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("overdub-loss-")
        val input = directory.resolve("source.wav")
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, FloatArray(48_000 * 2) { if (it % 2 == 0) .02f else -.01f }, 48_000, 2) }
        val sink = CountingSink()
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { sink }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend) { null }
        var capture: LoopOverdubCapture? = null
        val ports = object : ContinuousEditorPorts by host {
            override suspend fun createLoopOverdub(startFrame: Long, frames: Int, grid: IntArray, routes: List<com.choplab.engine.LoopOverdubRoute>) =
                host.createLoopOverdub(startFrame, frames, grid, routes).also { capture = it }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        try {
            assertTrue(backend.importAudio(input).accepted); idle(backend)
            val source = backend.studio.document.value.project.assets.single()
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(source.hash, FrameRange(0, 48_000), 0))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetTempo(Tempo(240_000)))).accepted)
            withTimeout(10_000) { presenter.state.first { it.permits(ContinuousCapability.LOOP_OVERDUB) } }
            val before = backend.studio.document.value
            for (bars in 1..8) {
                assertTrue(presenter.dispatch(ContinuousEditorAction.RecordLoopOverdub(bars)))
                assertEquals(bars * 48_000, capture!!.take.frames)
                assertTrue(presenter.dispatch(ContinuousEditorAction.TapPad(0)))
                assertTrue(presenter.dispatch(ContinuousEditorAction.CancelLoopOverdub))
                assertEquals(before, backend.studio.document.value)
                assertEquals(0, (backend.studio.selection.value.playbackTarget as PlaybackTarget.Arrangement).minimumFrames)
            }
            assertTrue(presenter.dispatch(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(1))))
            assertTrue(presenter.dispatch(ContinuousEditorAction.RecordLoopOverdub(1)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.TapPad(0)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.StopAll))
            assertEquals(before, backend.studio.document.value, "Pre-cue PADs cannot create an overdub")
            assertTrue(presenter.dispatch(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(0))))
            assertTrue(presenter.dispatch(ContinuousEditorAction.RecordLoopOverdub(1)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.TapPad(0)))
            await { capture!!.take.elapsedFrames > 4_000 }
            sink.fail = true
            await { capture!!.take.completed && !presenter.state.value.recordingHits && presenter.state.value.status == ContinuousStatus.LOOP_INTERRUPTED }
            assertTrue(capture!!.take.interrupted)
            assertEquals(before.revision + 1, backend.studio.document.value.revision)
            assertEquals(ContinuousStatus.LOOP_INTERRUPTED, presenter.state.value.status)
            assertEquals(1, backend.studio.document.value.project.clips.size)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(before.project, backend.studio.document.value.project)
        } finally { presenter.close(); backend.shutdown(); host.close(); scope.cancel() }
    }

    @Test fun latePreparationAndFailedPublicationCannotMutateOrLoseTheTakeAndStopDuringCommitAddsItOnlyOnce() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("overdub-late-")
        val input = directory.resolve("source.wav")
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, FloatArray(8_000 * 2) { .025f }, 48_000, 2) }
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { CountingSink() }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend) { null }
        val preparing = CompletableDeferred<Unit>()
        val allowPreparation = CompletableDeferred<Unit>()
        val publishing = CompletableDeferred<Unit>()
        val allowPublication = CompletableDeferred<Unit>()
        var prepares = 0
        var publishes = 0
        var closed = 0
        var capture: LoopOverdubCapture? = null
        val ports = object : ContinuousEditorPorts by host {
            override suspend fun createLoopOverdub(startFrame: Long, frames: Int, grid: IntArray, routes: List<com.choplab.engine.LoopOverdubRoute>): LoopOverdubCapture? {
                if (++prepares == 1) { preparing.complete(Unit); allowPreparation.await() }
                val real = requireNotNull(host.createLoopOverdub(startFrame, frames, grid, routes))
                return object : LoopOverdubCapture {
                    override val take = real.take
                    override suspend fun publish(name: String): List<LoopOverdubAsset> {
                        if (++publishes == 1) error("Injected storage failure before publication")
                        publishing.complete(Unit); allowPublication.await()
                        return real.publish(name)
                    }
                    override fun close() { closed++; real.close() }
                }.also { capture = it }
            }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        try {
            assertTrue(backend.importAudio(input).accepted); idle(backend)
            val source = backend.studio.document.value.project.assets.single()
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(source.hash, FrameRange(0, 8_000), 0))).accepted)
            withTimeout(10_000) { presenter.state.first { it.permits(ContinuousCapability.LOOP_OVERDUB) } }
            val before = backend.studio.document.value
            val starting = async { presenter.dispatch(ContinuousEditorAction.RecordLoopOverdub(1)) }
            preparing.await()
            val stop = async(start = CoroutineStart.UNDISPATCHED) { presenter.dispatch(ContinuousEditorAction.StopAll) }
            allowPreparation.complete(Unit)
            assertFalse(starting.await()); assertTrue(stop.await())
            assertEquals(before, backend.studio.document.value); assertEquals(1, closed)
            assertTrue(presenter.dispatch(ContinuousEditorAction.RecordLoopOverdub(1)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.TapPad(0)))
            await { capture!!.take.elapsedFrames > 4_000 }
            assertFalse(presenter.dispatch(ContinuousEditorAction.StopHits))
            assertEquals(before, backend.studio.document.value)
            assertTrue(presenter.state.value.recordingHits)
            assertEquals(1, closed, "A failed publication retains its PCM for retry")
            val finishing = async { presenter.dispatch(ContinuousEditorAction.StopHits) }
            publishing.await()
            val stopDuringSave = async(start = CoroutineStart.UNDISPATCHED) { presenter.dispatch(ContinuousEditorAction.StopAll) }
            allowPublication.complete(Unit)
            assertTrue(finishing.await()); assertTrue(stopDuringSave.await())
            assertEquals(before.revision + 1, backend.studio.document.value.revision)
            assertEquals(1, backend.studio.document.value.project.clips.size)
            assertEquals(2, closed)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(before.project, backend.studio.document.value.project)
        } finally { allowPreparation.complete(Unit); allowPublication.complete(Unit); presenter.close(); backend.shutdown(); host.close(); scope.cancel() }
    }

    @Test fun hostCloseFencesLateLoopPreparationBeforeWaitingForSourceRestoration() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("overdub-host-close-")
        val input = directory.resolve("source.wav")
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, FloatArray(8_000 * 2) { if (it % 2 == 0) .025f else -.01f }, 48_000, 2) }
        val original = Files.readAllBytes(input)
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { CountingSink() }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend) { null }
        val preparing = CompletableDeferred<Unit>()
        val allowPreparation = CompletableDeferred<Unit>()
        val closingSource = CompletableDeferred<Unit>()
        val allowSourceClose = CompletableDeferred<Unit>()
        val preview = object : VocalPreviewPort by host.vocalGuide.preview {
            override suspend fun stop(owner: VocalPreviewOwner): TtsResult<Unit> {
                if (owner == VocalPreviewOwner.COACH && preparing.isCompleted) {
                    closingSource.complete(Unit)
                    allowSourceClose.await()
                }
                return host.vocalGuide.preview.stop(owner)
            }
        }
        var closed = 0
        var publishes = 0
        val ports = object : ContinuousEditorPorts by host {
            override val sourcePreview = preview
            override val vocalGuide = object : VocalGuidePort by host.vocalGuide { override val preview = sourcePreview }
            override val vocalTakes = object : com.choplab.ui.vocal.VocalTakePort by host.vocalTakes { override val preview = sourcePreview }
            override val vocalPractice = object : com.choplab.ui.vocal.VocalPracticePort by host.vocalPractice { override val preview = sourcePreview }
            override val vocalPitch = object : com.choplab.ui.vocal.VocalPitchHost by host.vocalPitch { override val preview = sourcePreview }
            override val vocalCoach = object : com.choplab.ui.vocal.VocalCoachHost by host.vocalCoach { override val preview = sourcePreview }
            override val beatStretch = object : com.choplab.ui.stretch.BeatStretchHost by host.beatStretch { override val preview = sourcePreview }
            override suspend fun createLoopOverdub(startFrame: Long, frames: Int, grid: IntArray, routes: List<com.choplab.engine.LoopOverdubRoute>): LoopOverdubCapture? {
                preparing.complete(Unit)
                allowPreparation.await()
                val real = requireNotNull(host.createLoopOverdub(startFrame, frames, grid, routes))
                return object : LoopOverdubCapture {
                    override val take = real.take
                    override suspend fun publish(name: String): List<LoopOverdubAsset> { publishes++; return real.publish(name) }
                    override fun close() { closed++; real.close() }
                }
            }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        var closing: Deferred<Unit>? = null
        try {
            assertTrue(backend.importAudio(input).accepted); idle(backend)
            val source = backend.studio.document.value.project.assets.single()
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(source.hash, FrameRange(0, source.frames), 0))).accepted)
            withTimeout(10_000) { presenter.state.first { it.permits(ContinuousCapability.LOOP_OVERDUB) } }
            val before = backend.studio.document.value
            val starting = async { presenter.dispatch(ContinuousEditorAction.RecordLoopOverdub(1)) }
            withTimeout(10_000) { preparing.await() }
            // The loop is already preparing. Host close must fence it before any SOURCE stop can suspend.
            closing = async(start = CoroutineStart.UNDISPATCHED) { presenter.close() }
            withTimeout(10_000) { closingSource.await() }
            allowPreparation.complete(Unit)
            assertFalse(withTimeout(10_000) { starting.await() }, "A late loop cannot start while host close awaits SOURCE restoration")
            assertFalse(closing.isCompleted, "The cancellation is observed before SOURCE restoration finishes")
            assertEquals(1, closed)
            assertEquals(0, publishes)
            assertEquals(0, presenter.state.value.loopOverdubBars)
            assertEquals(before, backend.studio.document.value, "Closing an unopened capture changes neither the document nor its Undo history")
            assertContentEquals(original, Files.readAllBytes(backend.assets.verifiedPath(source)))
            allowSourceClose.complete(Unit)
            withTimeout(10_000) { closing.await() }
            assertEquals(1, closed)
            assertEquals(0, publishes)
            assertEquals(before, backend.studio.document.value)
            assertContentEquals(original, Files.readAllBytes(input))
        } finally {
            allowPreparation.complete(Unit); allowSourceClose.complete(Unit)
            withContext(NonCancellable) {
                withTimeout(10_000) { closing?.join() }
                presenter.close(); backend.shutdown(); host.close(); scope.cancel()
            }
        }
    }

    @Test fun aFullArrangementRefusesBeforeCaptureAndRepeatingOverdubsReuseAnUnprocessedBankTrack() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("overdub-capacity-")
        val input = directory.resolve("source.wav")
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, FloatArray(8_000 * 2) { .001f }, 48_000, 2) }
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { CountingSink() }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend) { null }
        var prepares = 0
        val ports = object : ContinuousEditorPorts by host {
            override suspend fun createLoopOverdub(startFrame: Long, frames: Int, grid: IntArray, routes: List<com.choplab.engine.LoopOverdubRoute>) = host.createLoopOverdub(startFrame, frames, grid, routes).also { prepares++ }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        try {
            assertTrue(backend.importAudio(input).accepted); idle(backend)
            val source = backend.studio.document.value.project.assets.single()
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(source.hash, FrameRange(0, 8_000), 0))).accepted)
            val track = com.choplab.core.model.Track("backing", "Backing", com.choplab.core.model.TrackKind.BANK)
            val clips = (0 until 32).map { com.choplab.core.model.Clip("backing-$it", track.id, source.hash, FrameRange(0, 8_000)) }
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement(com.choplab.core.model.frozenListOf(track),
                com.choplab.core.model.FrozenList.from(clips), com.choplab.core.model.frozenListOf()))).accepted)
            withTimeout(10_000) { presenter.state.first { it.permits(ContinuousCapability.LOOP_OVERDUB) } }
            val full = backend.studio.document.value
            assertFalse(presenter.dispatch(ContinuousEditorAction.RecordLoopOverdub(1)))
            assertEquals(0, prepares); assertEquals(full, backend.studio.document.value)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement(com.choplab.core.model.frozenListOf(track),
                com.choplab.core.model.frozenListOf(clips.first()), com.choplab.core.model.frozenListOf()))).accepted)
            for (index in 1..2) {
                assertTrue(presenter.dispatch(ContinuousEditorAction.RecordLoopOverdub(1)))
                assertTrue(presenter.dispatch(ContinuousEditorAction.TapPad(0)))
                assertTrue(presenter.dispatch(ContinuousEditorAction.StopHits))
                assertEquals(1, backend.studio.document.value.project.tracks.size)
                assertEquals(index + 1, backend.studio.document.value.project.clips.size)
            }
        } finally { presenter.close(); backend.shutdown(); host.close(); scope.cancel() }
    }

    private suspend fun idle(backend: NextBackend) = await { backend.studio.work.value.jobId == null && backend.studio.work.value.preparationId == null }
    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(5) }
    private class CountingSink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        @Volatile var nonzero = 0L
        @Volatile var fail = false
        @Volatile var closed = false
        private var started = 0L
        private var frames = 0L
        override fun pendingFrames() = 0L
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!closed && !fail)
            for (i in offset until offset + length step 4) {
                val bits = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8) or
                    ((bytes[i + 2].toInt() and 255) shl 16) or (bytes[i + 3].toInt() shl 24)
                if (Float.fromBits(bits) != 0f) nonzero++
            }
            if (frames == 0L) started = System.nanoTime()
            frames += length / 8
            val deadline = started + frames * 1_000_000_000L / 48_000
            while (true) {
                val left = deadline - System.nanoTime()
                if (left <= 0) break
                LockSupport.parkNanos(((left + 15_624_999) / 15_625_000) * 15_625_000)
            }
            return length
        }
        override fun close() { closed = true }
    }
}
