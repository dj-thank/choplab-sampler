package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.chop.*
import com.choplab.core.edit.*
import com.choplab.core.lyrics.*
import com.choplab.core.model.*
import com.choplab.core.separation.*
import com.choplab.core.vocal.*
import com.choplab.engine.*
import com.choplab.jvm.*
import com.choplab.jvm.ai.*
import com.choplab.jvm.separation.*
import com.choplab.ui.*
import com.choplab.ui.ai.*
import com.choplab.ui.analysis.*
import com.choplab.ui.chop.*
import com.choplab.ui.mixer.*
import com.choplab.ui.pattern.*
import com.choplab.ui.stretch.*
import com.choplab.ui.separation.FourStemPhase
import com.choplab.ui.vocal.*
import kotlinx.coroutines.*
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipFile
import kotlin.math.*

/** The packaged production graph, with explicit synthetic endpoints. No native device or provider is contacted. */
object NextWholeCreationSelfTest {
    @JvmStatic fun main(args: Array<String>) {
        val index = args.indexOf("--self-test")
        require(index >= 0 && index + 1 < args.size) { "Use --self-test <temporary-directory>" }
        println(runBlocking { run(Path.of(args[index + 1])).json })
    }

    data class Receipt(val directory: Path, val json: String, val undoChecks: Int, val pcmPeakBytes: Long, val pcmAfterCloseBytes: Long, val checks: List<String>)

    suspend fun run(directory: Path): Receipt {
        Files.createDirectories(directory)
        val run = Files.createDirectory(directory.resolve("whole-creation-${UUID.randomUUID()}"))
        val checks = mutableListOf<String>()
        val h = Workbench(run)
        var finalProject: Project? = null
        var assetHashes: Map<String, String> = emptyMap()
        var wavHash = ""
        var stemHash = ""
        var lrcHash = ""
        try {
            h.prepare()
            h.sourceAndChop(); checks += "source-analysis-explicit-tempo-undo-attack-chop"
            h.patternAndLoop(); checks += "triplet-step-repeat-route-overdub-undo"
            h.stretch(); checks += "wsola-preview-apply-undo"
            h.lyricsAndVoice(); checks += "lyrics-two-takes-punch-comp-guide-undo"
            h.practicePitchCoach(); checks += "practice-pitch-coach-source-ownership"
            h.fourStems(); checks += "four-stem-transaction-fixture-model"
            h.mixAndExport(); checks += "mixer-fx-master-wav24-stems-lrc"
            h.originalsUnchanged()
            finalProject = h.project
            assetHashes = h.project.assets.associate { it.hash to sha256(h.backend.assets.read(it)) }
            wavHash = fileSha256(h.wav)
            stemHash = fileSha256(h.stems)
            lrcHash = fileSha256(h.lrc)
            h.act(ContinuousEditorAction.SaveProject)
            h.idle(); check(Files.isRegularFile(h.archive))
            h.backend.flushAutosave()
        } finally { h.close() }
        val saved = requireNotNull(finalProject)
        // A fresh composition root reads autosave, then another root imports the explicit archive.
        for ((name, archive) in listOf("profile" to false, "reopened" to true)) {
            val restored = NextBackend.create(run.resolve(name), sinkFactory = { error("No native output in reopen proof") }, microphone = { null })
            try {
                if (archive) {
                    check(restored.openProject(run.resolve("song.choplab")).accepted)
                    until { restored.studio.work.value.jobId == null }
                }
                check(restored.studio.document.value.project == saved) { "Restored document differs: $name" }
                for (asset in saved.assets) check(sha256(restored.assets.read(asset)) == assetHashes.getValue(asset.hash))
                val out = run.resolve("$name.wav")
                val frames = Files.newInputStream(run.resolve("mix.wav")).use(WavCodec::inspect).frames.toInt()
                check(restored.studio.dispatch(Action.Export(ExportRequest(restored.files.register(out), frames,
                    bits = 24, tailMode = ExportTailMode.EXACT), PlaybackTarget.Arrangement())).accepted)
                until { restored.studio.work.value.jobId == null && Files.exists(out) }
                check(fileSha256(out) == wavHash) { "Restart changed the production graph: $name" }
            } finally { restored.shutdown() }
        }
        checks += "archive-autosave-fresh-backend-all-asset-bytes-and-export"
        val memory = PcmMemoryBudget.shared.statistics()
        check(memory.usedBytes == 0L && memory.leasedAssets == 0) { "A closed production owner retained PCM: $memory" }
        check(memory.peakBytes <= memory.limitBytes)
        checks += "shared-128mib-owners-released"
        // Every string here is a fixed label or SHA-256; no path, source text or user-controlled value enters JSON.
        val checkJson = checks.joinToString(",") { "\"$it\"" }
        val projectHash = fileSha256(run.resolve("song.choplab"))
        val json = """{"status":"LOCAL_PASS","scope":"packaged-headless-whole-creation","nativeAudio":false,"gui":false,"provider":false,"humanAcceptance":false,"tts":"deterministic-fixture-provider","fourStemModel":"deterministic-fixture-session","projectSha256":"$projectHash","wav24Sha256":"$wavHash","stemsSha256":"$stemHash","lrcSha256":"$lrcHash","assetCount":${saved.assets.size},"clipCount":${saved.clips.size},"takeCount":${saved.takes.size},"undoChecks":${h.undoChecks},"sourceTempoCandidates":${h.tempoCandidates},"pcmPeakBytes":${memory.peakBytes},"pcmLimitBytes":${memory.limitBytes},"pcmAfterCloseBytes":${memory.usedBytes},"checks":[$checkJson]}"""
        Files.writeString(run.resolve("receipt.json"), json + "\n")
        return Receipt(run, json, h.undoChecks, memory.peakBytes, memory.usedBytes, checks.toList())
    }

