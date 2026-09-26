package com.choplab.core

import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import kotlin.test.*

class EditSessionTest {
    private val asset = Asset("a".repeat(64), "wav", 100, 48_000, 2, 7, "sample.wav")
    private fun apply(session: EditSession, intent: Intent): Project = commit(session, session.plan(intent))
    private fun commit(session: EditSession, plan: EditPlan): Project {
        plan.effects.indices.forEach { session.acknowledge(plan, it) }
        return session.commit(plan)
    }

    @Test fun immutableCollectionsAndDocumentBounds() {
        val mutable = mutableListOf(asset)
        val project = Project(assets = mutable.frozen())
        mutable.clear()
        assertEquals(1, project.assets.size)
        assertFalse((project.assets as Any) is MutableList<*>)
        assertFailsWith<IllegalArgumentException> { Project(banks = (0..6).map(::Bank).frozen()) }
        assertFailsWith<IllegalArgumentException> { Project(pads = (0..128).map { Pad(it % 128) }.frozen()) }
        assertFailsWith<IllegalArgumentException> { Project(assets = frozenListOf(asset, asset)) }
        assertFailsWith<IllegalArgumentException> { Project(title = "bad\nname") }
        assertEquals("Verse/Chorus: A", Project(title = "Verse/Chorus: A").title)
        assertFailsWith<IllegalArgumentException> { Pattern("p", bars = 9) }
        assertFailsWith<IllegalArgumentException> { Pad(0, gain = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Project(patterns = frozenListOf(Pattern("p", notes = frozenListOf(Note(0, 0))))) }
        assertFailsWith<IllegalArgumentException> { Project(assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(0, 8))) }
    }

    @Test fun importChopAssignPatternAndKitPreserveUnrelatedMusic() {
        val session = EditSession()
        apply(session, Intent.ImportAsset(asset))
        apply(session, Intent.EqualChop(3))
        assertEquals(listOf(FrameRange(0, 2), FrameRange(2, 4), FrameRange(4, 7)), session.project.source!!.slices())
        apply(session, Intent.AssignSlice(2, 127))
        apply(session, Intent.FillPadPattern("pattern-1", 127, 960))
        apply(session, Intent.ShiftPadPattern("pattern-1", 127, -240))
        assertEquals(listOf(720, 1680, 2640, 3600), session.project.patterns.first().notes.map { it.tick })
        val beforeKit = session.project
        apply(session, Intent.ApplyKit(frozenListOf(asset), frozenListOf(Pad(0, asset.hash, FrameRange(0, 2)))))
        assertEquals(beforeKit.source, session.project.source)
        assertEquals(beforeKit.pads[127], session.project.pads[127])
        assertEquals(beforeKit.patterns, session.project.patterns)
        assertEquals(128, session.project.pads.size)
        assertEquals(8, session.project.banks.size)
    }

    @Test fun failedEffectDoesNotPublishDocumentOrConsumeUndo() {
        val session = EditSession()
        apply(session, Intent.ImportAsset(asset))
        apply(session, Intent.AssignSlice(0, 0))
        val before = session.project
        val revision = session.revision
        val plan = session.plan(Intent.ClearPad(0))
        assertIs<Effect.StopPads>(plan.effects.first())
        assertFailsWith<IllegalArgumentException> { session.commit(plan) }
        session.cancel(plan)
        assertEquals(before, session.project)
        assertEquals(revision, session.revision)
        assertEquals(2, session.undoCount)
        val undoPlan = assertNotNull(session.planUndo())
        session.cancel(undoPlan)
        assertEquals(before, session.project)
        assertEquals(2, session.undoCount)
        assertFalse(session.canRedo)
    }

