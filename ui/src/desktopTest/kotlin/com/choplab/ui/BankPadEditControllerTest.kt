package com.choplab.ui

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.engine.PcmAsset
import com.choplab.engine.PlayMode
import kotlinx.coroutines.*
import kotlin.math.roundToInt
import kotlin.test.*

class BankPadEditControllerTest {
    @Test fun bankDraftIsSilentCancelableAndCommitsAllMetadataAsOneUndo() = runBlocking {
        val h = BankPadEditorHarness()
        try {
            val before = h.studio.document.value
            assertTrue(h.controller.dispatch(BankPadEditAction.OpenBank))
            h.change(BankPadEditField.NAME, "太鼓と声")
            h.change(BankPadEditField.COLOR, "#fFaA13")
            h.change(BankPadEditField.ROLE, "自由な役割")
            assertEquals(before, h.studio.document.value)
            assertTrue(h.engine.commands.isEmpty(), "Draft changes never publish or stop a voice")
            assertTrue(h.controller.dispatch(BankPadEditAction.Cancel))
            assertEquals(before, h.studio.document.value)
            assertFalse(h.controller.dispatch(BankPadEditAction.Apply))

            assertTrue(h.controller.dispatch(BankPadEditAction.OpenBank))
            h.change(BankPadEditField.NAME, "太鼓と声")
            h.change(BankPadEditField.COLOR, "#fFaA13")
            h.change(BankPadEditField.ROLE, "自由な役割")
            assertTrue(h.controller.dispatch(BankPadEditAction.Apply))
            val after = h.studio.document.value
            assertEquals(Bank(0, "太鼓と声", 0xffaa13, "自由な役割"), after.project.banks[0])
            assertEquals(before.project.copy(banks = before.project.banks.map { if (it.id == 0) after.project.banks[0] else it }.frozen()), after.project)
            assertEquals(before.revision + 1, after.revision)
            assertEquals(1, h.engine.commands.size)
            assertIs<EngineCommand.SwapProgram>(h.engine.commands.single())
            assertTrue(h.studio.dispatch(Action.Undo).accepted)
            assertEquals(before.project, h.studio.document.value.project)
            assertFalse(h.studio.document.value.canUndo)
            assertTrue(h.studio.dispatch(Action.Redo).accepted)
            assertEquals(after.project, h.studio.document.value.project)
        } finally { h.close() }
    }

    @Test fun padPreviewKeepsPlaybackThenApplyReleasesOnlyItsVoiceAndPreservesOtherValues() = runBlocking {
        val h = BankPadEditorHarness()
        try {
            assertTrue(h.studio.dispatch(Action.Play).accepted)
            assertTrue(h.studio.dispatch(Action.Trigger(0)).accepted)
            val commands = h.engine.commands.size
            val before = h.studio.document.value
            assertTrue(h.controller.dispatch(BankPadEditAction.OpenPad))
            h.change(BankPadEditField.PAN, "-37.5")
            h.change(BankPadEditField.ATTACK, "12.5")
            h.change(BankPadEditField.DECAY, "1000")
            h.change(BankPadEditField.SUSTAIN, "25")
            h.change(BankPadEditField.RELEASE, "83.333")
            assertEquals(before, h.studio.document.value)
            assertEquals(commands, h.engine.commands.size)
            assertTrue(h.engine.transport.playing)
            assertTrue(h.controller.dispatch(BankPadEditAction.Apply))
            val expected = before.project.pads[0].copy(pan = -.375f, attackFrames = 600, decayFrames = 48_000,
                sustainLevel = .25f, releaseFrames = 4000)
            val after = h.studio.document.value.project
            assertEquals(before.project.copy(pads = before.project.pads.map { if (it.id == 0) expected else it }.frozen()), after)
            val effects = h.engine.commands.drop(commands)
            assertEquals(2, effects.size)
            assertEquals(0, assertIs<EngineCommand.Release>(effects[0]).padId)
            val audible = requireNotNull(assertIs<EngineCommand.SwapProgram>(effects[1]).program.pad(0))
            assertEquals(expected.pan, audible.pan)
            assertEquals(600, audible.attackFrames, "Times belong to 48 kHz output, not the 44.1 kHz source")
            assertEquals(48_000, audible.decayFrames)
            assertEquals(.25f, audible.sustainLevel)
            assertEquals(4000, audible.releaseFrames)
            assertTrue(h.engine.transport.playing, "Applying does not stop the song")
            assertFalse(h.controller.dispatch(BankPadEditAction.Apply), "A repeated confirmation cannot add history")
            assertTrue(h.studio.dispatch(Action.Undo).accepted)
            assertEquals(before.project, h.studio.document.value.project)
            assertFalse(h.studio.document.value.canUndo)
            assertTrue(h.studio.dispatch(Action.Redo).accepted)
            assertEquals(after, h.studio.document.value.project)
        } finally { h.close() }
    }