    /** Also used by the host test for cross-feature refusal and genuinely late worker completion. */
    /** Explicit fixture edit: preserve both full lyric rows inside every recorded candidate's actual coverage. */
    internal fun recordedLyricTiming(project: Project, lines: FrozenList<LyricLine>): FrozenList<LyricLine> {
        require(project.takes.size == 2 && lines.size == 2)
        val start = project.takes.maxOf { it.correctedStartFrame() }.coerceAtLeast(0)
        val end = project.takes.minOf { it.correctedEndFrame(project.asset(it.assetHash)) }
        val first = lines.minOf { it.startTick }
        val coveredStart = maxOf(start, ProgramCompiler.tickToFrame(first, project.tempo.milliBpm))
        val beatFramesNumerator = 48_000L * 60_000
        val firstBeat = coveredStart * project.tempo.milliBpm / beatFramesNumerator + 1
        val shift = firstBeat * ProjectLimits.PPQ - first
        val placed = lines.map { line -> line.copy(startTick = line.startTick + shift, endTick = line.endTick + shift,
            words = line.words.map { it.copy(startTick = it.startTick + shift, endTick = it.endTick + shift) }.frozen()) }.frozen()
        check(ProgramCompiler.tickToFrame(placed.last().endTick, project.tempo.milliBpm) <= end) {
            "${VocalProblem.TAKE_TOO_SHORT}: both complete lyric rows must fit; ${compBounds(project.copy(lyrics = placed))}"
        }
        project.takes.forEach { VocalCompEdits.lines(project.copy(lyrics = placed), it.id, "covered-fixture") }
        return placed
    }

    private fun compBounds(project: Project): String = "tempo=${project.tempo.milliBpm}; " +
        "takeFrames=${project.takes.map { listOf(it.range.start, it.range.end, it.correctedStartFrame(), it.correctedEndFrame(project.asset(it.assetHash))) }}; " +
        "lyricTicks=${project.lyrics.map { listOf(it.startTick, it.endTick) }}; " +
        "lyricFrames=${project.lyrics.map { listOf(ProgramCompiler.tickToFrame(it.startTick, project.tempo.milliBpm), ProgramCompiler.tickToFrame(it.endTick, project.tempo.milliBpm)) }}"