    @Test fun staleForeignAndRepeatedPlansAreRejected() {
        val session = EditSession()
        val old = session.plan(Intent.Rename("First"))
        val newer = session.plan(Intent.Rename("Second"))
        assertFailsWith<IllegalArgumentException> { session.acknowledge(old, 0) }
        assertFailsWith<IllegalArgumentException> { EditSession().acknowledge(newer, 0) }
        commit(session, newer)
        assertFailsWith<IllegalArgumentException> { session.commit(newer) }
        val failed = session.plan(Intent.Rename("Third"))
        assertFailsWith<IllegalArgumentException> { session.plan(Intent.EqualChop(0)) }
        assertFailsWith<IllegalArgumentException> { session.acknowledge(failed, 0) }
    }

    @Test fun songKeyIsAnUndoableSourceSettingThatANewImportResets() {
        val session = EditSession()
        assertFailsWith<IllegalArgumentException>("No source yet") { session.plan(Intent.SetSourcePitch(3.0)) }
        apply(session, Intent.ImportAsset(asset))
        apply(session, Intent.EqualChop(3))
        apply(session, Intent.AssignSlice(1, 0))
        val chopped = session.project
        apply(session, Intent.SetSourcePitch(2.0, "key-1"))
        apply(session, Intent.SetSourcePitch(3.0, "key-1"))
        assertEquals(chopped.source!!.copy(pitchSemitones = 3.0), session.project.source, "Only the key changes; the range and chops stay")
        assertEquals(chopped.pads, session.project.pads, "PADs never take the song key")
        commit(session, assertNotNull(session.planUndo()))
        assertEquals(chopped, session.project, "One gesture is one Undo")
        apply(session, Intent.SetSourcePitch(-24.0))
        for (bad in doubleArrayOf(24.5, -24.5, Double.NaN)) assertFailsWith<IllegalArgumentException>("$bad") { session.plan(Intent.SetSourcePitch(bad)) }
        apply(session, Intent.ImportAsset(asset))
        assertEquals(0.0, session.project.source!!.pitchSemitones, "A newly imported source starts at its own key")
    }

    @Test fun liveChopCutsAtEachTapAndEndsEachChopWhereTheNextOneStarts() {
        val song = Asset("c".repeat(64), "wav", 100, 48_000, 2, 48_000, "song.wav")
        val session = EditSession()
        assertFailsWith<IllegalArgumentException>("No source yet") { session.plan(Intent.LiveChop(3, 1_000)) }
        apply(session, Intent.ImportAsset(song))
        apply(session, Intent.SetSourceRange(FrameRange(500, 40_000)))
        apply(session, Intent.SetPad(session.project.pads[3].copy(pitchSemitones = 5.0, tone = .5f, gain = .7f)))
        fun pad(id: Int) = session.project.pads[id]

        apply(session, Intent.LiveChop(3, 1_000))
        assertEquals(FrameRange(1_000, 40_000), pad(3).range, "The first chop runs to the range end")
        assertEquals(song.hash, pad(3).assetHash)
        assertEquals(Triple(5.0, .5f, .7f), Triple(pad(3).pitchSemitones, pad(3).tone, pad(3).gain), "Other PAD settings stay")
        apply(session, Intent.LiveChop(1, 20_000, frozenListOf(3)))
        assertEquals(FrameRange(1_000, 20_000), pad(3).range, "An earlier chop ends where the next one starts")
        assertEquals(FrameRange(20_000, 40_000), pad(1).range)
        // Chops keep their order in time, not in tap order.
        apply(session, Intent.LiveChop(2, 10_000, frozenListOf(3, 1)))
        assertEquals(listOf(FrameRange(1_000, 10_000), FrameRange(10_000, 20_000), FrameRange(20_000, 40_000)), listOf(pad(3), pad(2), pad(1)).map { it.range })
        assertEquals(listOf(1_000L, 10_000L, 20_000L), session.project.source!!.markers)
        val beforeRetap = session.project
        // Tapping a chopped PAD again moves it; the others close up around it.
        apply(session, Intent.LiveChop(3, 30_000, frozenListOf(3, 1, 2)))
        assertEquals(listOf(FrameRange(10_000, 20_000), FrameRange(20_000, 30_000), FrameRange(30_000, 40_000)), listOf(pad(2), pad(1), pad(3)).map { it.range })
        assertEquals(listOf(1_000L, 10_000L, 20_000L, 30_000L), session.project.source!!.markers, "Earlier markers stay")
        commit(session, assertNotNull(session.planUndo()))
        assertEquals(beforeRetap, session.project, "Each tap is one Undo")

        // Only this pass, this bank and this source take part; a tap before the range starts at the range start.
        apply(session, Intent.SetPad(session.project.pads[17].copy(assetHash = song.hash, range = FrameRange(600, 700))))
        apply(session, Intent.LiveChop(4, 35_000, frozenListOf(17)))
        assertEquals(FrameRange(600, 700), pad(17).range, "Another bank's PAD is not part of this pass")
        assertEquals(FrameRange(35_000, 40_000), pad(4).range)
        assertFailsWith<IllegalArgumentException>("A tap after the range cuts nothing") { session.plan(Intent.LiveChop(4, 40_000)) }
        apply(session, Intent.LiveChop(5, 0))
        assertEquals(FrameRange(500, 40_000), pad(5).range)
        assertFalse(500L in session.project.source!!.markers, "No marker on the range edge")
        // PADs cut at the same moment share the chop; neither is left with a single frame.
        apply(session, Intent.LiveChop(6, 0, frozenListOf(5)))
        assertEquals(listOf(FrameRange(500, 40_000), FrameRange(500, 40_000)), listOf(pad(5).range, pad(6).range))
        apply(session, Intent.LiveChop(7, 30_000, frozenListOf(5, 6)))
        assertEquals(listOf(FrameRange(500, 30_000), FrameRange(500, 30_000), FrameRange(30_000, 40_000)), listOf(pad(5).range, pad(6).range, pad(7).range))
    }

