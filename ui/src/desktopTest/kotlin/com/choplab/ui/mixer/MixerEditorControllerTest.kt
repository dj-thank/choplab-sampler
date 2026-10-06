package com.choplab.ui.mixer

import com.choplab.core.Action
import com.choplab.core.edit.Intent
import com.choplab.engine.EngineCommand
import com.choplab.engine.MixFilterMode
import com.choplab.ui.BankPadEditAction
import com.choplab.ui.BankPadEditField
import com.choplab.ui.BankPadEditorHarness
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MixerEditorControllerTest {
    @Test fun aBankDraftIsSilentAndCreatesItsRealRouteWithAllMixValuesInOneUndo() = runBlocking {
        val h = BankPadEditorHarness()
        val controller = MixerEditorController(h.studio) { intent, revision -> h.studio.dispatch(Action.Edit(intent, revision)).accepted }
        suspend fun set(field: MixerField, text: String) = assertTrue(controller.dispatch(MixerAction.Change(field, text)))
        try {
            h.studio.dispatch(Action.Play); h.studio.dispatch(Action.Trigger(0))
            val before = h.studio.document.value
            val commands = h.engine.commands.size
            assertTrue(controller.dispatch(MixerAction.Open()))
            set(MixerField.GAIN, "65")
            assertTrue(controller.dispatch(MixerAction.Cancel))
            assertEquals(before, h.studio.document.value); assertEquals(commands, h.engine.commands.size)
            assertTrue(controller.dispatch(MixerAction.Open()))
            set(MixerField.GAIN, "65"); set(MixerField.PAN, "-25")
            set(MixerField.LOW_DB, "3"); set(MixerField.MID_DB, "2"); set(MixerField.HIGH_DB, "-1")
            controller.dispatch(MixerAction.Filter(MixFilterMode.LOW_PASS)); set(MixerField.CUTOFF, "1000")
            controller.dispatch(MixerAction.Switch(MixerSwitch.COMPRESSOR, true))
            set(MixerField.THRESHOLD, "-24"); set(MixerField.RATIO, "2"); set(MixerField.ATTACK, "5")
            set(MixerField.RELEASE, "200"); set(MixerField.MAKEUP, "3")
            set(MixerField.DELAY_SEND, "25"); set(MixerField.REVERB_SEND, "40")
            controller.dispatch(MixerAction.Switch(MixerSwitch.SOLO, true))
            assertEquals(before, h.studio.document.value); assertEquals(commands, h.engine.commands.size)
            assertTrue(h.engine.transport.playing)
            assertFalse(controller.dispatch(MixerAction.Select(MixerTarget.Master)))
            assertEquals(MixerProblem.UNAPPLIED, controller.view.value.problem)
            assertTrue(controller.dispatch(MixerAction.Apply))
            val project = h.studio.document.value.project
            val bankTrack = project.tracks.single { it.id == project.banks[0].trackId }
            assertEquals(.65f, bankTrack.gain); assertEquals(-.25f, bankTrack.pan); assertTrue(bankTrack.solo)
            assertEquals(3f, bankTrack.fx.insert.eq.lowDb); assertEquals(2f, bankTrack.fx.insert.eq.midDb); assertEquals(-1f, bankTrack.fx.insert.eq.highDb)
            assertEquals(MixFilterMode.LOW_PASS, bankTrack.fx.insert.filter.mode); assertEquals(1000f, bankTrack.fx.insert.filter.cutoffHz)
            with(bankTrack.fx.insert.compressor) {
                assertTrue(enabled); assertEquals(-24f, thresholdDb); assertEquals(2f, ratio); assertEquals(5f, attackMs)
                assertEquals(200f, releaseMs); assertEquals(3f, makeupDb)
            }
            assertEquals(.25f, bankTrack.fx.delaySend); assertEquals(.4f, bankTrack.fx.reverbSend)
            assertEquals(before.project.pads, project.pads); assertEquals(before.project.clips, project.clips)
            assertEquals(before.project.source, project.source)
            val published = h.engine.commands.filterIsInstance<EngineCommand.SwapProgram>().last().program
            assertEquals(bankTrack.id, published.mixer.busId(published.pad(0)!!.mixBus))
            assertEquals(.65f, published.pad(0)!!.mixGain)
            assertEquals(before.revision + 1, h.studio.document.value.revision)
            assertTrue(h.studio.dispatch(Action.Undo).accepted); assertEquals(before.project, h.studio.document.value.project)
            assertFalse(h.studio.document.value.canUndo)
            assertTrue(h.studio.dispatch(Action.Redo).accepted); assertEquals(project, h.studio.document.value.project)

            // The pre-existing metadata form must preserve the newly saved audio route.
            h.controller.dispatch(BankPadEditAction.OpenBank); h.change(BankPadEditField.NAME, "Rhythm")
            assertTrue(h.controller.dispatch(BankPadEditAction.Apply))
            assertEquals(bankTrack.id, h.studio.document.value.project.banks[0].trackId)
            assertEquals(bankTrack, h.studio.document.value.project.tracks.last())
        } finally { h.close() }
    }

    @Test fun masterAndSharedReturnsApplyTogetherAndAnUnchangedEditorIsNotAnUndo() = runBlocking {
        val h = BankPadEditorHarness()
        val controller = MixerEditorController(h.studio) { intent, revision -> h.studio.dispatch(Action.Edit(intent, revision)).accepted }
        try {
            assertTrue(controller.dispatch(MixerAction.Open(MixerTarget.Master)))
            assertTrue(controller.dispatch(MixerAction.Apply))
            assertEquals(0, h.studio.document.value.revision)
            controller.dispatch(MixerAction.Open(MixerTarget.Master))
            suspend fun set(field: MixerField, value: String) { assertTrue(controller.dispatch(MixerAction.Change(field, value))) }
            set(MixerField.GAIN, "80"); set(MixerField.LOW_DB, "-3")
            controller.dispatch(MixerAction.Switch(MixerSwitch.DELAY, true)); set(MixerField.DELAY_TIME, "0.021")
            set(MixerField.FEEDBACK, "60"); set(MixerField.DELAY_RETURN, "120")
            controller.dispatch(MixerAction.Switch(MixerSwitch.REVERB, true)); set(MixerField.REVERB_DECAY, "3")
            set(MixerField.DAMPING, "95"); set(MixerField.REVERB_RETURN, "75")
            assertFalse(controller.dispatch(MixerAction.Switch(MixerSwitch.MUTE, true)))
            assertTrue(controller.dispatch(MixerAction.Apply))
            val after = h.studio.document.value.project
            with(after.mix) {
                assertEquals(.8f, masterGain); assertEquals(-3f, master.eq.lowDb)
                assertTrue(delay.enabled); assertEquals(1, delay.frames); assertEquals(.6f, delay.feedback); assertEquals(1.2f, delay.returnGain)
                assertTrue(reverb.enabled); assertEquals(3f, reverb.decaySeconds); assertEquals(.95f, reverb.damping); assertEquals(.75f, reverb.returnGain)
            }
            assertEquals(h.initial.copy(mix = after.mix), after)
            assertTrue(h.studio.dispatch(Action.Undo).accepted); assertEquals(h.initial, h.studio.document.value.project)
            assertFalse(h.studio.document.value.canUndo)
        } finally { h.close() }
    }

    @Test fun invalidStaleBusyRecordingAndFailedAcknowledgmentNeverReplaceTheOriginalMix() = runBlocking {
        val h = BankPadEditorHarness()
        var blocked: MixerProblem? = null
        var race = false
        val controller = MixerEditorController(h.studio, { blocked }) { intent, revision ->
            if (race) h.studio.dispatch(Action.Edit(Intent.Rename("Concurrent edit")))
            h.studio.dispatch(Action.Edit(intent, revision)).accepted
        }
        try {
            for (reason in listOf(MixerProblem.BUSY, MixerProblem.RECORDING)) {
                blocked = reason
                assertFalse(controller.dispatch(MixerAction.Open()))
                assertNull(controller.view.value.draft)
            }
            blocked = null
            controller.dispatch(MixerAction.Open(MixerTarget.Master))
            for ((field, value) in listOf(MixerField.GAIN to "NaN", MixerField.CUTOFF to "Infinity", MixerField.FEEDBACK to "61",
                MixerField.DELAY_TIME to "0.001", MixerField.REVERB_DECAY to "3.01")) {
                assertTrue(controller.dispatch(MixerAction.Change(field, value)))
                assertTrue(field in controller.view.value.invalidFields)
                assertFalse(controller.view.value.canApply)
                assertFalse(controller.dispatch(MixerAction.Apply))
            }
            assertEquals(h.initial, h.studio.document.value.project)
            controller.dispatch(MixerAction.Cancel); controller.dispatch(MixerAction.Open())
            controller.dispatch(MixerAction.Change(MixerField.GAIN, "75"))
            blocked = MixerProblem.RECORDING
            assertFalse(controller.dispatch(MixerAction.Apply)); assertEquals(h.initial, h.studio.document.value.project)
            blocked = null
            h.engine.deny = { it is EngineCommand.SwapProgram }
            assertFalse(controller.dispatch(MixerAction.Apply))
            assertEquals(MixerProblem.APPLY_FAILED, controller.view.value.problem)
            assertEquals(h.initial, h.studio.document.value.project); assertFalse(h.studio.document.value.canUndo)
            h.engine.deny = { false }
            race = true
            assertFalse(controller.dispatch(MixerAction.Apply))
            assertEquals(MixerProblem.STALE, controller.view.value.problem)
            assertFalse(controller.view.value.canApply)
            assertEquals(h.initial.copy(title = "Concurrent edit"), h.studio.document.value.project)
            assertTrue(controller.dispatch(MixerAction.Cancel)); assertNull(controller.view.value.draft)
            assertTrue(h.studio.dispatch(Action.Undo).accepted); assertEquals(h.initial, h.studio.document.value.project)
        } finally { h.close() }
    }
}
