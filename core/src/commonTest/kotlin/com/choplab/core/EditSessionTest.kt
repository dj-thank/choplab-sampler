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