    internal class Workbench(val directory: Path) {
        val sink = ClockedSink()
        private var micNumber = 0
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { sink },
            microphone = { SineMic(220 * 2.0.pow((if (micNumber++ % 2 == 0) 45.0 else -35.0) / 1200)) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend, fourStemSessions = FixtureStems(),
            fourStemMemory = { SeparationMemory(8L shl 30, 4L shl 30, false) }, parent = { null })
        val archive = directory.resolve("song.choplab")
        val wav = directory.resolve("mix.wav")
        val stems = directory.resolve("stems.zip")
        val lrc = directory.resolve("lyrics.lrc")
        val sourcePath = directory.resolve("source.wav")
        private lateinit var original: Asset
        private lateinit var originalHash: String
        var lastCapture: LoopOverdubCapture? = null
        var analysisEntered: CompletableDeferred<Unit>? = null
        var analysisRelease: CompletableDeferred<Unit>? = null
        var analysisReturned: CompletableDeferred<Unit>? = null
        var undoChecks = 0
        var tempoCandidates = 0
        private var closed = false
        private val voice = TtsVoice(TtsEngine("whole-creation-fixture", "1", "fixture", "1"),
            "fixture", "Fixture voice", "ja-JP", "1", LyricLanguage.JAPANESE)
        val ports = object : ContinuousEditorPorts by host {
            override suspend fun chooseSave() = backend.files.register(archive)
            override suspend fun chooseExport(frames: Long) = ExportRequest(backend.files.register(wav), frames.toInt(), bits = 24, tailMode = ExportTailMode.EXACT)
            override suspend fun chooseStems(frames: Long) = StemExportRequest(backend.files.register(stems), frames.toInt(), tailMode = ExportTailMode.EXACT)
            override val lyricFiles = DesktopLyricFiles { save -> if (save) lrc else directory.resolve("input.lrc") }
            override suspend fun createLoopOverdub(startFrame: Long, frames: Int, grid: IntArray, routes: List<LoopOverdubRoute>) =
                host.createLoopOverdub(startFrame, frames, grid, routes).also { lastCapture = it }
            override suspend fun analyseSource(asset: Asset, range: FrameRange): com.choplab.core.analysis.SourceMusicResult {
                val result = host.analyseSource(asset, range)
                analysisEntered?.complete(Unit)
                analysisRelease?.let { withContext(NonCancellable) { it.await() } }
                analysisReturned?.complete(Unit)
                return result
            }
            override val vocalGuide = object : VocalGuidePort {
                override val preview = host.vocalGuide.preview
                override fun createSynthesis() = VocalTtsService(object : TtsProvider {
                    override suspend fun voices() = TtsResult.Success(frozenListOf(voice))
                    override suspend fun synthesize(request: TtsRequest) = TtsResult.Success(TtsAudio.fromPcm(
                        FloatArray(44_100) { (.1 * sin(2 * PI * 220 * it / 22_050)).toFloat() }, 22_050, 1))
                    override fun close() = Unit
                }, TtsCache(directory.resolve("tts-fixture-cache")), backend.assets)
            }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        val project: Project get() = backend.studio.document.value.project
        suspend fun idle(label: String = "studio-idle") {
            try { until { backend.studio.work.value.jobId == null && backend.studio.work.value.preparationId == null } }
            catch (failure: TimeoutCancellationException) {
                val work = backend.studio.work.value
                val pcm = PcmMemoryBudget.shared.statistics()
                error("Whole creation $label: job=${work.jobId != null}; preparation=${work.preparationId != null}; " +
                    "status=${presenter.state.value.status}; driver=${backend.engine.status.value.phase}; " +
                    "pcmBytes=${pcm.usedBytes}; leasedAssets=${pcm.leasedAssets}; revision=${backend.studio.document.value.revision}")
            }
        }
        suspend fun editable() { idle(); until { presenter.state.value.permits(ContinuousCapability.TEMPO) } }
        suspend fun act(action: ContinuousEditorAction) { check(presenter.dispatch(action)) { "Rejected $action: ${presenter.state.value.status}" } }
        suspend fun edit(intent: Intent) { idle(); check(backend.studio.dispatch(Action.Edit(intent)).accepted) { "Rejected $intent" } }
        suspend fun oneUndo(before: DocumentState) {
            idle(); val after = backend.studio.document.value
            check(after.revision == before.revision + 1 && after.project != before.project) { "Expected one durable edit" }
            act(ContinuousEditorAction.Undo); check(project == before.project)
            act(ContinuousEditorAction.Redo); check(project == after.project); undoChecks++
        }
        suspend fun prepare() {
            until { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            val frequencies = doubleArrayOf(261.625565, 329.627557, 391.995436)
            val samples = FloatArray(12 * 48_000 * 2)
            for (frame in 0 until samples.size / 2) {
                val phase = frame % (48_000 * 60 / 98.0)
                val chord = frequencies.indices.sumOf { i -> sin(frame * 2 * PI * frequencies[i] / 48_000) * if (i == 0) .18 else .12 } * (.25 + .75 * exp(-phase / 7_000))
                val kick = if (phase < 4_800) .7 * sin(phase * 2 * PI * 78 / 48_000) * exp(-phase / 650) else 0.0
                samples[frame * 2] = (chord + kick).toFloat(); samples[frame * 2 + 1] = samples[frame * 2] * -.65f
            }
            Files.newOutputStream(sourcePath).use { WavCodec.writeFloat(it, samples) }
            check(backend.importAudio(sourcePath).accepted); idle()
            original = project.asset(requireNotNull(project.source).assetHash); originalHash = fileSha256(sourcePath)
            editable()
        }
        suspend fun sourceAndChop() {
            println("WHOLE_CREATION step=source-analysis-chop")
            edit(Intent.SetTempo(Tempo(120_000, 620))); editable()
            act(ContinuousEditorAction.OpenSourceAnalysis)
            val analysis = requireNotNull(presenter.sourceAnalysis.value)
            until { analysis.state.value.editable }
            val before = backend.studio.document.value
            check(analysis.dispatch(SourceAnalysisAction.Analyse))
            val result = requireNotNull(analysis.state.value.result)
            tempoCandidates = result.tempos.size
            check(result.keys.any { it.tonic == 0 && it.mode == com.choplab.core.analysis.KeyMode.MAJOR })
            val tempo = result.tempos.minBy { abs(it.milliBpm - 98_000) }
            check(abs(tempo.milliBpm - 98_000) <= 1000)
            check(project == before.project && analysis.state.value.selectedMilliBpm == null)
            check(analysis.dispatch(SourceAnalysisAction.SelectTempo(tempo.milliBpm))); check(project == before.project)
            check(analysis.dispatch(SourceAnalysisAction.Apply)); act(ContinuousEditorAction.CloseSourceAnalysis); oneUndo(before)
            check(project.tempo.swingPermille == 620)
            editable(); act(ContinuousEditorAction.AutoChop)
            val chop = requireNotNull(presenter.autoChop.value)
            val uncut = backend.studio.document.value
            check(chop.dispatch(AutoChopAction.Settings(AutoChopSettings(AutoChopMode.ATTACK, slices = 64, thresholdDb = -30))))
            check(chop.dispatch(AutoChopAction.Prepare)); until { !chop.state.value.working }
            check(chop.state.value.canApply) { "Attack proposal unavailable: ${chop.state.value.problem}" }
            check(project == uncut.project); check(chop.dispatch(AutoChopAction.Preview))
            check(chop.dispatch(AutoChopAction.StopPreview)); check(project == uncut.project)
            check(chop.dispatch(AutoChopAction.Apply)); act(ContinuousEditorAction.CloseAutoChop); oneUndo(uncut)
            check(project.source!!.markers.isNotEmpty())
            for ((slice, pad) in listOf(0 to 0, 1 to 16)) edit(Intent.AssignSlice(slice, pad))
            edit(Intent.SetPad(project.pads[0].copy(mode = PlayMode.GATE, releaseFrames = 192)))
            edit(Intent.SetPad(project.pads[16].copy(mode = PlayMode.GATE, reverse = true, pan = .8f)))
            mixBank(0, "65", "-25", "3", "40")
            mixBank(1, "80", "-80", "-2", "25")
            originalsUnchanged()
        }
        private suspend fun mixBank(bank: Int, gain: String, pan: String, low: String, send: String) {
            editable(); val before = backend.studio.document.value
            act(ContinuousEditorAction.Mixer(MixerAction.Open(MixerTarget.Bank(bank))))
            for ((field, text) in listOf(MixerField.GAIN to gain, MixerField.PAN to pan, MixerField.LOW_DB to low,
                MixerField.DELAY_SEND to send, MixerField.REVERB_SEND to "20")) act(ContinuousEditorAction.Mixer(MixerAction.Change(field, text)))
            check(project == before.project); act(ContinuousEditorAction.Mixer(MixerAction.Apply)); oneUndo(before)
        }
        suspend fun patternAndLoop() {
            println("WHOLE_CREATION step=step-repeat-loop")
            editable(); act(ContinuousEditorAction.OpenStepPatterns)
            var editor = requireNotNull(presenter.stepPatterns.value); until { editor.state.value.editable }
            check(editor.dispatch(PatternAction.Grid(160))); check(editor.dispatch(PatternAction.Resize(1)))
            check(editor.dispatch(PatternAction.Velocity(.7f)))
            for (step in listOf(0, 1, 3)) check(editor.dispatch(PatternAction.Toggle(step, 160)))
            check(editor.dispatch(PatternAction.SelectPad(16)))
            until { editor.state.value.selectedPadId == 16 }
            check(editor.dispatch(PatternAction.Toggle(2, 160)))
            val beforePattern = backend.studio.document.value
            check(editor.dispatch(PatternAction.Save)); act(ContinuousEditorAction.CloseStepPatterns); oneUndo(beforePattern)
            editable(); act(ContinuousEditorAction.OpenStepPatterns)
            editor = requireNotNull(presenter.stepPatterns.value); until { editor.state.value.editable }
            check(editor.dispatch(PatternAction.Repeats(2))); check(editor.dispatch(PatternAction.Queue))
            val beforePlace = backend.studio.document.value
            check(editor.dispatch(PatternAction.Place("Triplet beat"))); act(ContinuousEditorAction.CloseStepPatterns); oneUndo(beforePlace)
            check(project.patterns.first().notes.any { it.tick % 240 != 0 })
            val bankB = requireNotNull(project.banks[1].trackId)
            val oldClips = beforePlace.project.clips.map { it.id }.toSet()
            val placed = project.clips.filter { it.id !in oldClips }
            val bClips = placed.filter { it.startTick % (4 * ProjectLimits.PPQ) == 320L }
            check(bClips.size == 2 && bClips.all { it.trackId == bankB }) {
                "Expected two BANK B triplet notes: count=${bClips.size}; notes=${project.patterns.first().notes.map { listOf(it.padId, it.tick) }}; " +
                    "placedTicks=${placed.map { it.startTick }}; bankBMatched=${bClips.all { it.trackId == bankB }}"
            }
            check(placed.filter { it !in bClips }.all { it.trackId == project.banks[0].trackId })
            act(ContinuousEditorAction.SeekSong(0)); act(ContinuousEditorAction.SetGrid(ContinuousGrid.SIXTEENTH_TRIPLET))
            act(ContinuousEditorAction.SetNoteRepeat(ContinuousNoteRepeat.SIXTEENTH_TRIPLET))
            act(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(0)))
            val before = backend.studio.document.value
            act(ContinuousEditorAction.RecordLoopOverdub(1)); val take = requireNotNull(lastCapture).take
            check(!presenter.dispatch(ContinuousEditorAction.RecordVoice))
            check(!presenter.dispatch(ContinuousEditorAction.SaveProject))
            check(!presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE)))
            until { take.elapsedFrames > 2048 }; act(ContinuousEditorAction.HoldPad(0))
            until { take.elapsedFrames > 14_000 }; act(ContinuousEditorAction.ReleasePad(0))
            act(ContinuousEditorAction.SetNoteRepeat(ContinuousNoteRepeat.OFF))
            act(ContinuousEditorAction.HoldPad(16)); val held = take.elapsedFrames
            until { take.elapsedFrames > held + 4096 }; act(ContinuousEditorAction.ReleasePad(16))
            until { take.elapsedFrames >= take.frames * 2L + 20_000 }
            check(project == before.project && take.acceptedPresses == 2 && take.routeCount == 2)
            act(ContinuousEditorAction.StopHits); oneUndo(before)
            val routes = project.clips.drop(before.project.clips.size)
            check(routes.size == 2 && routes.map { it.trackId }.toSet().size == 2)
            check(routes.all { it.gain == 1f && it.pan == 0f })
            val canceled = backend.studio.document.value
            act(ContinuousEditorAction.SeekSong(0)); act(ContinuousEditorAction.RecordLoopOverdub(1))
            act(ContinuousEditorAction.HoldPad(0)); act(ContinuousEditorAction.CancelLoopOverdub)
            check(backend.studio.document.value == canceled)
        }
        suspend fun stretch() {
            println("WHOLE_CREATION step=stretch")
            editable(); act(ContinuousEditorAction.OpenBeatStretch(StretchTarget(StretchKind.PAD, "0")))
            val editor = requireNotNull(presenter.beatStretch.value); until { editor.state.value.editable }
            val before = backend.studio.document.value
            check(editor.dispatch(StretchAction.Bpm("120"))); check(editor.dispatch(StretchAction.Prepare))
            check(editor.state.value.prepared); check(editor.dispatch(StretchAction.Original))
            check(editor.dispatch(StretchAction.Stretched)); check(project == before.project)
            check(editor.dispatch(StretchAction.Apply)); act(ContinuousEditorAction.CloseBeatStretch); oneUndo(before)
            check(project.beatStretches.size == 1)
        }
        suspend fun lyricsAndVoice() {
            println("WHOLE_CREATION step=lyrics-guide-punch-comp")
            val placement = LyricProposal("Fixture", LyricLanguage.JAPANESE, frozenListOf(ProposalSection("Verse", LyricSectionKind.VERSE, 1,
                frozenListOf(ProposalLine.create("音の歌", "おとのうた", LyricLanguage.JAPANESE),
                    ProposalLine.create("歌おう", "うたおう", LyricLanguage.JAPANESE))))).placeStructured(0, 4, "whole-line")
            edit(Intent.SetStructuredLyrics(placement.lines, placement.structure)); editable()
            act(ContinuousEditorAction.OpenVocalGuide)
            val guide = requireNotNull(presenter.vocalGuide.value); until { !guide.state.value.loadingVoices }
            val beforeGuide = backend.studio.document.value
            check(guide.mode(null, FlowMode.ONE_BAR))
            check(guide.prepare()); until { guide.state.value.phase != VocalGuidePhase.PREPARING }
            check(guide.state.value.phase == VocalGuidePhase.READY) { "Guide preparation: ${guide.state.value.failure}, ${guide.state.value.rows.map { it.failure }}" }
            if (guide.state.value.plan?.hasDensityAdvice == true) guide.confirmDensity(true)
            check(guide.listen(placement.lines.first().id)); check(project == beforeGuide.project)
            check(guide.preview.stop() is TtsResult.Success)
            act(ContinuousEditorAction.CloseVocalGuide); check(backend.studio.document.value == beforeGuide)
            editable(); act(ContinuousEditorAction.OpenVocalPunch)
            val punch = requireNotNull(presenter.vocalPunch.value)
            punch.update { it.copy(startSeconds = "0", endSeconds = "6", countInBars = 1, preRollBars = 1, passes = 2) }
            val beforeTakes = backend.studio.document.value
            check(punch.record()) { "Punch failed: ${punch.state.value}" }
            check(project.takes.size == beforeTakes.project.takes.size + 2)
            act(ContinuousEditorAction.CloseVocalPunch); oneUndo(beforeTakes)
            // Native input may begin after zero, especially on Windows. Keep its exact ranges and move both full rows explicitly.
            val beforeTiming = backend.studio.document.value
            val timedLines = recordedLyricTiming(project, placement.lines)
            edit(Intent.SetStructuredLyrics(timedLines, placement.structure)); oneUndo(beforeTiming)
            // Apply the real UI's one-bar mode only after its timing fits both takes. No obsolete guide clips remain.
            editable(); act(ContinuousEditorAction.OpenVocalGuide)
            val alignedGuide = requireNotNull(presenter.vocalGuide.value); until { !alignedGuide.state.value.loadingVoices }
            val beforeAlignedGuide = backend.studio.document.value
            check(alignedGuide.mode(null, FlowMode.ONE_BAR))
            check(alignedGuide.prepare()); until { alignedGuide.state.value.phase != VocalGuidePhase.PREPARING }
            check(alignedGuide.state.value.phase == VocalGuidePhase.READY) { "Aligned guide preparation: ${alignedGuide.state.value.failure}" }
            if (alignedGuide.state.value.plan?.hasDensityAdvice == true) alignedGuide.confirmDensity(true)
            check(alignedGuide.listen(timedLines.first().id)); check(project == beforeAlignedGuide.project)
            check(alignedGuide.preview.stop() is TtsResult.Success); check(alignedGuide.apply())
            act(ContinuousEditorAction.CloseVocalGuide); oneUndo(beforeAlignedGuide)
            check(project.takes == beforeTiming.project.takes && project.lyricStructure == placement.structure)
            check(project.lyrics.map { it.startTick to it.endTick } == timedLines.map { it.startTick to it.endTick })
            val guideTracks = project.tracks.filter { it.kind == TrackKind.GUIDE }.map { it.id }.toSet()
            check(project.clips.filter { it.trackId in guideTracks }.map { it.startTick }.sorted() == timedLines.map { it.startTick })
            project.takes.forEach { VocalCompEdits.lines(project, it.id, "covered-guide") }
            editable(); act(ContinuousEditorAction.OpenVocalTakes)
            val comp = requireNotNull(presenter.vocalTakes.value); until { comp.state.value.editable }
            check(comp.dispatch(VocalAction.SelectTake(project.takes.first().id))) { "SelectTake: ${comp.state.value.problem}; ${compBounds(project)}" }
            check(comp.dispatch(VocalAction.FromLyrics)) { "FromLyrics: ${comp.state.value.problem}; ${compBounds(project)}" }
            val draft = requireNotNull(comp.state.value.draft)
            check(draft.segments.size == 2)
            check(comp.dispatch(VocalAction.Choose(draft.segments.last().id, project.takes.last().id)))
            val beforeComp = backend.studio.document.value
            check(comp.dispatch(VocalAction.PreviewComp("Fixture comp"))); check(comp.dispatch(VocalAction.StopPreview))
            check(project == beforeComp.project); check(comp.dispatch(VocalAction.Apply("Fixture comp")))
            act(ContinuousEditorAction.CloseVocalTakes); oneUndo(beforeComp)
            check(project.vocalComps.size == 1 && project.vocalComps.single().segments.map { it.takeId }.toSet().size == 2)
        }
        suspend fun practicePitchCoach() {
            println("WHOLE_CREATION step=practice-pitch-coach")
            editable(); act(ContinuousEditorAction.OpenVocalPractice)
            val practice = requireNotNull(presenter.vocalPractice.value)
            practice.update { it.copy(startSeconds = "0", endSeconds = "0.6", speed = .8) }
            val before = backend.studio.document.value
            check(practice.preview()); check(project == before.project)
            check(host.vocalPractice.preview.state.value.owner == VocalPreviewOwner.PRACTICE)
            // Pointer/keyboard input uses onAction's immediate owner guard. Internal dispatch may first close a modal.
            presenter.onAction(ContinuousEditorAction.PlayOriginal)
            yield()
            check(practice.state.value.phase == PracticePhase.PLAYING &&
                host.vocalPractice.preview.state.value.owner == VocalPreviewOwner.PRACTICE)
            practice.stopAndJoin(); act(ContinuousEditorAction.CloseVocalPractice); check(backend.studio.document.value == before)
            editable(); act(ContinuousEditorAction.OpenVocalPitch)
            val pitch = requireNotNull(presenter.vocalPitch.value); until { pitch.state.value.editable }
            check(pitch.dispatch(PitchAction.Prepare)); check(pitch.state.value.report != null && pitch.state.value.prepared)
            val beforePitch = backend.studio.document.value
            check(pitch.dispatch(PitchAction.PreviewOriginal)); check(pitch.dispatch(PitchAction.PreviewCorrected))
            check(project == beforePitch.project); check(pitch.dispatch(PitchAction.Apply))
            act(ContinuousEditorAction.CloseVocalPitch); oneUndo(beforePitch)
            check(project.pitchCorrections.isNotEmpty())
            editable(); act(ContinuousEditorAction.OpenVocalCoach)
            val coach = requireNotNull(presenter.vocalCoach.value)
            check(coach.dispatch(CoachAction.Take(project.takes.last().id)))
            check(coach.dispatch(CoachAction.Reference(project.takes.first().id)))
            check(coach.dispatch(CoachAction.Range("0", "1")))
            check(coach.dispatch(CoachAction.Input(CoachVoiceInput.VOICE_ONLY)))
            val beforeCoach = backend.studio.document.value
            check(coach.dispatch(CoachAction.Analyze))
            check(coach.state.value.report?.lines?.isNotEmpty() == true && coach.state.value.problem == null)
            check(coach.dispatch(CoachAction.ListenGuide)); until { coach.state.value.phase == CoachPhase.READY_RESPONSE }
            check(backend.studio.document.value == beforeCoach)
            check(coach.dispatch(CoachAction.Practice)); check(presenter.vocalCoach.value == null)
            val routedPractice = requireNotNull(presenter.vocalPractice.value)
            check(routedPractice.preview()); routedPractice.stopAndJoin(); act(ContinuousEditorAction.CloseVocalPractice)
            check(backend.studio.document.value == beforeCoach)
        }
        suspend fun fourStems() {
            println("WHOLE_CREATION step=four-stems")
            editable(); act(ContinuousEditorAction.OpenFourStems)
            val editor = requireNotNull(presenter.fourStems.value); until { editor.state.value.editable }
            val before = backend.studio.document.value
            check(editor.settings(firstBar = 3, allowModelDownload = false)); check(editor.start())
            until { editor.state.value.phase in listOf(FourStemPhase.READY, FourStemPhase.FAILED) }
            check(editor.state.value.phase == FourStemPhase.READY) { "Four stems: ${editor.state.value.problem}" }
            check(project == before.project); check(editor.apply()); act(ContinuousEditorAction.CloseFourStems); oneUndo(before)
            check(project.clips.size == before.project.clips.size + 4)
        }
        suspend fun mixAndExport() {
            println("WHOLE_CREATION step=mix-export")
            editable(); val before = backend.studio.document.value
            act(ContinuousEditorAction.Mixer(MixerAction.Open(MixerTarget.Master)))
            act(ContinuousEditorAction.Mixer(MixerAction.Switch(MixerSwitch.DELAY, true)))
            act(ContinuousEditorAction.Mixer(MixerAction.Switch(MixerSwitch.REVERB, true)))
            for ((field, value) in listOf(MixerField.GAIN to "75", MixerField.DELAY_TIME to "10", MixerField.FEEDBACK to "20"))
                act(ContinuousEditorAction.Mixer(MixerAction.Change(field, value)))
            check(project == before.project); act(ContinuousEditorAction.Mixer(MixerAction.Apply)); oneUndo(before)
            println("WHOLE_CREATION step=export-master")
            act(ContinuousEditorAction.ExportWav); idle("export-master"); check(Files.isRegularFile(wav))
            val wave = Files.newInputStream(wav).use(WavCodec::read)
            check(wave.info.bits == 24 && wave.info.sampleRate == 48_000 && wave.info.channels == 2)
            check(wave.samples.any { abs(it) > .01f } && wave.samples.indices.step(2).any { wave.samples[it] != wave.samples[it + 1] })
            val baseline = fileSha256(wav)
            println("WHOLE_CREATION step=export-master-with-source")
            act(ContinuousEditorAction.PlayOriginal); act(ContinuousEditorAction.ExportWav); idle("export-master-with-source")
            check(fileSha256(wav) == baseline) { "SOURCE monitor entered export" }
            act(ContinuousEditorAction.StopAll)
            println("WHOLE_CREATION step=export-stems")
            act(ContinuousEditorAction.ExportStems); idle("export-stems")
            ZipFile(stems.toFile()).use { zip ->
                val manifest = zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).use { it.readBytes().decodeToString() }
                check("POST_FADER_POST_INSERT_PRE_MASTER" in manifest)
                val entries = zip.entries().asSequence().filter { it.name.endsWith(".wav") }.toList()
                check(entries.size >= 6)
                entries.forEach { entry -> check(zip.getInputStream(entry).use(WavCodec::inspect).bits == 32) }
            }
            act(ContinuousEditorAction.Lyrics(LyricAction.Open)); act(ContinuousEditorAction.Lyrics(LyricAction.Export()))
            act(ContinuousEditorAction.Lyrics(LyricAction.Close))
            val imported = LrcCodec.parse(Files.readString(lrc), LyricTiming(project.tempo.milliBpm), ProjectLimits.MAX_TIMELINE_TICKS)
            check(imported is LyricResult.Success) { "LRC readback: ${(imported as LyricResult.Failure).issue.problem}" }
            val restored = imported.value.lines
            check(restored.map { it.text } == project.lyrics.map { it.text }) { "LRC line text changed" }
            project.lyrics.zip(restored).forEach { (saved, read) ->
                check(abs(saved.startTick - read.startTick) <= 2 && abs(saved.endTick - read.endTick) <= 2) { "LRC line timing changed" }
                check(saved.words.map { it.text } == read.words.map { it.text }) { "LRC word text changed" }
                saved.words.zip(read.words).forEach { (a, b) ->
                    check(abs(a.startTick - b.startTick) <= 2 && abs(a.endTick - b.endTick) <= 2) { "LRC word timing changed" }
                }
            }
        }
        suspend fun originalsUnchanged() { check(sha256(backend.assets.read(original)) == originalHash); check(fileSha256(sourcePath) == originalHash) }
        suspend fun close() {
            if (closed) return
            closed = true; analysisRelease?.complete(Unit)
            try { presenter.close(); host.close() } finally { try { backend.shutdown() } finally { scope.cancel() } }
        }
    }

    internal class ClockedSink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        private var started = 0L
        @Volatile var frames = 0L
            private set
        @Volatile private var closed = false
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!closed && length % 8 == 0)
            if (started == 0L) started = System.nanoTime()
            frames += length / 8
            val deadline = started + frames * 1_000_000_000L / 48_000
            while (!closed && System.nanoTime() < deadline) LockSupport.parkNanos((deadline - System.nanoTime()).coerceAtLeast(1))
            return length
        }
        override fun close() { closed = true }
    }
    private class SineMic(private val hz: Double) : MicInput {
        override val sampleRate = 48_000
        override val bufferFrames = 480
        private var frame = 0L
        private var started = 0L
        @Volatile private var stopped = false
        override fun read(buffer: FloatArray): Int {
            if (stopped) return -1
            if (started == 0L) started = System.nanoTime()
            val count = minOf(480, buffer.size)
            for (i in 0 until count) buffer[i] = (.12 * sin(2 * PI * hz * (frame + i) / sampleRate)).toFloat()
            frame += count
            val deadline = started + frame * 1_000_000_000L / sampleRate
            while (!stopped && System.nanoTime() < deadline) LockSupport.parkNanos((deadline - System.nanoTime()).coerceAtLeast(1))
            return if (stopped) -1 else count
        }
        override fun stop() { stopped = true }
        override fun close() = stop()
    }
    private class FixtureStems : FourStemSessionFactory {
        override fun open(memory: PcmMemoryBudget, available: SeparationMemory, allowDownload: Boolean, check: () -> Unit): FourStemInference {
            require(!allowDownload)
            return object : FourStemInference {
                override fun infer(channelMajor: FloatArray, check: () -> Unit, consume: (FloatBuffer) -> Unit) {
                    runBlocking { memory.reserve(FourStemSpec.NATIVE_IO_PCM_BYTES) }.use {
                        val output = FloatArray(FourStemSpec.FRAMES * 8) { at -> channelMajor[at % (FourStemSpec.FRAMES * 2)] * (at / (FourStemSpec.FRAMES * 2) + 1) * .1f }
                        check(); consume(FloatBuffer.wrap(output))
                    }
                }
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
    }
    private fun fileSha256(path: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0) { "File hash made no progress" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    internal suspend fun until(ready: suspend () -> Boolean) = withTimeout(30_000) { while (!ready()) delay(5) }
}
