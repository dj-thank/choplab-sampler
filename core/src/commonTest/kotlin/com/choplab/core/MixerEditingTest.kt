package com.choplab.core

import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.EngineCommand
import com.choplab.engine.MixEq
import com.choplab.engine.MixInsert
import com.choplab.engine.MixSettings
import com.choplab.engine.MixDelay
import com.choplab.engine.MixReverb
import com.choplab.engine.MixCompressor
import com.choplab.engine.MixerProgram
import com.choplab.engine.OfflineRender
import com.choplab.engine.PadPerformanceRender
import com.choplab.engine.PcmAsset
import com.choplab.engine.TrackFx
import kotlinx.coroutines.test.runTest
import kotlin.math.*
import kotlin.test.*

class MixerEditingTest {
    private val source = Asset("a".repeat(64), "wav", 32812, 48_000, 2, 4096, "source")
    private val track = Track("bank-a", "A", TrackKind.BANK, .4f, .35f,
        fx = TrackFx(MixInsert(MixEq(3f, -2f, 1f), compressor = MixCompressor(true, -24f)), .3f, .2f))
    private val samples = FloatArray(8192) { if (it % 2 == 0) (.07 * sin(it * .03)).toFloat() else (.013 * cos(it * .07)).toFloat() }
    private fun project(): Project = Project(assets = frozenListOf(source), tracks = frozenListOf(track),
        banks = (0..7).map { Bank(it, trackId = if (it == 0) track.id else null) }.frozen(),
        pads = (0..127).map { if (it == 0) Pad(0, source.hash, FrameRange(0, 4096), gain = .7f, pan = -.4f, attackFrames = 37) else Pad(it) }.frozen(),
        patterns = frozenListOf(Pattern("pattern-1", notes = frozenListOf(Note(0, 0)))),
        mix = MixSettings(MixDelay(true, 97, .2f), MixReverb(true, .1f), masterGain = .8f))
    private fun commit(session: EditSession, plan: EditPlan): Project {
        plan.effects.indices.forEach { session.acknowledge(plan, it) }
        return session.commit(plan)
    }

    @Test fun firstBankRouteAndTrackAreAtomicAndNeverHijackAnExistingIdentity() {
        val initial = Project(tracks = frozenListOf(track))
        val session = EditSession(initial)
        assertFailsWith<IllegalArgumentException> { session.plan(Intent.SetBankMix(0, track)) }
        assertFailsWith<IllegalArgumentException> { session.plan(Intent.SetBankMix(8, track.copy(id = "new"))) }
        val routed = track.copy(id = "new-bank")
        val after = commit(session, session.plan(Intent.SetBankMix(0, routed)))
        assertEquals(routed.id, after.banks[0].trackId); assertEquals(frozenListOf(track, routed), after.tracks)
        assertEquals(1, session.undoCount)
        assertFailsWith<IllegalArgumentException> { session.plan(Intent.SetBankMix(0, track)) }
        assertFailsWith<IllegalArgumentException> { session.plan(Intent.SetBankMix(0, routed.copy(name = "Different"))) }
        commit(session, requireNotNull(session.planUndo())); assertEquals(initial, session.project)
        commit(session, requireNotNull(session.planRedo())); assertEquals(after, session.project)
    }

    @Test fun appliedMixIsOneUndoAndCancellationFailedEffectsAndStalePlansKeepOldMusic() {
        val initial = project()
        val session = EditSession(initial)
        val changed = track.copy(gain = .6f, pan = -.2f, mute = true, fx = TrackFx())
        val draft = session.plan(Intent.SetTrackMix(changed))
        assertEquals(initial, session.project)
        assertEquals(frozenListOf(0), assertIs<Effect.StopPads>(draft.effects.first()).ids)
        assertFailsWith<IllegalArgumentException> { session.commit(draft) }
        session.cancel(draft)
        assertEquals(0, session.undoCount); assertEquals(initial, session.project)
        val stale = session.plan(Intent.SetTrackMix(changed))
        val accepted = commit(session, session.plan(Intent.SetMasterMix(MixSettings(masterGain = .5f))))
        assertFailsWith<IllegalArgumentException> { session.acknowledge(stale, 0) }
        assertEquals(1, session.undoCount)
        commit(session, requireNotNull(session.planUndo())); assertEquals(initial, session.project)
        commit(session, requireNotNull(session.planRedo())); assertEquals(accepted, session.project)
        commit(session, session.plan(Intent.SetTrackMix(changed))); assertEquals(2, session.undoCount)
        assertEquals(initial.pads, session.project.pads)
        assertEquals(changed, session.project.tracks.single())
        assertFailsWith<IllegalArgumentException> { session.plan(Intent.SetTrackMix(changed.copy(kind = TrackKind.SOURCE))) }
        assertFailsWith<IllegalArgumentException> { session.plan(Intent.SetTrackMix(changed.copy(id = "missing"))) }
        assertFailsWith<IllegalArgumentException> { session.plan(Intent.SetMasterMix(MixSettings(masterGain = Float.NaN))) }
        assertEquals(2, session.undoCount)
    }