    @Test fun invalidInputNeverCallsTheEditPathAndNoOpKeepsExactFloatsAndHistory() = runBlocking {
        val h = BankPadEditorHarness()
        try {
            val before = h.studio.document.value
            val invalidBank = listOf(BankPadEditField.NAME to "", BankPadEditField.NAME to "x".repeat(49),
                BankPadEditField.ROLE to "\nvoice", BankPadEditField.COLOR to "#FF000000", BankPadEditField.COLOR to "ffffffg")
            for ((field, text) in invalidBank) {
                h.controller.dispatch(BankPadEditAction.OpenBank); h.change(field, text)
                assertTrue(field in h.controller.view.value.invalidFields)
                assertFalse(h.controller.view.value.canApply)
                assertFalse(h.controller.dispatch(BankPadEditAction.Apply))
                assertEquals(before, h.studio.document.value)
            }
            val invalidPad = listOf(BankPadEditField.PAN to "NaN", BankPadEditField.PAN to "100.01",
                BankPadEditField.PAN to "-101", BankPadEditField.ATTACK to "-1", BankPadEditField.DECAY to "1001",
                BankPadEditField.SUSTAIN to "Infinity", BankPadEditField.SUSTAIN to "-0.1", BankPadEditField.RELEASE to "0",
                BankPadEditField.RELEASE to "0.001", BankPadEditField.RELEASE to "")
            for ((field, text) in invalidPad) {
                h.controller.dispatch(BankPadEditAction.OpenPad); h.change(field, text)
                assertTrue(field in h.controller.view.value.invalidFields, "$field: $text")
                assertFalse(h.controller.dispatch(BankPadEditAction.Apply))
                assertEquals(before, h.studio.document.value)
            }
            h.controller.dispatch(BankPadEditAction.OpenPad)
            assertFalse(h.controller.view.value.canApply)
            assertTrue(h.controller.dispatch(BankPadEditAction.Apply))
            assertEquals(before, h.studio.document.value)
            assertTrue(h.engine.commands.isEmpty())
        } finally { h.close() }
    }

    @Test fun documentRevisionAndSelectionFenceOldConfirmationsIncludingUndoBackToTheSameContent() = runBlocking {
        for (mode in 0..3) {
            val h = BankPadEditorHarness()
            try {
                h.controller.dispatch(if (mode % 2 == 0) BankPadEditAction.OpenBank else BankPadEditAction.OpenPad)
                h.change(if (mode % 2 == 0) BankPadEditField.NAME else BankPadEditField.PAN, if (mode % 2 == 0) "New" else "90")
                when (mode) {
                    0 -> h.studio.dispatch(Action.SelectPad(1))
                    1 -> h.studio.dispatch(Action.Edit(Intent.Rename("Changed")))
                    2 -> { h.studio.dispatch(Action.Edit(Intent.Rename("Changed"))); h.studio.dispatch(Action.Undo) }
                    3 -> h.studio.dispatch(Action.New(h.studio.document.value.project))
                }
                val current = h.studio.document.value
                val commands = h.engine.commands.size
                assertFalse(h.controller.dispatch(BankPadEditAction.Apply))
                assertEquals(BankPadEditProblem.STALE, h.controller.view.value.problem)
                assertFalse(h.controller.view.value.canApply)
                assertEquals(current, h.studio.document.value)
                assertEquals(commands, h.engine.commands.size)
                assertFalse(h.controller.dispatch(BankPadEditAction.Apply))
                assertTrue(h.controller.dispatch(BankPadEditAction.Cancel))
            } finally { h.close() }
        }
    }

    @Test fun busyRecordingAndEmptyPadAreRefusedAndFailedAudioPublicationKeepsTheDraftAndDocument() = runBlocking {
        val h = BankPadEditorHarness()
        try {
            for (problem in listOf(BankPadEditProblem.BUSY, BankPadEditProblem.RECORDING)) {
                h.blocked = problem
                assertFalse(h.controller.dispatch(BankPadEditAction.OpenBank))
                h.blocked = null
                assertTrue(h.controller.dispatch(BankPadEditAction.OpenPad))
                h.change(BankPadEditField.PAN, "100")
                val before = h.studio.document.value
                h.blocked = problem
                assertFalse(h.controller.dispatch(BankPadEditAction.Apply))
                assertEquals(before, h.studio.document.value)
                assertTrue(h.controller.dispatch(BankPadEditAction.Cancel))
                h.blocked = null
            }
            h.studio.dispatch(Action.SelectPad(2))
            assertFalse(h.controller.dispatch(BankPadEditAction.OpenPad))
            assertEquals(BankPadEditProblem.EMPTY_PAD, h.controller.view.value.problem)
            h.studio.dispatch(Action.SelectPad(0))
            h.controller.dispatch(BankPadEditAction.OpenPad); h.change(BankPadEditField.PAN, "100")
            val before = h.studio.document.value
            for (denyRelease in listOf(true, false)) {
                h.engine.deny = { if (denyRelease) it is EngineCommand.Release else it is EngineCommand.SwapProgram }
                assertFalse(h.controller.dispatch(BankPadEditAction.Apply))
                assertEquals(before, h.studio.document.value)
                assertEquals(BankPadEditProblem.APPLY_FAILED, h.controller.view.value.problem)
                assertNotNull(h.controller.view.value.draft)
            }
            h.engine.deny = { false }
            assertTrue(h.controller.dispatch(BankPadEditAction.Apply))
            assertEquals(1f, h.studio.document.value.project.pads[0].pan)
            assertTrue(h.studio.dispatch(Action.Undo).accepted)
            assertEquals(before.project, h.studio.document.value.project)
            assertFalse(h.studio.document.value.canUndo)
        } finally { h.close() }
    }