    @Test fun coalescingIsOneUndoAndHistoryCapsAtOneHundred() {
        val session = EditSession()
        apply(session, Intent.SetTempo(Tempo(121_000), "drag-1"))
        apply(session, Intent.SetTempo(Tempo(122_000), "drag-1"))
        assertEquals(1, session.undoCount)
        assertEquals(2L, session.revision)
        commit(session, assertNotNull(session.planUndo()))
        assertEquals(120_000, session.project.tempo.milliBpm)
        commit(session, assertNotNull(session.planRedo()))
        assertEquals(122_000, session.project.tempo.milliBpm)
        repeat(105) { apply(session, Intent.Rename("Project $it")) }
        assertEquals(100, session.undoCount)
        repeat(100) { commit(session, assertNotNull(session.planUndo())) }
        assertNull(session.planUndo())
        assertEquals("Project 4", session.project.title)
    }

    @Test fun assetProtectionIncludesRedoAndReplacementClearsHistory() {
        val session = EditSession()
        apply(session, Intent.ImportAsset(asset))
        commit(session, assertNotNull(session.planUndo()))
        assertTrue(asset.hash in session.protectedAssets())
        commit(session, session.planReplace(Project(id = "new")))
        assertTrue(session.protectedAssets().isEmpty())
        assertFalse(session.canUndo)
        assertFalse(session.canRedo)
    }

    @Test fun noOpDoesNotAdvanceRevisionAndReplacementAlwaysRefreshes() {
        val session = EditSession()
        val plan = session.plan(Intent.Rename("Untitled"))
        assertEquals(Mutation.NONE, plan.mutation)
        assertTrue(plan.effects.isEmpty())
        session.commit(plan)
        assertEquals(0L, session.revision)
        val replace = session.planReplace(Project())
        assertEquals<List<Effect>>(listOf(Effect.PublishProject), replace.effects)
        commit(session, replace)
        assertEquals(1L, session.revision)
    }
}