    @Test fun livePadAndItsRawPerformanceBakeApplyTrackAndSharedFxExactlyOnce() = runTest {
        val original = PcmAsset.fromInterleaved(samples)
        val p = project()
        val raw = ProgramCompiler.enginePad(p.pads[0], source, original)
        val baked = PadPerformanceRender.render(raw, null, 4096)
        val derived = source.copy(hash = "b".repeat(64), role = AssetRole.RENDERED, derivedFrom = source.hash)
        val arrangement = p.copy(assets = frozenListOf(source, derived),
            clips = frozenListOf(Clip("performed", track.id, derived.hash, FrameRange(0, 4096))))
        val pcm = object : PcmPort {
            override suspend fun load(asset: Asset) = if (asset.hash == source.hash) original else PcmAsset.fromInterleaved(baked)
        }
        val compiler = ProgramCompiler(pcm)
        val live = compiler.compile(p, "pattern-1", 0)
        val played = compiler.compile(arrangement, PlaybackTarget.Arrangement(), 0)
        try {
            assertEquals(p.pads[0].gain, live.pad(0)!!.gain)
            assertEquals(p.pads[0].pan, live.pad(0)!!.pan)
            assertEquals(track.gain, live.pad(0)!!.mixGain)
            assertEquals(track.pan, live.pad(0)!!.mixPan)
            val commands = listOf(EngineCommand.StartSequence(0, 1), EngineCommand.Stop(4096, 2))
            val a = OfflineRender.render(live, commands, 4096, tailFrames = live.mixer.tailFrames)
            val b = OfflineRender.render(played, commands, 4096, tailFrames = played.mixer.tailFrames)
            assertEquals(a.size, b.size)
            assertTrue(a.any { it != 0f })
            var largest = 0.0
            for (i in a.indices) largest = max(largest, abs(a[i].toDouble() - b[i]))
            assertTrue(largest < 2e-7, "Only the raw float bake's rounding is permitted, max=$largest")
            assertContentEquals(samples, FloatArray(samples.size) { original.sample(it / 2, it % 2) })
        } finally { live.releasePreparation(); played.releasePreparation() }
    }

    @Test fun routedMuteSoloAndUnroutedLegacyAuditionUseTheExplicitSavedRoute() = runTest {
        val compiler = ProgramCompiler(object : PcmPort { override suspend fun load(asset: Asset) = PcmAsset.fromInterleaved(samples) })
        val p = project()
        suspend fun pad(project: Project): com.choplab.engine.Pad {
            val program = compiler.compile(project, "pattern-1", 0)
            return try { requireNotNull(program.pad(0)) } finally { program.releasePreparation() }
        }
        assertEquals(0f, pad(p.copy(tracks = frozenListOf(track.copy(mute = true)))).mixGain)
        val other = Track("other", "Other", TrackKind.VOCAL, solo = true)
        assertEquals(0f, pad(p.copy(tracks = frozenListOf(track, other))).mixGain)
        assertEquals(track.gain, pad(p.copy(tracks = frozenListOf(track.copy(solo = true), other))).mixGain)
        val legacy = p.copy(banks = (0..7).map(::Bank).frozen(), tracks = frozenListOf(track, other))
        val unrouted = pad(legacy)
        assertEquals(MixerProgram.UNROUTED_BUS, unrouted.mixBus); assertEquals(1f, unrouted.mixGain); assertEquals(0f, unrouted.mixPan)
        assertFailsWith<IllegalArgumentException> { p.copy(banks = p.banks.map { if (it.id == 0) it.copy(trackId = "missing") else it }.frozen()) }
        assertFailsWith<IllegalArgumentException> { p.copy(tracks = frozenListOf(track.copy(kind = TrackKind.VOCAL))) }
    }
}