    @Test fun everyEnvelopeFrameSurvivesTheMillisecondDraftAndUneditedFloatFieldsStayBitExact() = runBlocking {
        for (frames in 0..48_000) assertEquals(frames, (BankPadEditController.milliseconds(frames).toDouble() * 48).roundToInt())
        val h = BankPadEditorHarness()
        try {
            val before = h.studio.document.value.project.pads[0]
            h.controller.dispatch(BankPadEditAction.OpenPad)
            assertEquals("0.021", (h.controller.view.value.draft as BankPadDraft.PadSound).release)
            h.change(BankPadEditField.ATTACK, "0")
            assertTrue(h.controller.dispatch(BankPadEditAction.Apply))
            val pad = h.studio.document.value.project.pads[0]
            assertEquals(before.pan.toBits(), pad.pan.toBits())
            assertEquals(before.sustainLevel.toBits(), pad.sustainLevel.toBits())
            assertEquals(1, pad.releaseFrames)
        } finally { h.close() }
    }
}

/** Real Studio/reducer/compiler with in-memory platform ports. No device or physical audio evidence. */
internal class BankPadEditorHarness {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val asset = Asset("a".repeat(64), "wav", 100, 44_100, 2, 4410, "Synthetic")
    val initial = Project(assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(12, 4000)),
        pads = (0..127).map { if (it < 2) Pad(it, asset.hash, FrameRange(17, 3500), "Sound $it", PlayMode.GATE,
            pitchSemitones = 3.0, gain = .7f, pan = .12345679f, reverse = true, chokeGroup = 3,
            attackFrames = 7, releaseFrames = 1, decayFrames = 23, sustainLevel = .7345679f, tone = .4f) else Pad(it) }.frozen(),
        tracks = frozenListOf(Track("song", "Song", TrackKind.BANK)),
        clips = frozenListOf(Clip("placed", "song", asset.hash, FrameRange(4, 3000), pan = -.2f)),
        patterns = frozenListOf(Pattern("pattern-1", notes = frozenListOf(Note(0, 0)))))
    val engine = Engine()
    var blocked: BankPadEditProblem? = null
    val studio = Studio(scope, Services(object : AssetStore {
        override suspend fun containsVerified(asset: Asset) = true
        override suspend fun write(asset: Asset, bytes: ByteArray) = Unit
        override suspend fun read(asset: Asset) = ByteArray(0)
    }, object : ImportPort { override suspend fun import(location: Location) = asset }, object : ProjectPort {
        override suspend fun save(project: Project, revision: Long, location: Location) = Unit
        override suspend fun open(location: Location) = initial
    }, object : ExportPort {
        override suspend fun export(project: Project, patternId: String, request: ExportRequest) = ExportReceipt(request.frames.toLong(), 48_000, 2, request.bits)
    }, engine), initial, preparationDispatcher = Dispatchers.Unconfined)
    val controller = BankPadEditController(studio, { blocked }) { intent, revision ->
        studio.document.value.revision == revision && studio.dispatch(Action.Edit(intent)).accepted
    }
    suspend fun change(field: BankPadEditField, text: String) { assertTrue(controller.dispatch(BankPadEditAction.Change(field, text))) }
    suspend fun close() { engine.deny = { false }; studio.dispatch(Action.Close); scope.cancel() }

    class Engine : EnginePort {
        val commands = mutableListOf<EngineCommand>()
        var deny: (EngineCommand) -> Boolean = { false }
        var transport = TransportState(outputAttached = true)
        private val compiler = ProgramCompiler(object : PcmPort {
            override suspend fun load(asset: Asset) = PcmAsset.fromInterleaved(FloatArray(((asset.frames * 48_000 + asset.sampleRate - 1) / asset.sampleRate).toInt() * 2) { .1f })
        })
        override suspend fun prepare(project: Project, patternId: String, revision: Long): EngineProgram = compiler.compile(project, patternId, revision)
        override suspend fun apply(command: EngineCommand): Boolean {
            commands += command
            if (deny(command)) return false
            transport = transport.copy(frame = transport.frame + 1,
                playing = when (command) { is EngineCommand.StartSequence -> true; is EngineCommand.Stop -> false; else -> transport.playing },
                programRevision = (command as? EngineCommand.SwapProgram)?.program?.revision ?: transport.programRevision)
            return true
        }
        override fun snapshot() = transport
    }
}
