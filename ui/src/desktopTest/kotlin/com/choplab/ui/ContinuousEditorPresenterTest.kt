package com.choplab.ui

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.kits.DrumKits
import com.choplab.core.model.*
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.engine.PlayMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** Presenter/Studio contracts with fake platform ports; not physical audio evidence. */
class ContinuousEditorPresenterTest {
    @Test fun explicitLoopSwitchReplacesThePreviousLoopWithoutChangingTheOriginal() = runBlocking {
        val h = Harness()
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(0)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(1)))
            val p = h.studio.document.value.project
            // The replaced loop returns to the mode it had before looping; GATE is not flattened to ONE_SHOT.
            assertEquals(PlayMode.GATE, p.pads[0].mode)
            assertEquals(PlayMode.LOOP, p.pads[1].mode)
            assertEquals(h.original.hash, p.source?.assetHash)
            val release = h.engine.commands.indexOfLast { it is EngineCommand.Release && it.padId == 0 }
            val trigger = h.engine.commands.indexOfLast { it is EngineCommand.Trigger && it.padId == 1 }
            assertTrue(release >= 0 && trigger > release)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(1)))
            assertEquals(PlayMode.GATE, h.studio.document.value.project.pads[1].mode)
        } finally { h.close() }
    }

    @Test fun loopToggleKeepsAPadThatWasAlreadyALoopInTheDocument() = runBlocking {
        val h = Harness { p -> p.copy(pads = p.pads.map { if (it.id == 1) it.copy(mode = PlayMode.LOOP) else it }.frozen()) }
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(1)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(0)))
            val p = h.studio.document.value.project
            assertEquals(PlayMode.LOOP, p.pads[0].mode)
            // Only one loop sounds, but the document's own LOOP setting on PAD 2 is not rewritten.
            assertEquals(PlayMode.LOOP, p.pads[1].mode)
            val release = h.engine.commands.indexOfLast { it is EngineCommand.Release && it.padId == 1 }
            val trigger = h.engine.commands.indexOfLast { it is EngineCommand.Trigger && it.padId == 0 }
            assertTrue(release >= 0 && trigger > release)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(0)))
            assertEquals(PlayMode.GATE, h.studio.document.value.project.pads[0].mode)
            assertEquals(PlayMode.LOOP, h.studio.document.value.project.pads[1].mode)
        } finally { h.close() }
    }

    @Test fun playAfterTheSongRanToItsEndStartsAgainFromTheTop() = runBlocking {
        val h = Harness()
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            // The engine parks at the song end; a plain Resume there is accepted but plays nothing.
            h.engine.transport = TransportState(outputAttached = true, sequenceFrame = 48_000)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlaySong))
            val seek = h.engine.commands.indexOfLast { it is EngineCommand.Seek && it.sequenceFrame == 0L }
            val resume = h.engine.commands.indexOfLast { it is EngineCommand.Resume }
            assertTrue(seek >= 0 && resume > seek)
            h.engine.commands.clear()
            h.engine.transport = TransportState(outputAttached = true, sequenceFrame = 12_000, sequencePaused = true)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlaySong))
            assertTrue(h.engine.commands.none { it is EngineCommand.Seek })
            assertTrue(h.engine.commands.any { it is EngineCommand.Resume })
        } finally { h.close() }
    }

    @Test fun rapidPadEventsReachTheEngineInTheOrderTheyWereMade() = runBlocking {
        val h = Harness()
        try {
            repeat(100) {
                h.presenter.onAction(ContinuousEditorAction.HoldPad(0))
                h.presenter.onAction(ContinuousEditorAction.ReleasePad(0))
            }
            fun padEvents() = h.engine.commands.filter { (it is EngineCommand.Trigger && it.padId == 0) || (it is EngineCommand.Release && it.padId == 0) }
            withTimeout(10_000) { while (padEvents().size < 200) delay(5) }
            // A Release overtaken by its Hold would leave a GATE PAD sounding.
            padEvents().forEachIndexed { index, command -> assertEquals(index % 2 == 0, command is EngineCommand.Trigger, "PAD event $index out of order") }
        } finally { h.close() }
    }

    @Test fun autosavedZeroLengthClipStillOpensAndCanBeDeleted() = runBlocking<Unit> {
        val highRate = Asset("c".repeat(64), "wav", 100, 96_000, 2, 96_000, "High rate")
        // Saved by an earlier build: one 96 kHz frame is shorter than one 48 kHz timeline frame.
        val h = Harness { p -> p.copy(assets = (p.assets + highRate).frozen(), tracks = frozenListOf(Track("track-1", "A", TrackKind.BANK)),
            clips = frozenListOf(Clip("clip-1", "track-1", highRate.hash, FrameRange(1, 2), timelineStartFrame = 0))) }
        try {
            assertEquals(1L, h.presenter.state.value.clips.single().timelineDurationFrames)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.DeleteClip("clip-1")))
            withTimeout(2000) { h.presenter.state.first { it.clips.isEmpty() } }
        } finally { h.close() }
    }
    @Test fun drumKitFillsBankBAndAKitChangeKeepsThePlacedBeat() = runBlocking<Unit> {
        val h = Harness(kits = true)
        try {
            val offered = h.presenter.state.value
            assertTrue(offered.permits(ContinuousCapability.ADD_DRUM))
            assertEquals(DrumKits.catalog.map { it.id }, offered.drumKits.map { it.id })
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.AddDrum))
            withTimeout(2000) { h.presenter.state.first { it.drumKitChooserOpen } }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ChooseDrumKit("dusty-jazz")))
            val installed = withTimeout(2000) { h.presenter.state.first { it.installedDrumKit == "dusty-jazz" && it.selectedPadId == 16 } }
            assertFalse(installed.drumKitChooserOpen)
            assertNull(installed.drumKitQuestion, "An empty drum BANK needs no question")
            assertTrue(installed.pads.subList(16, 32).all { it.kind == ContinuousPadKind.DRUM })
            assertEquals(listOf("KICK 1", "SNARE 1", "CLOSED HAT 1", "CLAP 1"), listOf(16, 20, 24, 28).map { installed.pads[it].name })
            assertEquals(ContinuousPadKind.SAMPLE, installed.pads[0].kind, "The user's own chop stays a sample")

            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(16, null, 4_800)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            val placed = h.studio.document.value.project.clips
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ChooseDrumKit("boom-bap")))
            withTimeout(2000) { h.presenter.state.first { it.installedDrumKit == "boom-bap" } }
            val changed = h.studio.document.value.project.clips
            assertEquals(placed.map { it.copy(assetHash = if (it.assetHash == h.ports.kitHash("dusty-jazz", 0)) h.ports.kitHash("boom-bap", 0) else it.assetHash) }, changed,
                "The placed kick takes the new kit's kick in the same place; the chop clip stays")
            assertNotEquals(placed, changed)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(placed, h.studio.document.value.project.clips, "One Undo returns the previous kit")
            withTimeout(2000) { h.presenter.state.first { it.installedDrumKit == "dusty-jazz" } }
        } finally { h.close() }
    }

    @Test fun aKitChangeSwapsOnlyTheSoundsAndEachDrumKeepsItsSettings() = runBlocking<Unit> {
        val h = Harness(kits = true)
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ChooseDrumKit("dusty-jazz")))
            withTimeout(2000) { h.presenter.state.first { it.installedDrumKit == "dusty-jazz" } }
            val tuned = h.studio.document.value.project.pads[16].copy(gain = .5f, pitchSemitones = -2.0, tone = .6f, mode = PlayMode.GATE)
            assertTrue(h.studio.dispatch(Action.Edit(Intent.SetPad(tuned))).accepted)

            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ChooseDrumKit("boom-bap")))
            assertNull(withTimeout(2000) { h.presenter.state.first { it.installedDrumKit == "boom-bap" } }.drumKitQuestion,
                "Kit sounds are not the user's own sounds, so no question")
            val project = h.studio.document.value.project
            assertEquals(tuned.copy(assetHash = h.ports.kitHash("boom-bap", 0)), project.pads[16], "The kick keeps its settings with the new kit's sound")
            assertEquals(DrumKits.pad(17, 1, project.asset(h.ports.kitHash("boom-bap", 1))), project.pads[17])

            val revision = h.studio.document.value.revision
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ChooseDrumKit("boom-bap")))
            assertEquals(revision, h.studio.document.value.revision, "Choosing the kit in use again changes nothing")

            // A kit is shown as in use only while every sound on the drum BANK comes from it.
            assertTrue(h.studio.dispatch(Action.Edit(Intent.AssignRange(h.chopped.hash, FrameRange(0, 48_000), 17))).accepted)
            val mixed = withTimeout(2000) { h.presenter.state.first { it.installedDrumKit == null } }
            assertEquals(ContinuousPadKind.DRUM, mixed.pads[16].kind)
        } finally { h.close() }
    }

    @Test fun ownSoundsOnTheDrumBankAreReplacedOnlyAfterTheUserAgrees() = runBlocking<Unit> {
        val h = Harness(kits = true) { p -> p.copy(pads = p.pads.map { if (it.id == 17) Pad(17, "b".repeat(64), FrameRange(0, 48_000)) else it }.frozen()) }
        try {
            val before = h.studio.document.value.project
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ChooseDrumKit("vinyl-soul")))
            assertEquals(ContinuousKitQuestion("vinyl-soul", 1), withTimeout(2000) { h.presenter.state.first { it.drumKitQuestion != null } }.drumKitQuestion)
            assertEquals(before, h.studio.document.value.project, "Nothing changes before the user agrees")
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.DismissDrumKit))
            withTimeout(2000) { h.presenter.state.first { it.drumKitQuestion == null } }
            assertEquals(before, h.studio.document.value.project)

            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ChooseDrumKit("vinyl-soul")))
            // The drum BANK changes while the question is open; the answer never covers a sound it did not count.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.AssignSourceRange(18)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ConfirmDrumKit))
            val asked = withTimeout(2000) { h.presenter.state.first { it.drumKitQuestion?.replacedSounds == 2 } }
            assertNull(asked.installedDrumKit)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ConfirmDrumKit))
            val installed = withTimeout(2000) { h.presenter.state.first { it.installedDrumKit == "vinyl-soul" } }
            assertNull(installed.drumKitQuestion)
            assertEquals(before.pads.subList(0, 16), h.studio.document.value.project.pads.subList(0, 16), "Other BANKs are untouched")
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals("b".repeat(64), h.studio.document.value.project.pads[17].assetHash, "Undo brings the user's sound back")
        } finally { h.close() }
    }

    @Test fun toneDarkensTheSelectedPadAndKeepsItOffTheTimelineUntilReset() = runBlocking<Unit> {
        val h = Harness()
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectPad(0)))
            // Capabilities are judged once the edit's preparation has finished (LOADING withholds them all).
            suspend fun settled(condition: (ContinuousEditorState) -> Boolean) =
                withTimeout(2000) { h.presenter.state.first { condition(it) && it.status != ContinuousStatus.LOADING } }
            val open = settled { it.selectedPadId == 0 }
            assertTrue(open.permits(ContinuousCapability.PAD_TONE))
            assertTrue(open.permits(ContinuousCapability.PLACE_PAD))
            assertEquals(1f, open.pads[0].tone)

            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetPadTone(0, .45f)))
            assertEquals(.45f, h.studio.document.value.project.pads[0].tone)
            val dark = settled { it.pads[0].tone == .45f }
            // A placed clip would play the source untouched, so a toned PAD is not placed until tone is reset.
            assertFalse(dark.permits(ContinuousCapability.PLACE_PAD))
            assertTrue(h.engine.commands.any { it is EngineCommand.Release && it.padId == 0 }, "Changing tone stops the old voice")

            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetPadTone(0, 1.05f)), "Tone stays within its range")
            assertEquals(.45f, h.studio.document.value.project.pads[0].tone)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetPadTone(0, 1f)))
            assertTrue(settled { it.pads[0].tone == 1f }.permits(ContinuousCapability.PLACE_PAD))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(.45f, h.studio.document.value.project.pads[0].tone, "Each tone step is one Undo")

            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectPad(5)))
            val empty = settled { it.selectedPadId == 5 }
            assertTrue(empty.permits(ContinuousCapability.TEMPO), "Settled, so the missing tone control is about the empty PAD")
            assertFalse(empty.permits(ContinuousCapability.PAD_TONE), "An empty PAD has no tone")
        } finally { h.close() }
    }

    @Test fun hostsWithoutKitsKeepDrumsUnavailable() = runBlocking<Unit> {
        val h = Harness()
        try {
            assertFalse(h.presenter.state.value.permits(ContinuousCapability.ADD_DRUM))
            assertTrue(h.presenter.state.value.drumKits.isEmpty())
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.AddDrum))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ChooseDrumKit("dusty-jazz")))
            assertFalse(h.presenter.state.value.drumKitChooserOpen)
            assertTrue(h.studio.document.value.project.pads.subList(16, 32).all { it.assetHash == null })
        } finally { h.close() }
    }

    @Test fun originalSurvivesPadSelectionAndStageChangesWithoutAutomaticPlacement() = runBlocking {
        val h = Harness()
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlayOriginal))
            h.ports.originalFrame = 1234
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectPad(1)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)))
            val state = withTimeout(2000) { h.presenter.state.first { it.stage == ContinuousStage.BEAT && it.selectedPadId == 1 } }
            assertEquals(h.original.hash, state.original?.id)
            assertTrue(state.originalPlaying)
            assertEquals(1234L, h.presenter.readout().originalFrame)
            assertEquals(0, h.ports.stops)
            assertTrue(h.studio.document.value.project.clips.isEmpty())
        } finally { h.close() }
    }

    @Test fun monitoringNeverChangesProjectAndArrangementExportIsExplicit() = runBlocking {
        val h = Harness()
        try {
            val initial = h.studio.document.value.project
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetSongMonitorGain(0f)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetOriginalMonitorGain(.4f)))
            assertEquals(initial, h.studio.document.value.project)
            assertEquals(0f, h.ports.songGain)
            assertEquals(.4f, h.ports.originalGain)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 73)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ExportWav))
            withTimeout(2000) { while (h.exportTarget == null) delay(5) }
            assertIs<PlaybackTarget.Arrangement>(h.exportTarget)
            assertEquals(48_073L, h.ports.exportFrames)
        } finally { h.close() }
    }

    @Test fun heldGateIsReleasedOnNavigationAndClipEditUndoRetainsSource() = runBlocking {
        val h = Harness()
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.HoldPad(0)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.CHOP)))
            assertTrue(h.engine.commands.any { it is EngineCommand.Release && it.padId == 0 })
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 913)))
            assertEquals(913L, h.studio.document.value.project.clips.single().timelineStartFrame)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertTrue(h.studio.document.value.project.clips.isEmpty())
            assertEquals(h.original.hash, h.studio.document.value.project.source?.assetHash)
        } finally { h.close() }
    }

    private class Harness(kits: Boolean = false, adjust: (Project) -> Project = { it }) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val original = Asset("a".repeat(64), "wav", 100, 48_000, 2, 96_000, "Original")
        val chopped = Asset("b".repeat(64), "wav", 100, 48_000, 2, 48_000, "Chop")
        val initial = adjust(Project(assets = frozenListOf(original, chopped), source = Source(original.hash, FrameRange(0, 96_000)),
            pads = (0..127).map { if (it < 2) Pad(it, chopped.hash, FrameRange(0, 48_000), mode = PlayMode.GATE) else Pad(it) }.frozen()))
        val engine = FakeEngine()
        @Volatile var exportTarget: PlaybackTarget? = null
        val studio = Studio(scope, Services(object : AssetStore {
            override suspend fun containsVerified(asset: Asset) = true
            override suspend fun write(asset: Asset, bytes: ByteArray) = Unit
            override suspend fun read(asset: Asset) = byteArrayOf()
        }, object : ImportPort { override suspend fun import(location: Location) = original },
            object : ProjectPort {
                override suspend fun save(project: Project, revision: Long, location: Location) = Unit
                override suspend fun open(location: Location) = initial
            }, object : ExportPort {
                override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt = error("Must select arrangement explicitly")
                override suspend fun export(project: Project, target: PlaybackTarget, request: ExportRequest): ExportReceipt {
                    exportTarget = target
                    return ExportReceipt(request.frames.toLong(), 48_000, 2, request.bits)
                }
            }, engine), initial)
        val ports = FakePorts(kits)
        val presenter = ContinuousEditorPresenter(studio, scope, ports)
        suspend fun close() { presenter.close(); studio.dispatch(Action.Close); scope.cancel() }
    }
    private class FakeEngine : EnginePort {
        val commands = java.util.concurrent.CopyOnWriteArrayList<EngineCommand>()
        override suspend fun prepare(project: Project, patternId: String, revision: Long) = EngineProgram(revision = revision)
        override suspend fun prepare(project: Project, target: PlaybackTarget, revision: Long) = EngineProgram(revision = revision)
        override suspend fun apply(command: EngineCommand): Boolean { commands += command; return true }
        @Volatile var transport = TransportState(outputAttached = true)
        override fun snapshot() = transport
    }
    private class FakePorts(private val kits: Boolean = false) : ContinuousEditorPorts {
        var originalFrame = 0L
        var stops = 0
        var songGain = 1f
        var originalGain = 1f
        var exportFrames = 0L
        override val originalAvailable = true
        override suspend fun chooseAudio(): Location? = null
        override suspend fun chooseOpen(): Location? = null
        override suspend fun chooseSave() = Location("save")
        override suspend fun chooseExport(frames: Long): ExportRequest {
            exportFrames = frames
            return ExportRequest(Location("export"), frames.toInt())
        }
        override suspend fun peaks(asset: Asset) = listOf(.2f, .4f)
        override fun readout() = ContinuousEditorReadout(originalFrame = originalFrame)
        override suspend fun setSongMonitorGain(gain: Float): Boolean { songGain = gain; return true }
        override suspend fun setOriginalMonitorGain(gain: Float): Boolean { originalGain = gain; return true }
        override suspend fun playOriginal(asset: Asset) = true
        override suspend fun stopOriginal(): Boolean { stops++; return true }
        override val drumKitsAvailable get() = kits
        fun kitHash(kitId: String, slot: Int) = (DrumKits.catalog.indexOfFirst { it.id == kitId } * 16 + slot + 1).toString(16).padStart(64, '0')
        override suspend fun drumKit(kitId: String): List<Asset>? = if (!kits) null else (0 until 16).map { slot ->
            val frames = DrumKits.frames(slot)
            Asset(kitHash(kitId, slot), "wav", 44L + frames * 4, 48_000, 1, frames.toLong(), DrumKits.soundName(DrumKits.kit(kitId), slot), AssetRole.RENDERED)
        }
    }
}
