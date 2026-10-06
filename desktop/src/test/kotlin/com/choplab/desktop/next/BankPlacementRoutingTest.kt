package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.model.Pattern
import com.choplab.core.pattern.*
import com.choplab.engine.*
import com.choplab.jvm.WavCodec
import com.choplab.ui.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

/** Stereo PCM, insert/send processing, Undo and saved route identity through the production backend. */
class BankPlacementRoutingTest {
    @Test fun bankBPadAndFillKeepItsMixRatherThanBankA() = verify(Route.PAD)
    @Test fun bankBTripletStepKeepsItsMixRatherThanBankA() = verify(Route.STEP)
    @Test fun performedAndRepeatedHitsKeepEachBankMixWithoutBakingItTwice() = verify(Route.PERFORMANCE)
    private enum class Route { PAD, STEP, PERFORMANCE }

    private fun verify(route: Route) = runBlocking<Unit> {
        val root = Files.createTempDirectory("bank-placement-route-")
        val backend = create(root.resolve("profile"))
        var reopened: NextBackend? = null
        try {
            val input = root.resolve("source.wav")
            Files.newOutputStream(input).use { WavCodec.writeFloat(it,
                FloatArray(8192) { i -> (sin(i / 2 * .07) * if (i % 2 == 0) .16 else -.07).toFloat() }, 48_000, 2) }
            assertTrue(backend.importAudio(input).accepted); idle(backend)
            val imported = backend.studio.document.value.project
            val hash = assertNotNull(imported.source).assetHash
            val asset = imported.asset(hash)
            for (id in listOf(0, 16)) assertTrue(backend.studio.dispatch(Action.Edit(
                Intent.AssignRange(hash, FrameRange(0, 4096), id))).accepted)
            val a = Track("bank-a", "A", TrackKind.BANK, gain = .2f, pan = -1f,
                fx = TrackFx(insert = MixInsert(filter = MixFilter(MixFilterMode.LOW_PASS, 300f))))
            val b = Track("bank-b", "B", TrackKind.BANK, gain = .6f, pan = -.8f,
                fx = TrackFx(insert = MixInsert(eq = MixEq(highDb = -4f)), delaySend = .3f))
            for ((id, track) in listOf(0 to a, 1 to b)) assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetBankMix(id, track))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetMasterMix(MixSettings(
                delay = MixDelay(true, 73, 0f, .5f), masterGain = .75f)))).accepted)
            val panned = backend.studio.document.value.project.pads[16].copy(pan = .8f, gain = .7f)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetPad(panned))).accepted)
            val before = backend.studio.document.value.project
            val original = backend.assets.read(asset)
            var nextId = 0
            fun fresh(kind: String) = "$kind-${++nextId}"
            val intent = when (route) {
                Route.PAD -> {
                    val rendered = backend.renderPad(before.pads[16], asset)
                    val pcm = backend.assets.read(rendered).inputStream().use(WavCodec::read).samples
                    val raw = original.inputStream().use(WavCodec::read).samples
                    val left = kotlin.math.cos(.8f * kotlin.math.PI / 2).toFloat()
                    for (i in pcm.indices) assertEquals(raw[i] * if (i % 2 == 0) left else 1f, pcm[i], 1e-7f,
                        "PAD pan is applied before BANK pan; PAD gain remains the clip's gain")
                    ContinuousClipEdits.intent(before, ContinuousEditorAction.FillPad(16, null, 0,
                        ContinuousGrid.SIXTEENTH_TRIPLET, 1), ::fresh, rendered = mapOf(16 to rendered))
                }
                Route.STEP -> {
                    val pattern = Pattern("route", notes = frozenListOf(Note(0, 0), Note(160, 16, .7f), Note(320, 16)))
                    val source = before.copy(patterns = frozenListOf(pattern))
                    val plan = PatternPlacement.plan(source, listOf(SongSection("route")), 0)
                    val rendered = plan.renders.associateWith { request ->
                        backend.renderPerformance(source.pads[request.padId], asset, request.releaseAt, request.limitFrames, request.stopAt)
                    }
                    PatternPlacement.intent(source, plan, rendered, ::fresh, "STEP")
                }
                Route.PERFORMANCE -> {
                    val hits = listOf(ContinuousHit(0, 0, performed = true, limitFrames = 4096),
                        ContinuousHit(16, 8000, performed = true, limitFrames = 4096, repeatTicks = 160),
                        ContinuousHit(16, 12000, performed = true, limitFrames = 4096, repeatTicks = 160))
                    val rendered = hits.associateWith { hit ->
                        if (hit.repeatTicks > 0) backend.renderNoteRepeat(before.pads[hit.padId], asset, before.tempo,
                            hit.repeatTicks, 3000, hit.limitFrames, null)
                        else backend.renderPerformance(before.pads[hit.padId], asset, null, hit.limitFrames)
                    }
                    ContinuousClipEdits.intent(before, ContinuousEditorAction.PlaceHits(hits), ::fresh, performances = rendered)
                }
            }
            assertTrue(backend.studio.dispatch(Action.Edit(intent)).accepted)
            val saved = backend.studio.document.value.project
            val expectedTracks = if (route == Route.PAD) listOf("bank-b") else listOf("bank-a", "bank-b")
            assertEquals(expectedTracks.toSet(), saved.clips.map { it.trackId }.toSet())
            assertEquals(before.banks, saved.banks); assertEquals(before.tracks, saved.tracks)
            // Independently assign the declared BANK identities, then compare actual 24-bit samples.
            val oracleClips = saved.clips.mapIndexed { index, clip -> clip.copy(trackId =
                if (route != Route.PAD && index == 0) "bank-a" else "bank-b") }.frozen()
            val oracle = saved.copy(clips = oracleClips)
            val actual = export(backend, root.resolve("actual.wav"))
            assertTrue(actual.any { abs(it) > .001f })
            val undoRevision = backend.studio.document.value.revision
            assertTrue(backend.studio.dispatch(Action.Undo).accepted)
            assertEquals(before, backend.studio.document.value.project)
            assertEquals(undoRevision + 1, backend.studio.document.value.revision)
            assertTrue(backend.studio.dispatch(Action.Redo).accepted); assertEquals(saved, backend.studio.document.value.project)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement(oracle.tracks, oracle.clips, oracle.takes))).accepted)
            assertContentEquals(actual, export(backend, root.resolve("oracle.wav")))
            // A wrong BANK's very different fader/pan/filter must be audible in this fixture.
            val wrong = oracle.clips.map { it.copy(trackId = "bank-a") }.frozen()
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement(oracle.tracks, wrong, oracle.takes))).accepted)
            assertFalse(actual.contentEquals(export(backend, root.resolve("wrong.wav"))))
            assertTrue(backend.studio.dispatch(Action.Undo).accepted)
            val archive = root.resolve("song.choplab")
            assertTrue(backend.saveProject(archive).accepted); idle(backend)
            backend.flushAutosave()
            reopened = create(root.resolve("reopened"))
            assertTrue(reopened.openProject(archive).accepted); idle(reopened)
            assertEquals(saved, reopened.studio.document.value.project)
            assertContentEquals(original, reopened.assets.read(asset))
            assertContentEquals(actual, export(reopened, root.resolve("reopened.wav")))
        } finally { reopened?.shutdown(); backend.shutdown(); root.toFile().deleteRecursively() }
    }
    private fun create(path: Path) = NextBackend.create(path, sinkFactory = { error("No native device") }, microphone = { null })
    private suspend fun idle(backend: NextBackend) = withTimeout(10_000) {
        while (backend.studio.work.value.jobId != null || backend.studio.work.value.preparationId != null) delay(5)
    }
    private suspend fun export(backend: NextBackend, path: Path): FloatArray {
        check(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(path), 120_000,
            bits = 24, tailMode = ExportTailMode.EXACT), PlaybackTarget.Arrangement())).accepted)
        idle(backend)
        return Files.newInputStream(path).use(WavCodec::read).samples
    }
}
