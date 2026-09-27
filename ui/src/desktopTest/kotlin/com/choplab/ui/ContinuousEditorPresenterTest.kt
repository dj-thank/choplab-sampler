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
import kotlin.math.pow
import kotlin.test.*

/** Presenter/Studio contracts with fake platform ports; not physical audio evidence. */
class ContinuousEditorPresenterTest {
    @Test fun reviewAHitPressedBeforeTheSongsEndSurvivesItsLaterRelease() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.ports.outputDelay = 0
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(1, null, 0)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE)))
            h.until { it.permits(ContinuousCapability.RECORD_HITS) && it.grid == ContinuousGrid.FREE }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            h.until { it.recordingHits }
            // CEPads takes this timestamp on pointer down, but emits CaptureHit only on release.
            val pressedAt = 43_200L
            h.engine.transport = h.engine.transport.copy(sequenceFrame = pressedAt)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.HoldPad(0)))
            h.engine.transport = h.engine.transport.copy(sequenceFrame = 48_000, playing = false, sequencePaused = false)
            h.until { !it.recordingHits && it.status == ContinuousStatus.HITS_EMPTY }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ReleasePad(0)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(0, pressedAt)))
            assertEquals(listOf(0L, pressedAt), h.studio.document.value.project.clips.map {
                ContinuousClipEdits.startFrame(h.studio.document.value.project, it)
            }.sorted(), "A PAD heard before the end must be retained when the finger is lifted after the end")
        } finally { h.close() }
    }

    @Test fun reviewAGatePerformanceRetainsTheDurationThatWasPlayed() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.ports.outputDelay = 0
            assertEquals(PlayMode.GATE, h.initial.pads[0].mode)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(1, null, 0)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE)))
            h.until { it.permits(ContinuousCapability.RECORD_HITS) && it.grid == ContinuousGrid.FREE }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            h.until { it.recordingHits }
            val pressedAt = 12_000L
            val playedFrames = 4_800L
            h.engine.transport = h.engine.transport.copy(sequenceFrame = pressedAt)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.HoldPad(0)))
            h.engine.transport = h.engine.transport.copy(sequenceFrame = pressedAt + playedFrames)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ReleasePad(0)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(0, pressedAt)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopHits))
            val p = h.studio.document.value.project
            val recorded = p.clips.single { ContinuousClipEdits.startFrame(p, it) == pressedAt }
            assertEquals(playedFrames, recorded.range.length, "A 100 ms GATE performance must not become the whole 1 s sample")
        } finally { h.close() }
    }

    @Test fun separationCancellationLeavesProductionUntouchedAndRecordingDoesNotOpenAPicker() = runBlocking<Unit> {
        val h = Harness(voice = true)
        try {
            val before = h.studio.document.value.project
            assertTrue(h.presenter.state.value.permits(ContinuousCapability.SEPARATE_SOURCE))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SeparateSource))
            assertEquals(1, h.ports.separationPicks)
            assertEquals(before, h.studio.document.value.project)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SeparateSource))
            assertEquals(1, h.ports.separationPicks)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.DiscardSourceRecording))
        } finally { h.close() }
    }

    @Test fun onlineCancellationLeavesProductionUntouchedAndRecordingDoesNotOpenAPicker() = runBlocking<Unit> {
        val h = Harness(voice = true)
        try {
            val before = h.studio.document.value.project
            assertTrue(h.presenter.state.value.permits(ContinuousCapability.IMPORT_ONLINE))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ImportOnline))
            assertEquals(1, h.ports.onlinePicks)
            assertEquals(before, h.studio.document.value.project)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ImportOnline))
            assertEquals(1, h.ports.onlinePicks)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.DiscardSourceRecording))
        } finally { h.close() }
    }

    @Test fun libraryCancellationLeavesProductionUntouchedAndRecordingDoesNotOpenAPicker() = runBlocking<Unit> {
        val h = Harness(voice = true)
        try {
            val before = h.studio.document.value.project
            assertTrue(h.presenter.state.value.permits(ContinuousCapability.IMPORT_LIBRARY))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ImportLibrary))
            assertEquals(1, h.ports.libraryPicks)
            assertEquals(before, h.studio.document.value.project)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ImportLibrary))
            assertEquals(1, h.ports.libraryPicks)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.DiscardSourceRecording))
        } finally { h.close() }
    }

    @Test fun systemAudioBecomesAStereoOriginalWithoutOpeningTheMicrophone() = runBlocking<Unit> {
        val capture = FakeSystemCapture()
        val h = Harness(voice = true, system = capture)
        try {
            val before = h.studio.document.value.project
            assertTrue(h.presenter.state.value.permits(ContinuousCapability.RECORD_SYSTEM_SOURCE))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSystemSource))
            h.until { it.recordingSource && it.recordingSystemAudio && !it.startingSourceRecording }
            assertEquals(987L, h.presenter.readout().recordingMillis)
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            assertTrue(h.ports.voiceStarts.isEmpty())
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopSourceRecording))
            val after = h.studio.document.value.project
            val asset = after.asset(requireNotNull(after.source).assetHash)
            assertEquals(2, asset.channels)
            assertEquals("SYSTEM 1", asset.name)
            assertEquals(before.pads, after.pads)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before, h.studio.document.value.project)
            for ((result, message) in listOf(SystemAudioCapture.Start.DENIED to ContinuousStatus.SYSTEM_DENIED,
                    SystemAudioCapture.Start.NO_DISPLAY to ContinuousStatus.SYSTEM_NO_DISPLAY,
                    SystemAudioCapture.Start.TIMEOUT to ContinuousStatus.SYSTEM_TIMEOUT,
                    SystemAudioCapture.Start.UNAVAILABLE to ContinuousStatus.SYSTEM_UNAVAILABLE)) {
                capture.result = result
                assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordSystemSource))
                h.until { !it.recordingSource && it.status == message }
                assertTrue(h.ports.voiceStarts.isEmpty(), "System failure must never fall back to a microphone")
                assertEquals(before, h.studio.document.value.project)
            }
        } finally { h.close() }
    }

    @Test fun systemPermissionWaitCanBeCancelledAndClosingCancelsItBeforeAutosave() = runBlocking<Unit> {
        val capture = FakeSystemCapture().also { it.waiting = CompletableDeferred() }
        val h = Harness(system = capture)
        try {
            val before = h.studio.document.value.project
            h.presenter.onAction(ContinuousEditorAction.RecordSystemSource)
            h.until { it.startingSourceRecording && it.recordingSystemAudio }
            h.presenter.onAction(ContinuousEditorAction.DiscardSourceRecording)
            h.until { !it.recordingSource && it.status == ContinuousStatus.CANCELLED }
            assertEquals(before, h.studio.document.value.project)
            assertEquals(0, capture.stops)
            capture.waiting = CompletableDeferred()
            h.presenter.onAction(ContinuousEditorAction.RecordSystemSource)
            h.until { it.startingSourceRecording }
            assertTrue(withTimeout(2_000) { h.presenter.finishRecording() })
            assertEquals(before, h.studio.document.value.project)
            capture.waiting = null
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSystemSource), "Retry after cancellation")
            assertTrue(h.presenter.finishRecording())
            assertEquals(1, capture.stops)
        } finally { h.close() }
    }

    private class FakeSystemCapture : SystemAudioCapture {
        var result = SystemAudioCapture.Start.STARTED
        var waiting: CompletableDeferred<SystemAudioCapture.Start>? = null
        var stops = 0
        override suspend fun start(maxSeconds: Int): SystemAudioCapture.Start {
            assertTrue(maxSeconds in 1..300)
            return waiting?.await() ?: result
        }
        override fun cancelOpening() { waiting?.complete(SystemAudioCapture.Start.CANCELLED) }
        override val full = false
        override val interrupted = false
        override val recordedMillis = 987L
        override suspend fun stop(name: String): Asset {
            stops++
            return Asset("f".repeat(64), "wav", 44 + 96_000L * 8, 48_000, 2, 96_000, name)
        }
        override suspend fun discard() = Unit
        override suspend fun close() = Unit
    }

    @Test fun recordingCannotStartWhileAnEarlierDocumentChangeIsStillPreparing() = runBlocking<Unit> {
        val h = Harness(voice = true)
        val preparing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            h.engine.duringPrepare = { preparing.complete(Unit); release.await() }
            val edit = async { h.studio.dispatch(Action.Edit(Intent.SetPad(h.initial.pads[0].copy(gain = .4f)))) }
            withTimeout(5_000) { preparing.await() }
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            assertTrue(h.ports.voiceStarts.isEmpty())
            release.complete(Unit)
            assertTrue(edit.await().accepted)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.DiscardSourceRecording))
        } finally { release.complete(Unit); h.close() }
    }

    @Test fun microphoneCollectsAnOriginalWithoutASongOrOutputAndOneUndoRestoresIt() = runBlocking<Unit> {
        val h = Harness(voice = true) { Project() }
        try {
            h.engine.transport = TransportState(outputAttached = false)
            h.studio.dispatch(Action.RefreshTransport)
            assertTrue(h.until { it.permits(ContinuousCapability.RECORD_SOURCE) }.clips.isEmpty())
            val before = h.studio.document.value.project
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            val recording = h.until { it.recordingSource }
            assertFalse(recording.recordingVoice)
            assertFalse(recording.permits(ContinuousCapability.IMPORT_AUDIO))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.OpenProject))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(0, h.ports.cues, "A source has no song cue or trimmed lead-in")
            assertTrue(h.ports.voiceStarts.single() in 1..300)
            h.ports.recordedMillis = 1234
            assertEquals(1234, h.presenter.readout().recordingMillis)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopSourceRecording))
            h.until { !it.recordingSource && it.status == ContinuousStatus.SOURCE_RECORDED }
            val recorded = h.studio.document.value.project
            assertEquals("MIC 1", recorded.assets.single().name)
            assertEquals(FrameRange(0, 96_000), recorded.source?.range, "All captured frames become the original")
            assertTrue(recorded.clips.isEmpty())
            assertTrue(recorded.pads.all { it.assetHash == null })
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before, h.studio.document.value.project)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Redo))
            assertEquals(recorded, h.studio.document.value.project)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.AssignSourceRange(0)))
            assertEquals(recorded.source?.assetHash, h.studio.document.value.project.pads[0].assetHash)
        } finally { h.close() }
    }

    @Test fun sourceRecordingPreservesPadsAndSongAndCanBeDiscardedOrFinishedBeforeAutosave() = runBlocking<Unit> {
        val h = Harness(voice = true)
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            val before = h.studio.document.value.project
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.DiscardSourceRecording))
            assertEquals(1, h.ports.discards)
            assertEquals(before, h.studio.document.value.project)
            assertTrue(h.ports.voiceNames.isEmpty(), "Discard never publishes a file")
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            assertTrue(h.presenter.finishRecording())
            val after = h.studio.document.value.project
            assertNotEquals(before.source, after.source)
            assertEquals(before.pads, after.pads)
            assertEquals(before.clips, after.clips)
            assertTrue(after.assets.containsAll(before.assets))
            assertTrue(h.presenter.finishRecording())
            assertEquals(1, h.ports.voiceNames.size, "Final autosave's preparation is idempotent")
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before, h.studio.document.value.project)
        } finally { h.close() }
    }

    @Test fun sourceLimitInputLossPermissionAndStorageFailureLeaveAnEditableDocument() = runBlocking<Unit> {
        val h = Harness(voice = true)
        try {
            for (permission in listOf(VoiceStart.DENIED, VoiceStart.UNAVAILABLE, VoiceStart.NO_ROOM)) {
                h.ports.microphone = permission
                assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
                h.until { !it.recordingSource && it.status != null }
                assertTrue(h.ports.voiceNames.isEmpty())
            }
            h.ports.microphone = VoiceStart.STARTED
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            h.ports.full = true
            h.until { !it.recordingSource && it.status == ContinuousStatus.SOURCE_RECORDING_LIMIT }
            h.ports.full = false
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            h.ports.interrupted = true
            h.until { !it.recordingSource && it.status == ContinuousStatus.SOURCE_RECORDING_INTERRUPTED }
            h.ports.interrupted = false
            val before = h.studio.document.value.project
            h.ports.takeFrames = null
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopSourceRecording))
            h.until { it.status == ContinuousStatus.VOICE_EMPTY }
            assertEquals(before, h.studio.document.value.project)
            h.ports.storeFails = true
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            assertFalse(h.presenter.finishRecording(), "The host must not report a successful final save")
            h.until { !it.recordingSource && it.status == ContinuousStatus.VOICE_NOT_SAVED }
            assertEquals(before, h.studio.document.value.project)
            h.ports.storeFails = false
            h.ports.takeFrames = 96_000
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordSource))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.CHOP)))
            h.until { it.stage == ContinuousStage.CHOP && !it.recordingSource }
            assertNotEquals(before.source, h.studio.document.value.project.source)
        } finally { h.close() }
    }

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

    @Test fun aPadsStartAndEndMoveByMillisecondsWithinItsSoundOneUndoPerRun() = runBlocking {
        val slow = Asset("d".repeat(64), "wav", 100, 44_100, 2, 44_100, "44.1 kHz")
        val h = Harness { p -> p.copy(assets = (p.assets + slow).frozen(),
            pads = p.pads.map { if (it.id == 3) Pad(3, slow.hash, FrameRange(0, 44_100)) else it }.frozen()) }
        try {
            fun range() = requireNotNull(h.studio.document.value.project.pads[0].range)
            // At 48 kHz a millisecond is 48 frames.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.NudgePadBoundary(0, end = false, milliseconds = 10)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.NudgePadBoundary(0, end = false, milliseconds = 10)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.NudgePadBoundary(0, end = false, milliseconds = -1)))
            assertEquals(FrameRange(912, 48_000), range())
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.NudgePadBoundary(0, end = true, milliseconds = -1)))
            assertEquals(FrameRange(912, 47_952), range())
            // One Undo per run of nudges of one boundary, as the earlier app's trim dials.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(FrameRange(912, 48_000), range())
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(FrameRange(0, 48_000), range())
            assertFalse(h.studio.document.value.canUndo)
            // Within its sound: at a limit a nudge changes nothing and is not refused.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.NudgePadBoundary(0, end = false, milliseconds = -10)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.NudgePadBoundary(0, end = true, milliseconds = 10)))
            assertEquals(FrameRange(0, 48_000), range())
            assertFalse(h.studio.document.value.canUndo, "Nothing changed, so nothing to undo")
            // Never past the other boundary.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.NudgePadBoundary(0, end = false, milliseconds = 1000)))
            assertEquals(FrameRange(47_999, 48_000), range())
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.NudgePadBoundary(0, end = true, milliseconds = -1000)))
            assertEquals(FrameRange(47_999, 48_000), range())
            // A millisecond is counted at the sound's own rate: 44 frames at 44.1 kHz.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.NudgePadBoundary(3, end = true, milliseconds = -1)))
            assertEquals(FrameRange(0, 44_056), requireNotNull(h.studio.document.value.project.pads[3].range))
            // Only a PAD with a sound, by a nonzero step of at most a second.
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.NudgePadBoundary(5, end = false, milliseconds = 1)))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.NudgePadBoundary(0, end = false, milliseconds = 0)))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.NudgePadBoundary(0, end = false, milliseconds = 1001)))
        } finally { h.close() }
    }

    @Test fun aPadsPlaySettingsChangeItOneUndoEachAndClearingEmptiesIt() = runBlocking {
        val h = Harness()
        try {
            // An empty PAD has nothing to set.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectPad(5)))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.OpenPadPlay))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetPadReverse(5, true)))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ClearPad(5)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectPad(0)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenPadPlay))
            h.until { it.padPlayOpen }
            // The panel stays open while each change is prepared, so it does not flicker or forget a press.
            var openWhilePrepared: Boolean? = null
            h.engine.duringPrepare = { delay(150); openWhilePrepared = h.presenter.state.value.padPlayOpen }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetPadReverse(0, true)))
            assertEquals(true, openWhilePrepared)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetPadReverse(0, true)), "Choosing what is chosen already is fine")
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetPadMode(0, ContinuousPadMode.ONE_SHOT)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetPadChoke(0, 2)))
            h.until { s -> s.pads[0].let { it.reverse && it.mode == ContinuousPadMode.ONE_SHOT && it.chokeGroup == 2 } }
            val set = h.studio.document.value.project.pads[0]
            assertEquals(Triple(true, PlayMode.ONE_SHOT, 2), Triple(set.reverse, set.mode, set.chokeGroup))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            val undone = h.studio.document.value.project.pads[0]
            assertEquals(Triple(true, PlayMode.ONE_SHOT, 0), Triple(undone.reverse, undone.mode, undone.chokeGroup), "One Undo per change")
            // Looping stays the loop button's, and choke groups are 0 to 4 as in the earlier app.
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetPadMode(0, ContinuousPadMode.LOOP)))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetPadChoke(0, 5)))
            assertEquals(undone, h.studio.document.value.project.pads[0])
            // Choosing how a PAD the loop button made loop plays ends that loop. After an Undo of the choice it loops
            // as the loop button left it, and ending that loop returns it to the mode it had before (GATE).
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(1)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetPadMode(1, ContinuousPadMode.ONE_SHOT)))
            assertEquals(PlayMode.ONE_SHOT, h.studio.document.value.project.pads[1].mode)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(PlayMode.LOOP, h.studio.document.value.project.pads[1].mode)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(1)))
            assertEquals(PlayMode.GATE, h.studio.document.value.project.pads[1].mode)
            // A choice made while nothing loops stays as it is when another PAD loops.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetPadMode(1, ContinuousPadMode.ONE_SHOT)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(0)))
            assertEquals(PlayMode.ONE_SHOT, h.studio.document.value.project.pads[1].mode)
            // Clearing a looping PAD stops it, empties it and closes the panel.
            val before = h.engine.commands.size
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ClearPad(0)))
            h.until { !it.padPlayOpen && it.pads[0].kind == ContinuousPadKind.EMPTY && 0 !in it.pads.filter { p -> p.looping }.map { p -> p.id } }
            assertNull(h.studio.document.value.project.pads[0].assetHash)
            assertTrue(h.engine.commands.drop(before).any { it is EngineCommand.Release && it.padId == 0 }, "The looping PAD stops")
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ClearPad(0)), "An empty PAD has nothing to clear")
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(h.chopped.hash, h.studio.document.value.project.pads[0].assetHash, "Undo brings the sound back")
            h.until { it.pads[0].kind != ContinuousPadKind.EMPTY }
            assertFalse(h.presenter.state.value.padPlayOpen, "Closed by clearing, the panel does not come back with the sound")
            // The loop it had is still the loop button's: the next loop returns it to the mode it had before looping (once).
            assertEquals(PlayMode.LOOP, h.studio.document.value.project.pads[0].mode)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(1)))
            assertEquals(PlayMode.ONE_SHOT, h.studio.document.value.project.pads[0].mode)
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

    @Test fun songKeyIsSavedWithTheDocumentAndShownOnTheOriginal() = runBlocking<Unit> {
        val h = Harness()
        try {
            suspend fun settled(condition: (ContinuousEditorState) -> Boolean) =
                withTimeout(2000) { h.presenter.state.first { condition(it) && it.status != ContinuousStatus.LOADING } }
            val open = settled { it.original != null }
            assertTrue(open.permits(ContinuousCapability.ORIGINAL_PITCH))
            assertEquals(0f, open.original!!.pitchSemitones)

            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetOriginalPitch(3f)))
            assertEquals(3.0, h.studio.document.value.project.source!!.pitchSemitones)
            assertEquals(3f, settled { it.original?.pitchSemitones == 3f }.original!!.pitchSemitones)
            assertEquals(h.initial.pads, h.studio.document.value.project.pads, "The key is for listening to the original only")
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetOriginalPitch(25f)), "The key stays within two octaves")
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(0.0, h.studio.document.value.project.source!!.pitchSemitones)
        } finally { h.close() }

        val empty = Harness { it.copy(source = null) }
        try {
            val state = withTimeout(2000) { empty.presenter.state.first { it.status != ContinuousStatus.LOADING && it.permits(ContinuousCapability.TEMPO) } }
            assertFalse(state.permits(ContinuousCapability.ORIGINAL_PITCH), "No original, no key")
            assertFalse(empty.presenter.dispatch(ContinuousEditorAction.SetOriginalPitch(2f)))
        } finally { empty.close() }
    }

    @Test fun liveChopCutsWhereEachTapWasHeardAndUndoEndsThePass() = runBlocking<Unit> {
        val h = Harness { it.copy(source = it.source!!.copy(range = FrameRange(12_000, 90_000))) }
        try {
            fun pads() = h.studio.document.value.project.pads
            val untouched = h.studio.document.value.project
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CapturePad(3, 40_000)), "A tap outside a pass is not an error")
            assertEquals(untouched, h.studio.document.value.project, "It cuts nothing")

            assertTrue(h.presenter.dispatch(ContinuousEditorAction.BeginLiveChop))
            assertEquals(listOf(12_000L), h.ports.seeks, "A pass plays the original from the start of the range")
            withTimeout(2000) { h.presenter.state.first { it.liveChopping && it.originalPlaying } }
            // A tap cuts where it was heard: 60 ms, 2 880 frames at 48 kHz, before the original's position at the press.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CapturePad(3, 42_880)))
            assertEquals(FrameRange(40_000, 90_000), pads()[3].range)
            assertEquals(h.original.hash, pads()[3].assetHash)
            assertEquals(3, h.studio.selection.value.padId, "The chopped PAD is selected")
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CapturePad(0, 62_880)))
            assertEquals(listOf(FrameRange(40_000, 60_000), FrameRange(60_000, 90_000)), listOf(pads()[3].range, pads()[0].range))
            assertEquals(h.original.hash, pads()[0].assetHash, "A PAD that held another sound takes the original")
            assertEquals(PlayMode.GATE, pads()[0].mode, "and keeps its other settings")
            assertEquals(listOf(40_000L, 60_000L), h.studio.document.value.project.source!!.markers)
            val cut = h.studio.document.value.project
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CapturePad(5, 92_880)), "A tap heard after the range is not an error")
            assertEquals(cut, h.studio.document.value.project, "It cuts nothing")

            // Undo takes back one tap and, as in the earlier app, ends the pass with the original.
            val stops = h.ports.stops
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(FrameRange(40_000, 90_000), pads()[3].range)
            assertEquals(h.initial.pads[0], pads()[0])
            assertEquals(stops + 1, h.ports.stops)
            assertFalse(withTimeout(2000) { h.presenter.state.first { !it.liveChopping } }.originalPlaying)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CapturePad(5, 80_000)))
            assertNull(pads()[5].assetHash, "No pass, no cut")
        } finally { h.close() }
    }

    @Test fun liveChopFollowsTheKeyAndEndsWithTheOriginal() = runBlocking<Unit> {
        // An octave up the original plays twice as fast, so the same 60 ms spans twice as many of its frames.
        val h = Harness { it.copy(source = it.source!!.copy(pitchSemitones = 12.0)) }
        try {
            h.ports.playing = true
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.BeginLiveChop))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CapturePad(2, 30_000)))
            assertEquals(FrameRange(24_240, 96_000), h.studio.document.value.project.pads[2].range)

            // Leaving the CHOP stage ends the pass; the original keeps playing.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)))
            withTimeout(2000) { h.presenter.state.first { !it.liveChopping && it.originalPlaying } }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.CHOP)))

            // The original reaching its end by itself ends the pass.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.BeginLiveChop))
            withTimeout(2000) { h.presenter.state.first { it.liveChopping } }
            h.ports.playing = false
            withTimeout(2000) { h.presenter.state.first { !it.liveChopping && !it.originalPlaying } }

            // A reading of the original taken just before a pass began does not end that pass.
            h.ports.duringReading = {
                runBlocking { assertTrue(h.presenter.dispatch(ContinuousEditorAction.BeginLiveChop)) }
                h.ports.playing = true
            }
            withTimeout(2000) { while (h.ports.duringReading != null) delay(5) }
            delay(600)
            assertTrue(h.presenter.state.value.liveChopping, "The pass that began during the reading is still running")

            // Playing on past the range end ends the pass there, with the original.
            val playedOn = h.ports.stops
            h.ports.originalFrame = 96_000
            assertFalse(withTimeout(2000) { h.presenter.state.first { !it.liveChopping } }.originalPlaying)
            assertEquals(playedOn + 1, h.ports.stops)
            h.ports.originalFrame = 0
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.BeginLiveChop))
            withTimeout(2000) { h.presenter.state.first { it.liveChopping } }

            // Stopping the pass stops the original.
            val stops = h.ports.stops
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.EndLiveChop))
            assertEquals(stops + 1, h.ports.stops)
            withTimeout(2000) { h.presenter.state.first { !it.liveChopping } }
        } finally { h.close() }
    }

    @Test fun diagnosticsComeFromTheHostAndCopyingReportsTheResult() = runBlocking<Unit> {
        val h = Harness()
        try {
            assertEquals(ContinuousDiagnostics(outputAttached = true, underruns = 4, pendingFrames = 1_920), h.presenter.diagnostics())
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CopyDiagnostics("音の診断\n音切れ: 4 回")))
            assertEquals(listOf("音の診断\n音切れ: 4 回"), h.ports.copied)
            assertEquals(ContinuousStatus.COPIED, withTimeout(2000) { h.presenter.state.first { it.status == ContinuousStatus.COPIED } }.status)
            h.ports.clipboardWorks = false
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.CopyDiagnostics("x")))
            assertEquals(ContinuousStatus.FAILED, withTimeout(2000) { h.presenter.state.first { it.status == ContinuousStatus.FAILED } }.status)
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
            // Placed freely, exactly where asked, off the beat grid.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE)))
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
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 913)))
            assertEquals(913L, h.studio.document.value.project.clips.single().timelineStartFrame)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertTrue(h.studio.document.value.project.clips.isEmpty())
            assertEquals(h.original.hash, h.studio.document.value.project.source?.assetHash)
        } finally { h.close() }
    }

    @Test fun onTheBeatGridPlacedClipsLandOnBeatsAndKeepThemWhenTheTempoChanges() = runBlocking<Unit> {
        val h = Harness()
        try {
            // The beat grid is the default, and the state carries the exact tempo it follows.
            val ready = h.until { it.permits(ContinuousCapability.PLACE_PAD) }
            assertEquals(ContinuousGrid.BEAT, ready.grid)
            assertEquals(120_000, ready.milliBpm)
            // 120 BPM: a beat is 24 000 frames, and 29 000 is nearest the second beat.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 29_000)))
            val clip = h.until { it.clips.size == 1 }.clips.single()
            assertEquals(24_000L, clip.timelineStartFrame)
            // At half the tempo it is still on the second beat.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetTempo(60)))
            assertEquals(48_000L, h.until { it.milliBpm == 60_000 }.clips.single().timelineStartFrame)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.NudgeClip(clip.id, forward = true)))
            assertEquals(96_000L, h.until { it.clips.single().timelineStartFrame != 48_000L }.clips.single().timelineStartFrame)
            // Free: a nudge is a second.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE)))
            h.until { it.grid == ContinuousGrid.FREE }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.NudgeClip(clip.id, forward = true)))
            assertEquals(144_000L, h.until { it.clips.single().timelineStartFrame != 96_000L }.clips.single().timelineStartFrame)
            // Each move is one Undo.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(96_000L, h.until { it.clips.single().timelineStartFrame != 144_000L }.clips.single().timelineStartFrame)
            // Back on its beat, as it was.
            assertNull(h.studio.document.value.project.clips.single().timelineStartFrame)
        } finally { h.close() }
    }

    @Test fun aFillTheSongCouldNotPlayIsRefusedSayingWhyAndChangesNothing() = runBlocking<Unit> {
        val long = Asset("c".repeat(64), "wav", 100, 48_000, 2, 240_000, "Long")
        val h = Harness(adjust = { p -> p.copy(assets = (p.assets + long).sortedBy { it.hash }.frozen(),
            pads = p.pads.map { if (it.id == 2) Pad(2, long.hash, FrameRange(0, 240_000)) else it }.frozen()) })
        try {
            h.until { it.permits(ContinuousCapability.PLACE_PAD) }
            // A five-second sound every sixteenth through four bars would sound 40 at once; the song plays 32.
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.FillPad(2, null, 0, ContinuousGrid.QUARTER, 4)))
            h.until { it.status == ContinuousStatus.SONG_FULL }
            assertTrue(h.studio.document.value.project.clips.isEmpty())
            // Through one bar, 16 at once, it fills.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.FillPad(2, null, 0, ContinuousGrid.QUARTER, 1)))
            h.until { it.clips.size == 16 }
        } finally { h.close() }
    }

    @Test fun theSwingIsSetWithTheTempoMovingOffSixteenthsPlacedOnTheBeatInOneUndo() = runBlocking<Unit> {
        val h = Harness()
        try {
            assertEquals(500, h.until { it.permits(ContinuousCapability.PLACE_PAD) }.swingPermille)
            // 120 BPM: sixteenths through the first bar, 6 000 frames apart.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.FillPad(0, null, 0, ContinuousGrid.QUARTER, 1)))
            h.until { it.clips.size == 16 }
            // At 60% each eighth's second sixteenth comes 7 200 frames in; the eighths stay.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetTempo(120, 600)))
            val swung = h.until { it.swingPermille == 600 }
            assertEquals((0 until 16).map { it / 2 * 12_000L + if (it % 2 == 1) 7_200 else 0 }, swung.clips.map { it.timelineStartFrame }.sorted())
            // A tempo alone keeps the swing.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetTempo(60)))
            assertEquals(600, h.until { it.milliBpm == 60_000 }.swingPermille)
            // Each is one Undo.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            h.until { it.milliBpm == 120_000 && it.swingPermille == 600 }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals((0 until 16).map { it * 6_000L }, h.until { it.swingPermille == 500 }.clips.map { it.timelineStartFrame }.sorted())
            // Past 50-75%, nothing changes.
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetTempo(120, 760)))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetTempo(120, 490)))
            assertEquals(500, h.studio.document.value.project.tempo.swingPermille)
        } finally { h.close() }
    }

    @Test fun aFillPlacesThePadThroughItsBarsInOneUndoRenderingATransformedPadOnce() = runBlocking<Unit> {
        val h = Harness(render = true)
        try {
            h.until { it.permits(ContinuousCapability.PLACE_PAD) }
            // 120 BPM: song position 100 000 is in the second bar, from 96 000; a quarter beat is 6 000 frames.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.FillPad(0, null, 100_000, ContinuousGrid.QUARTER, 1)))
            assertEquals((0 until 16).map { 96_000L + it * 6_000 }, h.until { it.clips.size == 16 }.clips.map { it.timelineStartFrame }.sorted())
            assertTrue(h.ports.renders.isEmpty())
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            h.until { it.clips.isEmpty() }
            // A PAD an octave up is rendered once for all its places.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetPadPitch(0, 12f)))
            h.until { it.permits(ContinuousCapability.PLACE_PAD) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.FillPad(0, null, 0, ContinuousGrid.BEAT, 1)))
            assertEquals(4, h.until { it.clips.size == 4 }.clips.size)
            assertEquals(1, h.ports.renders.size)
            val project = h.studio.document.value.project
            assertEquals(AssetRole.RENDERED, project.asset(project.clips.map { it.assetHash }.distinct().single()).role)
        } finally { h.close() }
    }

    @Test fun aRepeatCopiesItsBarsIntoTheEmptyBarsAfterInOneUndoAndIsRefusedOverOtherClips() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.until { it.permits(ContinuousCapability.PLACE_PAD) }
            // 120 BPM: four beats of the first bar, repeated four times, fill five bars.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.FillPad(0, null, 0, ContinuousGrid.BEAT, 1)))
            h.until { it.clips.size == 4 }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RepeatBars(0, 1, 4)))
            assertEquals((0 until 20).map { it * 24_000L }, h.until { it.clips.size == 20 }.clips.map { it.timelineStartFrame }.sorted())
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            h.until { it.clips.size == 4 }
            // Over bars that already hold a clip it is refused, and nothing changes.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 100_000)))
            h.until { it.clips.size == 5 }
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.RepeatBars(0, 1, 1)))
            assertEquals(5, h.studio.document.value.project.clips.size)
        } finally { h.close() }
    }

    @Test fun aPassRecordsThePadsWhereTheyWereHeardAndPutsThemOnTheSongInOneUndo() = runBlocking<Unit> {
        val h = Harness(voice = true)
        try {
            val attached = h.until { it.permits(ContinuousCapability.PAD_AUDITION) }
            assertEquals(ContinuousUnavailable.NO_SONG, attached.unavailable[ContinuousCapability.RECORD_HITS], "Nothing to play along with yet")
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            // A song to play along with: PAD 1 on the first and the seventh beat, to 192 000.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(1, null, 0)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(1, null, 144_000)))
            h.until { it.permits(ContinuousCapability.RECORD_HITS) && it.clips.size == 2 }
            val before = h.studio.document.value.project
            // The song stands paused on its second beat, and plays on from there.
            h.engine.transport = TransportState(outputAttached = true, sequenceFrame = 24_000, sequencePaused = true)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            val recording = h.until { it.recordingHits }
            assertTrue(h.engine.commands.last() is EngineCommand.Resume)
            assertTrue(h.engine.commands.none { it is EngineCommand.Seek })
            // Playing along and stopping stay available; changing the document, or a voice take at once, does not.
            assertTrue(recording.permits(ContinuousCapability.PAD_AUDITION) && recording.permits(ContinuousCapability.STOP_ALL))
            assertFalse(recording.permits(ContinuousCapability.RECORD_HITS) || recording.permits(ContinuousCapability.HISTORY))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertFalse(recording.permits(ContinuousCapability.SEPARATE_SOURCE))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SeparateSource))
            assertEquals(0, h.ports.separationPicks)
            // The output plays 1 920 frames behind the engine. Pressed at engine frame 61 000, PAD 0 was heard at 59 080:
            // nearest the third beat (48 000), not the fourth. At 61 500 the same beat again, once is enough; at 73 000
            // (heard 71 080) the fourth.
            for (frame in listOf(61_000L, 61_500L, 73_000L)) {
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.HoldPad(0)))
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.ReleasePad(0)))
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(0, frame)))
            }
            // Before the pass began (24 000), or once the song has ended (192 000): not played to the song.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(0, 23_000)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(0, 192_000)))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(5, 60_000)), "An empty PAD plays nothing")
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.PlaceHits(listOf(ContinuousHit(0, 0)))), "Only a pass places")
            assertEquals(before, h.studio.document.value.project, "Nothing is on the song until the pass ends")

            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopHits))
            val stopped = h.until { !it.recordingHits && it.status == ContinuousStatus.HITS_PLACED && it.clips.size == 4 }
            assertTrue(h.engine.commands.last { it is EngineCommand.Pause || it is EngineCommand.Resume } is EngineCommand.Pause, "Stopping pauses the song")
            assertEquals(listOf(0L, 48_000L, 72_000L, 144_000L), stopped.clips.map { it.timelineStartFrame }.sorted())
            assertTrue(h.studio.document.value.project.clips.all { it.timelineStartFrame == null }, "On the beat")
            assertTrue(stopped.permits(ContinuousCapability.RECORD_HITS), "Another pass can follow")
            val once = h.studio.document.value.project

            // Played again where the same sounds already are, the song is as it was, with no Undo of its own.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            h.until { it.recordingHits }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(0, 61_000)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopHits))
            h.until { !it.recordingHits && it.status == ContinuousStatus.HITS_UNCHANGED }
            assertEquals(once, h.studio.document.value.project)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before, h.studio.document.value.project, "The first pass is one Undo")

            // A pause ends a pass too, before it returns: heard at 96 000, the fifth beat.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            h.until { it.recordingHits }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(0, 97_920)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PauseSong))
            assertEquals(listOf(0L, 3_840L, 5_760L), h.studio.document.value.project.clips.map { it.startTick }.sorted())
            h.until { !it.recordingHits && it.status == ContinuousStatus.HITS_PLACED }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            // So does leaving the stage whose button started it.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            h.until { it.recordingHits }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(0, 97_920)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.CHOP)))
            h.until { !it.recordingHits && it.status == ContinuousStatus.HITS_PLACED }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            // A pass with nothing played leaves the song as it was.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            h.until { it.recordingHits }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopSong))
            h.until { !it.recordingHits && it.status == ContinuousStatus.HITS_EMPTY }
            assertEquals(before, h.studio.document.value.project)
        } finally { h.close() }
    }

    @Test fun aPassTheSongCannotWhollyTakePlacesWhatFitsFromItsStart() = runBlocking<Unit> {
        val long = Asset("c".repeat(64), "wav", 100, 48_000, 2, 240_000, "Long")
        val h = Harness(adjust = { p -> p.copy(assets = (p.assets + long).sortedBy { it.hash }.frozen(),
            pads = p.pads.map { if (it.id == 2) Pad(2, long.hash, FrameRange(0, 240_000)) else it }.frozen()) })
        try {
            h.until { it.permits(ContinuousCapability.PAD_AUDITION) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(1, null, 0)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(1, null, 480_000)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE)))
            h.until { it.clips.size == 2 && it.grid == ContinuousGrid.FREE }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            h.until { it.recordingHits }
            // The five-second PAD heard every 6 000 frames, 40 times: the song plays 32 sounds at once, so the first 32 go on.
            for (k in 0 until 40) assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(2, 1_920L + k * 6_000)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopHits))
            h.until { !it.recordingHits && it.status == ContinuousStatus.HITS_PARTLY }
            assertEquals((0 until 32).map { it * 6_000L }, h.studio.document.value.project.clips.filter { it.assetHash == long.hash }.map { it.timelineStartFrame })
        } finally { h.close() }
    }

    @Test fun aStopWhileThePassIsAddedNeverLosesIt() = runBlocking<Unit> {
        val h = Harness()
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(1, null, 0)))
            h.until { it.permits(ContinuousCapability.RECORD_HITS) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            h.until { it.recordingHits }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(0, 25_920)))
            val before = h.engine.prepares
            h.engine.duringPrepare = { h.presenter.onAction(ContinuousEditorAction.StopAll); delay(100) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopHits))
            assertEquals(listOf(0L, 24_000L), h.studio.document.value.project.clips.map { ContinuousClipEdits.startFrame(h.studio.document.value.project, it) }.sorted())
            assertEquals(before + 1, h.engine.prepares, "Prepared once, never cancelled")
            // Closing the editor with a pass running keeps what was played.
            h.presenter.onAction(ContinuousEditorAction.SelectPad(0))
            h.until { it.selectedPadId == 0 }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            h.until { it.recordingHits }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(0, 49_920)))
            h.presenter.close()
            assertEquals(listOf(0L, 24_000L, 48_000L), h.studio.document.value.project.clips.map { ContinuousClipEdits.startFrame(h.studio.document.value.project, it) }.sorted())
        } finally { h.close() }
    }

    @Test fun aPassEndedByTheSongReportsWhatGoesWrongInsteadOfThrowing() = runBlocking<Unit> {
        // A document from elsewhere with more tracks than the editor edits: its song can play, but no edit of it goes on.
        val h = Harness { p -> p.copy(tracks = (1..17).map { Track("t$it", "T$it", TrackKind.BANK) }.frozen(),
            clips = frozenListOf(Clip("song", "t1", p.pads[1].assetHash!!, FrameRange(0, 48_000), timelineStartFrame = 0))) }
        try {
            h.until { it.permits(ContinuousCapability.RECORD_HITS) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            h.until { it.recordingHits }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(0, 25_920)))
            h.engine.transport = h.engine.transport.copy(playing = false, sequencePaused = false)
            h.until { !it.recordingHits && it.status == ContinuousStatus.FAILED }
            // The poll goes on: another pass still ends with the song.
            h.engine.transport = h.engine.transport.copy(playing = true)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            h.until { it.recordingHits }
            h.engine.transport = h.engine.transport.copy(playing = false, sequencePaused = false)
            h.until { !it.recordingHits && it.status == ContinuousStatus.HITS_EMPTY }
        } finally { h.close() }
    }

    @Test fun theSongsEndEndsAPassAndATransformedPadIsRenderedOnceForAllItsHits() = runBlocking<Unit> {
        val h = Harness(render = true) { p -> p.copy(pads = p.pads.map { if (it.id == 0) it.copy(pitchSemitones = 12.0) else it }.frozen()) }
        try {
            h.until { it.permits(ContinuousCapability.PAD_AUDITION) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(1, null, 0)))
            h.until { it.permits(ContinuousCapability.RECORD_HITS) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordHits))
            h.until { it.recordingHits }
            for (frame in listOf(1_920L, 25_920L)) assertTrue(h.presenter.dispatch(ContinuousEditorAction.CaptureHit(0, frame)))
            assertTrue(h.ports.renders.isEmpty(), "Rendered when the pass ends, not while playing")
            // The song reaches its end: the engine stops playing it, and the pass ends with it.
            h.engine.transport = h.engine.transport.copy(playing = false, sequencePaused = false)
            // Status and document projections arrive on separate flows: wait for both, not an older clip snapshot.
            val ended = h.until { !it.recordingHits && it.status == ContinuousStatus.HITS_PLACED && it.clips.size == 3 }
            // Stop pressed a moment late, and a PAD let go as the pass ended, leave its message in place.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopHits))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ReleasePad(0)))
            delay(100)
            assertEquals(ContinuousStatus.HITS_PLACED, h.presenter.state.value.status)
            assertEquals(1, h.ports.renders.size, "Rendered once for both hits")
            val octave = h.studio.document.value.project.assets.single { it.role == AssetRole.RENDERED }
            assertEquals(2, h.studio.document.value.project.clips.count { it.assetHash == octave.hash })
            assertEquals(listOf(0L, 0L, 24_000L), ended.clips.map { it.timelineStartFrame }.sorted())
        } finally { h.close() }
    }

    @Test fun aTakeGoesToTheFirstEmptyVoicePadAndOntoTheSongWhereItWasSung() = runBlocking<Unit> {
        val h = Harness(voice = true) { p -> p.copy(pads = p.pads.map { if (it.id == 48) Pad(48, p.assets[1].hash, FrameRange(0, 48_000)) else it }.frozen()) }
        try {
            val attached = h.until { it.permits(ContinuousCapability.PAD_AUDITION) }
            assertEquals(ContinuousUnavailable.NO_SONG, attached.unavailable[ContinuousCapability.RECORD_VOICE], "Nothing to sing to yet")
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertTrue(h.ports.voiceStarts.isEmpty())
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            h.until { it.permits(ContinuousCapability.RECORD_VOICE) }
            val before = h.studio.document.value.project
            // The song stands paused half way through.
            h.engine.transport = TransportState(outputAttached = true, sequenceFrame = 24_000, sequencePaused = true)

            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            val recording = h.until { it.recordingVoice }
            assertTrue(h.ports.voiceStarts.single() in 1..300)
            assertEquals(1, h.ports.cues)
            assertTrue(h.ports.commandsAtCue.last() is EngineCommand.Resume, "The song starts right before the cue")
            assertTrue(h.ports.commandsAtCue.none { it is EngineCommand.Seek }, "It plays on from where it stood")
            // Playing along and stopping stay available; changing the document does not.
            assertTrue(recording.permits(ContinuousCapability.PAD_AUDITION) && recording.permits(ContinuousCapability.SONG_PLAYBACK))
            assertFalse(recording.permits(ContinuousCapability.RECORD_VOICE) || recording.permits(ContinuousCapability.HISTORY)
                || recording.permits(ContinuousCapability.SONG_SEEK) || recording.permits(ContinuousCapability.IMPORT_AUDIO))
            assertEquals(ContinuousUnavailable.RECORDING, recording.unavailable[ContinuousCapability.TEMPO])
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TapPad(0)))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetTempo(100)))
            h.until { it.status == ContinuousStatus.RECORDING_BUSY }
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before, h.studio.document.value.project)

            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopVoice))
            val stopped = h.until { !it.recordingVoice && it.status == ContinuousStatus.VOICE_SAVED && it.selectedPadId == 49 }
            assertTrue(h.engine.commands.last { it is EngineCommand.Pause || it is EngineCommand.Resume } is EngineCommand.Pause, "Stopping pauses the song")
            assertEquals(listOf("VOICE 2"), h.ports.voiceNames, "BANK D's first PAD is taken, so the take is its second")
            val p = h.studio.document.value.project
            val take = p.assets.single { it.name == "VOICE 2" }
            assertEquals(Pad(49, take.hash, FrameRange(0, 96_000), "VOICE 2", gain = .9f), p.pads[49])
            val track = p.tracks.single { it.kind == TrackKind.VOCAL }
            assertEquals("VOICE", track.name)
            val clip = p.clips.single { it.assetHash == take.hash }
            // The lead-in before the cue (2 400) and the output's delay (1 920) are cut from its start, and it ends where
            // the song ends (48 000), half a second after it began.
            assertEquals(FrameRange(4_320, 28_320), clip.range)
            assertEquals(24_000L, clip.timelineStartFrame)
            assertEquals(track.id, clip.trackId)
            assertTrue(stopped.permits(ContinuousCapability.RECORD_VOICE), "The take's PAD is selected and another take can follow")

            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before, h.studio.document.value.project, "The take is one Undo")
        } finally { h.close() }
    }

    @Test fun aTakeEndsWithTheSongOrAtItsLimitAndKeepsWhatWasSung() = runBlocking<Unit> {
        val h = Harness(voice = true)
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            // The song had played to its end: recording starts it again from the top.
            h.engine.transport = TransportState(outputAttached = true, sequenceFrame = 48_000)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertTrue(h.ports.commandsAtCue.any { it is EngineCommand.Seek && it.sequenceFrame == 0L })
            // The song reaching its end (or the host stopping it) ends the take.
            h.engine.transport = h.engine.transport.copy(playing = false)
            h.until { !it.recordingVoice && it.status == ContinuousStatus.VOICE_SAVED }
            val first = h.studio.document.value.project.clips.last()
            assertEquals(0L, first.timelineStartFrame)
            assertEquals("VOICE 1", h.studio.document.value.project.pads[48].name)
            // Pressing stop just after the song ended the take finds nothing to stop and keeps the take's message.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopVoice))
            delay(200) // Time for a cleared status to reach the state, were it cleared.
            assertEquals(ContinuousStatus.VOICE_SAVED, h.presenter.state.value.status)

            // A take that reached its length limit stops by itself, pausing the song.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            h.ports.full = true
            h.until { !it.recordingVoice && it.status == ContinuousStatus.VOICE_LIMIT }
            assertFalse(h.engine.transport.playing)
            val p = h.studio.document.value.project
            assertEquals("VOICE 2", p.pads[49].name)
            assertEquals(1, p.tracks.count { it.kind == TrackKind.VOCAL }, "Takes share the voice track")
            h.ports.full = false

            // The output is lost: the song cannot be heard any more, so the take ends and is kept.
            h.engine.transport = h.engine.transport.copy(sequencePaused = true)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            h.engine.transport = h.engine.transport.copy(outputAttached = false)
            h.until { !it.recordingVoice && it.status == ContinuousStatus.VOICE_SAVED }
            assertEquals("VOICE 3", h.studio.document.value.project.pads[50].name)
            h.engine.transport = h.engine.transport.copy(outputAttached = true)
            h.until { it.permits(ContinuousCapability.RECORD_VOICE) }

            // Leaving the stage, stopping everything or closing the editor end the take and keep it; tapping the stage
            // already shown does not.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.CAPTURE)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)))
            h.until { it.recordingVoice }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.SAVE)))
            assertEquals("VOICE 5", h.studio.document.value.project.pads[52].name)
            assertFalse(h.until { it.stage == ContinuousStage.SAVE }.recordingVoice)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopAll))
            assertEquals("VOICE 6", h.studio.document.value.project.pads[53].name)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            h.presenter.close()
            assertEquals("VOICE 7", h.studio.document.value.project.pads[54].name)
            assertEquals(0, h.ports.discards)
        } finally { h.close() }
    }

    @Test fun refusedMicrophoneFullBankAndEmptyTakesAreExplained() = runBlocking<Unit> {
        val h = Harness(voice = true) { p -> p.copy(pads = p.pads.map { if (it.id in 48..63) Pad(it.id, p.assets[1].hash, FrameRange(0, 48_000)) else it }.frozen()) }
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            val placed = h.studio.document.value.project
            for ((answer, status) in listOf(VoiceStart.DENIED to ContinuousStatus.MIC_DENIED, VoiceStart.UNAVAILABLE to ContinuousStatus.MIC_UNAVAILABLE,
                VoiceStart.NO_ROOM to ContinuousStatus.VOICE_NO_ROOM)) {
                h.ports.microphone = answer
                h.engine.commands.clear()
                assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
                assertFalse(h.until { it.status == status }.recordingVoice)
                assertTrue(h.engine.commands.none { it is EngineCommand.Resume }, "The song does not start")
            }
            h.ports.microphone = VoiceStart.STARTED

            // BANK D is full: the take goes onto the song only.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopVoice))
            h.until { it.status == ContinuousStatus.VOICE_SAVED_SONG_ONLY }
            assertEquals(placed.pads, h.studio.document.value.project.pads)
            assertEquals("VOICE 1", h.studio.document.value.project.clips.last().let { c -> h.studio.document.value.project.asset(c.assetHash).name })

            // Nothing recorded, or stopped before anything was sung to the song: nothing is added.
            val once = h.studio.document.value.project
            for ((frames, status) in listOf(null to ContinuousStatus.VOICE_EMPTY, 4_000L to ContinuousStatus.VOICE_TOO_SHORT)) {
                h.ports.takeFrames = frames
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopVoice))
                h.until { it.status == status }
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectPad(0)), "Clears the status")
            }
            // Storage failed: said so, and the editor records again afterwards.
            h.ports.takeFrames = 96_000
            h.ports.storeFails = true
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.StopVoice))
            assertFalse(h.until { it.status == ContinuousStatus.VOICE_NOT_SAVED }.recordingVoice)
            h.ports.storeFails = false
            assertEquals(once, h.studio.document.value.project)
        } finally { h.close() }
    }

    @Test fun aTakeEndedAtTheSongEndLeavesTheNextOneStartingFromTheTop() = runBlocking<Unit> {
        val h = Harness(voice = true)
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            h.engine.transport = TransportState(outputAttached = true, sequenceFrame = 24_000, sequencePaused = true)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            // The song reaches its end, and the singer presses stop right then, perhaps before the poll noticed.
            h.engine.transport = h.engine.transport.copy(playing = false, sequenceFrame = 48_000)
            h.presenter.dispatch(ContinuousEditorAction.StopVoice)
            h.until { !it.recordingVoice && it.status == ContinuousStatus.VOICE_SAVED }
            assertFalse(h.engine.transport.sequencePaused, "Nothing pauses a song that already ended")
            assertTrue(h.engine.commands.last { it is EngineCommand.Pause || it is EngineCommand.Resume } is EngineCommand.Resume)
            // So the next take (and a plain play) start the song from the top again.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertTrue(h.ports.commandsAtCue.any { it is EngineCommand.Seek && it.sequenceFrame == 0L })
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopVoice))
            h.engine.transport = TransportState(outputAttached = true, sequenceFrame = 48_000, sequencePaused = true)
            h.engine.commands.clear()
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlaySong))
            assertTrue(h.engine.commands.any { it is EngineCommand.Seek && it.sequenceFrame == 0L }, "Paused at the end plays from the top")
        } finally { h.close() }
    }

    @Test fun aMicrophoneThatStopsWorkingEndsTheTakeAndSaysSo() = runBlocking<Unit> {
        val h = Harness(voice = true)
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            h.ports.interrupted = true
            h.until { !it.recordingVoice && it.status == ContinuousStatus.VOICE_INTERRUPTED }
            assertFalse(h.engine.transport.playing, "The song pauses with the take")
            assertEquals("VOICE 1", h.studio.document.value.project.pads[48].name, "What was sung is kept")
        } finally { h.close() }
    }

    @Test fun aTakeWhoseFirstFrameCameLateIsPlacedLaterAndEndsWithTheSong() = runBlocking<Unit> {
        val h = Harness(voice = true)
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            // The microphone's first frame came 100 ms after the song started; the output was 40 ms behind.
            h.ports.takeLead = -4_800
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopVoice))
            h.until { it.status == ContinuousStatus.VOICE_SAVED }
            val clip = h.studio.document.value.project.clips.last()
            assertEquals(2_880L, clip.timelineStartFrame, "60 ms after where the song started")
            assertEquals(FrameRange(0, 45_120), clip.range, "Whole from its first frame, up to the song's end at 48 000")
        } finally { h.close() }
    }

    @Test fun aStopWhileTheTakeIsAddedNeverLosesIt() = runBlocking<Unit> {
        val h = Harness(voice = true)
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            // A stop pressed while the take's edit is prepared does not cancel that edit.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            val before = h.engine.prepares
            h.engine.duringPrepare = { h.presenter.onAction(ContinuousEditorAction.StopAll); delay(100) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopVoice))
            assertEquals("VOICE 1", h.studio.document.value.project.pads[48].name)
            assertEquals(before + 1, h.engine.prepares, "Prepared once, never cancelled")
            // Let the queued stop run first: UI events run in order.
            h.presenter.onAction(ContinuousEditorAction.SelectPad(0))
            h.until { it.selectedPadId == 0 }
            // One sent just before (so nothing held it back) cancels the edit, which is then added again.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            h.engine.duringPrepare = { h.studio.dispatch(Action.CancelWork) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopVoice))
            assertNull(h.engine.duringPrepare)
            assertEquals("VOICE 2", h.studio.document.value.project.pads[49].name)
            h.until { it.status == ContinuousStatus.VOICE_SAVED }
            // The cancelled attempt's own notice comes later and does not replace the message.
            delay(200)
            assertEquals(ContinuousStatus.VOICE_SAVED, h.presenter.state.value.status)
        } finally { h.close() }
    }

    @Test fun anEditCancelledWhileItIsPreparedSaysSo() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.until { it.permits(ContinuousCapability.PLACE_PAD) }
            h.engine.duringPrepare = { h.studio.dispatch(Action.CancelWork) }
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetPadGain(0, .5f)))
            assertEquals(ContinuousStatus.CANCELLED, h.until { it.status == ContinuousStatus.CANCELLED }.status)
            assertEquals(1f, h.studio.document.value.project.pads[0].gain, "The edit was not made")
        } finally { h.close() }
    }

    @Test fun aSongThatRefusesTheTakeStillLeavesItOnThePad() = runBlocking<Unit> {
        val h = Harness(voice = true)
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            h.engine.refuses = { p -> p.tracks.any { it.kind == TrackKind.VOCAL } }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopVoice))
            h.until { it.status == ContinuousStatus.VOICE_SAVED_PAD_ONLY }
            // The refused attempt's own notice comes later and does not replace the message.
            delay(200)
            assertEquals(ContinuousStatus.VOICE_SAVED_PAD_ONLY, h.presenter.state.value.status)
            val p = h.studio.document.value.project
            assertEquals("VOICE 1", p.pads[48].name)
            assertTrue(p.tracks.none { it.kind == TrackKind.VOCAL } && p.clips.size == 1, "Only the PAD changed")
        } finally { h.close() }
    }

    @Test fun aTakeNeverOutgrowsTheEngineBudgetAndHostsWithoutAMicrophoneKeepItOff() = runBlocking<Unit> {
        // The original fills the engine's PCM budget but for half a second.
        val limit = com.choplab.core.ProgramCompiler.RESIDENT_FRAME_LIMIT
        val h = Harness { p ->
            val long = p.assets[0].copy(frames = limit - 48_000 - 24_000)
            p.copy(assets = frozenListOf(long, p.assets[1]), source = Source(long.hash, FrameRange(0, long.frames)),
                pads = p.pads.map { if (it.id == 2) Pad(2, long.hash, FrameRange(0, 48_000)) else it }.frozen())
        }
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            // Once the placement's work is done: while it runs, recording is only busy.
            val placed = h.until { it.clips.isNotEmpty() && it.unavailable[ContinuousCapability.RECORD_VOICE] != ContinuousUnavailable.BUSY }
            assertEquals(ContinuousUnavailable.NOT_CONNECTED, placed.unavailable[ContinuousCapability.RECORD_VOICE], "No microphone on this host")
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            h.ports.voice = true
            h.ports.playing = true
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            h.until { it.status == ContinuousStatus.VOICE_NO_ROOM }
            assertTrue(h.ports.voiceStarts.isEmpty(), "The microphone is not opened")
            assertEquals(0, h.ports.stops, "and nothing that plays is stopped")
        } finally { h.close() }
    }

    @Test fun aPadWithItsPitchChangedIsRenderedAndPlacedAsThatSound() = runBlocking<Unit> {
        // A host that cannot render such a PAD keeps it off the song.
        val plain = Harness()
        try {
            plain.until { it.permits(ContinuousCapability.PLACE_PAD) }
            assertTrue(plain.presenter.dispatch(ContinuousEditorAction.SetPadPitch(0, 12f)))
            assertFalse(plain.until { !it.permits(ContinuousCapability.PLACE_PAD) }.permits(ContinuousCapability.PLACE_PAD))
        } finally { plain.close() }
        val h = Harness(render = true)
        try {
            h.until { it.permits(ContinuousCapability.PLACE_PAD) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE)))
            // An untouched PAD is placed as its own sound.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(1, null, 0)))
            assertTrue(h.ports.renders.isEmpty())
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetPadPitch(0, 12f)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetPadGain(0, .6f)))
            assertTrue(h.until { it.permits(ContinuousCapability.PLACE_PAD) }.permits(ContinuousCapability.PLACE_PAD))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 96_000)))
            assertEquals(12.0, h.ports.renders.single().pitchSemitones)
            val project = h.studio.document.value.project
            val placed = project.clips.single { it.timelineStartFrame == 96_000L }
            val rendered = project.asset(placed.assetHash)
            assertEquals(AssetRole.RENDERED, rendered.role)
            assertEquals(FrameRange(0, 24_000), placed.range, "The whole rendered sound, half as long an octave up")
            assertEquals(.6f, placed.gain, "With the PAD's level")
            // One Undo takes the clip and its rendered sound away.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            val undone = h.studio.document.value.project
            assertTrue(undone.clips.none { it.timelineStartFrame == 96_000L } && undone.assets.none { it.role == AssetRole.RENDERED })
            // A sound that cannot be made is not placed.
            h.ports.renderFails = true
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 96_000)))
            // Wait for this refusal itself: the Undo's engine preparation may still show LOADING first.
            h.until { it.status == ContinuousStatus.PLACE_FAILED }
        } finally { h.close() }
        // Nor one the engine has no room left for: a five-minute PAD an octave down would need ten.
        val full = Harness(render = true) { p ->
            val long = p.assets[1].copy(frames = 48_000L * 300, byteCount = 44 + 48_000L * 300 * 4)
            p.copy(assets = frozenListOf(p.assets[0], long),
                pads = p.pads.map { if (it.assetHash == long.hash) it.copy(range = FrameRange(0, long.frames), pitchSemitones = -12.0) else it }.frozen())
        }
        try {
            full.until { it.permits(ContinuousCapability.PLACE_PAD) }
            assertFalse(full.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            full.until { it.status == ContinuousStatus.PLACE_NO_ROOM }
            assertTrue(full.ports.renders.isEmpty(), "Nothing is rendered")
        } finally { full.close() }
    }

    @Test fun aPadIsScratchedWhereTheHandMovesItAndContinuesFromThereNextTime() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.until { it.permits(ContinuousCapability.SCRATCH) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenScratch))
            assertEquals(ContinuousScratchTarget.PAD, h.until { it.scratch != null }.scratch?.target, "The selected PAD holds a sound")
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchHold))
            val start = h.engine.commands.filterIsInstance<EngineCommand.ScratchStart>().single()
            assertEquals(0, start.padId)
            assertEquals(0.0, start.sourceFrame, "From the PAD's start")
            assertTrue(h.until { it.scratch?.holding == true }.scratch!!.holding)
            // 100 pixels forward at normal sensitivity: 48 000 / (60 × 7) frames each, as in the earlier app.
            h.presenter.onAction(ContinuousEditorAction.ScratchDrag(100f))
            val aim = 100 * NORMAL_FRAMES_PER_PIXEL
            withTimeout(2_000) { while (h.engine.commands.filterIsInstance<EngineCommand.ScratchPosition>().lastOrNull()?.sourceFrame != aim) delay(5) }
            assertEquals(aim / 48_000, h.presenter.readout().scratchFraction.toDouble(), 1e-3)
            // Each move stays well inside the engine's limit of eight times normal speed.
            val moves = h.engine.commands.filterIsInstance<EngineCommand.ScratchPosition>()
            assertTrue(moves.size >= 2, "A long drag goes on in several moves")
            (listOf(0.0) + moves.map { it.sourceFrame }).zipWithNext().zip(moves) { (a, b), move ->
                assertTrue(kotlin.math.abs(b - a) / move.durationFrames <= 8.0, "Too fast: $a → $b over ${move.durationFrames}")
            }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchLetGo))
            assertTrue(h.engine.commands.last() is EngineCommand.ScratchEnd)
            assertFalse(h.until { it.scratch?.holding == false }.scratch!!.holding)
            // The next hold picks the record up where it was left.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchHold))
            assertEquals(aim, h.engine.commands.filterIsInstance<EngineCommand.ScratchStart>().last().sourceFrame)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CloseScratch))
            assertTrue(h.engine.commands.last() is EngineCommand.ScratchEnd, "Closing lets go")
            assertNull(h.until { it.scratch == null }.scratch)
        } finally { h.close() }
    }

    @Test fun theOriginalIsScratchedWithinItsRangeAndPlaysOnOnlyIfItWasPlaying() = runBlocking<Unit> {
        val h = Harness { it.copy(source = it.source!!.copy(range = FrameRange(12_000, 90_000))) }
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlayOriginal))
            h.until { it.permits(ContinuousCapability.SCRATCH) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenScratch))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetScratchTarget(ContinuousScratchTarget.ORIGINAL)))
            h.ports.originalFrame = 40_000
            val plays = h.ports.plays
            h.ports.playing = false
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchHold))
            assertEquals(listOf(40_000L, 12_000L, 90_000L), h.ports.scratchStarts.single(), "Held where it was heard, within its range")
            assertFalse(h.until { !it.originalPlaying }.originalPlaying, "Paused under the hand")
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetScratchTarget(ContinuousScratchTarget.PAD)), "Not while held")
            // Pulled far back: it stops at the range start.
            h.presenter.onAction(ContinuousEditorAction.ScratchDrag(-2_000f))
            withTimeout(2_000) { while (h.ports.scratchMoves.lastOrNull()?.first != 12_000.0) delay(5) }
            assertTrue(h.ports.scratchMoves.all { it.first >= 12_000.0 })
            // The engine plays on what the hand paused; the editor shows what it did and starts nothing itself.
            h.ports.playing = true
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchLetGo))
            assertEquals(1, h.ports.scratchEnds)
            assertTrue(h.until { it.originalPlaying }.originalPlaying)
            assertEquals(plays, h.ports.plays, "Never started a second time")
            // Held again while paused: letting go leaves it paused.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopOriginal))
            h.ports.playing = false
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchHold))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchLetGo))
            assertFalse(h.until { !it.originalPlaying }.originalPlaying)
            assertEquals(plays, h.ports.plays)
        } finally { h.close() }
    }

    @Test fun aSoundingPadIsTakenWhereItPlaysAndEarlyDragsAreKept() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.until { it.permits(ContinuousCapability.SCRATCH) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenScratch))
            // The engine takes a sounding PAD where it plays, and says where that is.
            h.engine.playhead = 20_000.0
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchHold))
            h.presenter.onAction(ContinuousEditorAction.ScratchDrag(100f))
            val aim = 20_000 + 100 * NORMAL_FRAMES_PER_PIXEL
            withTimeout(2_000) { while (h.engine.commands.filterIsInstance<EngineCommand.ScratchPosition>().lastOrNull()?.sourceFrame != aim) delay(5) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchLetGo))
            // A drag made before the original is even loaded and held still counts.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetScratchTarget(ContinuousScratchTarget.ORIGINAL)))
            h.ports.originalFrame = 10_000
            h.ports.scratchStartDelay = 600
            h.presenter.onAction(ContinuousEditorAction.ScratchHold)
            h.presenter.onAction(ContinuousEditorAction.ScratchDrag(50f))
            // The cut fader is not queued behind the hold either.
            h.presenter.onAction(ContinuousEditorAction.SetScratchCut(.5f))
            withTimeout(300) { h.presenter.state.first { it.scratch?.cut == .5f } }
            withTimeout(2_000) { while (h.ports.scratchMoves.lastOrNull()?.first != 10_000 + 50 * NORMAL_FRAMES_PER_PIXEL) delay(5) }
            assertEquals(.5f, h.ports.scratchCuts.last(), "Closed halfway before the first move")
            h.presenter.onAction(ContinuousEditorAction.ScratchLetGo)
            withTimeout(2_000) { while (h.ports.scratchEnds != 1) delay(5) }
        } finally { h.close() }
    }

    @Test fun aMoveRefusedAfterTheScratchEndedStopsQuietly() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.until { it.permits(ContinuousCapability.SCRATCH) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenScratch))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchHold))
            // The output was rebuilt under the hand, say: the engine no longer knows this scratch.
            h.engine.refuseScratchMoves = true
            h.presenter.onAction(ContinuousEditorAction.ScratchDrag(100f))
            withTimeout(2_000) { while (h.engine.commands.none { it is EngineCommand.ScratchPosition }) delay(5) }
            h.presenter.onAction(ContinuousEditorAction.ScratchDrag(100f))
            delay(200)
            assertEquals(1, h.engine.commands.count { it is EngineCommand.ScratchPosition }, "No more moves once one was refused")
            assertNull(h.presenter.state.value.status, "Not reported as a failure")
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchLetGo))
            assertFalse(h.until { it.scratch?.holding == false }.scratch!!.holding)
        } finally { h.close() }
    }

    @Test fun theCutFaderStopAllAndLeavingTheStageEndAScratchCleanly() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.until { it.permits(ContinuousCapability.SCRATCH) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenScratch))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetScratchCut(0f)))
            assertTrue(h.engine.commands.none { it is EngineCommand.ScratchCut }, "Kept for the next hold")
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchHold))
            val afterStart = h.engine.commands.dropWhile { it !is EngineCommand.ScratchStart }
            assertEquals(0f, (afterStart[1] as EngineCommand.ScratchCut).gain, "Applied right after taking hold")
            // The fader moves at once, like a drag; a held platter passes it on.
            h.presenter.onAction(ContinuousEditorAction.SetScratchCut(.5f))
            assertEquals(.5f, h.until { it.scratch?.cut == .5f }.scratch!!.cut)
            withTimeout(2_000) { while (h.engine.commands.filterIsInstance<EngineCommand.ScratchCut>().last().gain != .5f) delay(5) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetScratchCut(1f)))
            withTimeout(2_000) { while (h.engine.commands.filterIsInstance<EngineCommand.ScratchCut>().last().gain != 1f) delay(5) }
            // Stop all stops everything, the scratch with it, before letting go: nothing plays on.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopAll))
            val end = h.engine.commands.indexOfLast { it is EngineCommand.ScratchEnd }
            val stop = h.engine.commands.indexOfLast { it is EngineCommand.Stop }
            assertTrue(stop in 0 until end)
            assertFalse(h.until { it.scratch?.holding == false }.scratch!!.holding)
            // Leaving the BEAT stage closes the panel.
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchHold))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.SAVE)))
            assertTrue(h.engine.commands.last() is EngineCommand.ScratchEnd)
            assertNull(h.until { it.stage == ContinuousStage.SAVE }.scratch)
        } finally { h.close() }
    }

    @Test fun aScreenReaderNudgeScratchesAnEighthAndLetsGo() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.until { it.permits(ContinuousCapability.SCRATCH) }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenScratch))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ScratchNudge(forward = true)))
            val commands = h.engine.commands.filter { it is EngineCommand.ScratchStart || it is EngineCommand.ScratchPosition || it is EngineCommand.ScratchEnd }
            assertTrue(commands.first() is EngineCommand.ScratchStart && commands.last() is EngineCommand.ScratchEnd)
            val moves = commands.filterIsInstance<EngineCommand.ScratchPosition>()
            assertEquals(6_000.0, moves.last().sourceFrame, 1e-6, "An eighth of 48 000 frames")
            assertTrue(moves.size >= 3, "A short movement, not a jump")
            assertFalse(h.until { it.scratch?.holding == false }.scratch!!.holding)
        } finally { h.close() }
        // On a long original, at most half a second of it.
        val long = Harness(originalFrames = 48_000L * 60) { it.copy(source = it.source!!.copy(range = FrameRange(0, 48_000L * 60))) }
        try {
            long.until { it.permits(ContinuousCapability.SCRATCH) }
            assertTrue(long.presenter.dispatch(ContinuousEditorAction.OpenScratch))
            assertTrue(long.presenter.dispatch(ContinuousEditorAction.SetScratchTarget(ContinuousScratchTarget.ORIGINAL)))
            assertTrue(long.presenter.dispatch(ContinuousEditorAction.ScratchNudge(forward = true)))
            assertEquals(24_000.0, long.ports.scratchMoves.last().first, 1e-6)
            assertEquals(1, long.ports.scratchEnds)
        } finally { long.close() }
    }

    @Test fun scratchNeedsASoundOrTheOriginal() = runBlocking<Unit> {
        val h = Harness { p -> p.copy(source = null, pads = (0..127).map { Pad(it) }.frozen()) }
        try {
            h.until { it.permits(ContinuousCapability.PAD_AUDITION) }
            assertFalse(h.presenter.state.value.permits(ContinuousCapability.SCRATCH))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.OpenScratch))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ScratchHold), "Nothing to hold without the panel")
            h.presenter.onAction(ContinuousEditorAction.ScratchDrag(10f))
            assertTrue(h.engine.commands.none { it is EngineCommand.ScratchStart || it is EngineCommand.ScratchPosition })
        } finally { h.close() }
    }

    @Test fun anEarlierAppsProjectFileSaysWhatWasRescuedFromIt() = runBlocking {
        for ((rescued, status) in listOf(Notice.Rescued(2, 0) to ContinuousStatus.RESCUED, Notice.Rescued(3, 1) to ContinuousStatus.RESCUED_PARTLY,
                Notice.Rescued(2, 2) to ContinuousStatus.RESCUED_TOO_LONG, Notice.Rescued(0, 0) to ContinuousStatus.RESCUED_NOTHING)) {
            val h = Harness(rescue = rescued)
            try {
                h.ports.openLocation = Location("old.choplab")
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenProject))
                h.until { it.status == status }
                assertNull(h.studio.document.value.savedRevision, "A rescued document is new until it is saved")
            } finally { h.close() }
        }
    }

    /** 48 kHz frames a screen pixel moves a platter at normal sensitivity, computed as the presenter does. */
    private val NORMAL_FRAMES_PER_PIXEL = 48_000 / (60.0 * 7.0)

    @Test fun anImmediateOpenKeepsItsNoticeEvenBeforeThePresentersDispatcherRuns() = runBlocking<Unit> {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val gate = java.util.concurrent.CountDownLatch(1)
        executor.submit { gate.await() }
        val dispatcher = executor.asCoroutineDispatcher()
        val h = Harness(rescue = Notice.Rescued(2, 0), presenterDispatcher = dispatcher)
        try {
            h.ports.openLocation = Location("old.choplab")
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenProject))
            withTimeout(5_000) { while (h.studio.work.value.jobId != null || h.studio.work.value.preparationId != null) delay(5) }
            // A mailbox turn after Open's completion ensures its notice was emitted before the dispatcher is released.
            h.studio.dispatch(Action.RefreshTransport)
            gate.countDown()
            h.until { it.status == ContinuousStatus.RESCUED }
        } finally { gate.countDown(); h.close(); dispatcher.close() }
    }

    private class Harness(kits: Boolean = false, voice: Boolean = false, originalFrames: Long = 96_000, render: Boolean = false,
                          system: SystemAudioCapture? = null,
                          presenterDispatcher: CoroutineDispatcher = Dispatchers.Default,
                          rescue: Notice.Rescued? = null, adjust: (Project) -> Project = { it }) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val original = Asset("a".repeat(64), "wav", 100, 48_000, 2, originalFrames, "Original")
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
                /** With [rescue], every file opens as the earlier app's project file it was rescued from. */
                override suspend fun openDocument(location: Location) = OpenedProject(initial, rescue)
            }, object : ExportPort {
                override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt = error("Must select arrangement explicitly")
                override suspend fun export(project: Project, target: PlaybackTarget, request: ExportRequest): ExportReceipt {
                    exportTarget = target
                    return ExportReceipt(request.frames.toLong(), 48_000, 2, request.bits)
                }
            }, engine), initial)
        val ports = FakePorts(kits, voice, render).also { it.engine = engine; it.system = system }
        /** Waits for [condition]; generous, since a loaded CI runner can take a while, and a wait that never ends fails. */
        suspend fun until(condition: (ContinuousEditorState) -> Boolean) = withTimeout(5000) { presenter.state.first(condition) }
        val presenter = ContinuousEditorPresenter(studio, CoroutineScope(scope.coroutineContext + presenterDispatcher), ports)
        suspend fun close() { presenter.close(); studio.dispatch(Action.Close); scope.cancel() }
    }
    private class FakeEngine : EnginePort {
        val commands = java.util.concurrent.CopyOnWriteArrayList<EngineCommand>()
        @Volatile var prepares = 0
        /** Runs once inside the next preparation, as something happening while an edit is prepared. */
        @Volatile var duringPrepare: (suspend () -> Unit)? = null
        /** Programs this engine refuses to prepare, like a song with too many clips at once. */
        @Volatile var refuses: (Project) -> Boolean = { false }
        /** Where a scratched PAD sounds, as the engine reports it; below zero while it is silent. */
        @Volatile var playhead = -1.0
        /** Refuses scratch moves, as an engine that no longer knows the scratch. */
        @Volatile var refuseScratchMoves = false
        override suspend fun prepare(project: Project, patternId: String, revision: Long) = EngineProgram(revision = revision)
        override suspend fun prepare(project: Project, target: PlaybackTarget, revision: Long): EngineProgram {
            prepares++
            duringPrepare?.let { duringPrepare = null; it() }
            check(!refuses(project)) { "Refused by the engine" }
            return EngineProgram(revision = revision)
        }
        override suspend fun apply(command: EngineCommand): Boolean {
            commands += command
            if (command is EngineCommand.ScratchPosition && refuseScratchMoves) return false
            // The song's transport as the engine would report it.
            transport = when (command) {
                is EngineCommand.Resume -> transport.copy(playing = true, sequencePaused = false)
                is EngineCommand.Pause -> transport.copy(playing = false, sequencePaused = true)
                is EngineCommand.Stop -> transport.copy(playing = false, sequencePaused = false, scratchFrame = -1.0)
                is EngineCommand.Seek -> transport.copy(sequenceFrame = command.sequenceFrame)
                is EngineCommand.ScratchStart -> transport.copy(scratchFrame = if (playhead >= 0) playhead else command.sourceFrame)
                is EngineCommand.ScratchEnd -> transport.copy(scratchFrame = -1.0)
                else -> transport
            }
            return true
        }
        @Volatile var transport = TransportState(outputAttached = true)
        override fun snapshot() = transport
    }
    private class FakePorts(private val kits: Boolean = false, voice: Boolean = false, private val render: Boolean = false) : ContinuousEditorPorts {
        override val padRenderAvailable get() = render
        val renders = java.util.concurrent.CopyOnWriteArrayList<Pad>()
        @Volatile var renderFails = false
        /** Renders as a host would name and size it: an octave up halves the sound. */
        override suspend fun renderPad(pad: Pad, source: Asset): Asset? {
            renders += pad
            if (renderFails) return null
            val frames = kotlin.math.ceil(requireNotNull(pad.range).length / 2.0.pow(pad.pitchSemitones / 12)).toLong()
            return Asset("c".repeat(64), "wav", 44 + frames * 8, 48_000, 2, frames, "${source.name} +12", AssetRole.RENDERED, derivedFrom = source.hash)
        }
        @Volatile var originalFrame = 0L
        @Volatile var stops = 0
        val seeks = java.util.concurrent.CopyOnWriteArrayList<Long>()
        /** What the output reports about the original; null when this host does not report it. */
        @Volatile var playing: Boolean? = null
        /** Runs once inside the next reading of [playing], after the value was taken: a change the reading misses. */
        @Volatile var duringReading: (() -> Unit)? = null
        var songGain = 1f
        var originalGain = 1f
        var exportFrames = 0L
        override val originalAvailable = true
        override val separationAvailable = true
        var separationPicks = 0
        override suspend fun separateSource(source: Asset): Location? { separationPicks++; return null }
        override val onlineAvailable = true
        var online: Location? = null
        var onlinePicks = 0
        override suspend fun chooseOnline(): Location? { onlinePicks++; return online }
        override val libraryAvailable = true
        var library: Location? = null
        var libraryPicks = 0
        override suspend fun chooseLibrary(): Location? { libraryPicks++; return library }
        override suspend fun chooseAudio(): Location? = null
        @Volatile var openLocation: Location? = null
        override suspend fun chooseOpen(): Location? = openLocation
        override suspend fun chooseSave() = Location("save")
        override suspend fun chooseExport(frames: Long): ExportRequest {
            exportFrames = frames
            return ExportRequest(Location("export"), frames.toInt())
        }
        override suspend fun peaks(asset: Asset) = listOf(.2f, .4f)
        override fun readout() = ContinuousEditorReadout(originalFrame = originalFrame)
        override suspend fun setSongMonitorGain(gain: Float): Boolean { songGain = gain; return true }
        override suspend fun setOriginalMonitorGain(gain: Float): Boolean { originalGain = gain; return true }
        override fun originalPlaying(): Boolean? {
            val value = playing
            duringReading?.let { duringReading = null; it() }
            return value
        }
        @Volatile var plays = 0
        override suspend fun playOriginal(asset: Asset): Boolean { plays++; return true }
        val scratchStarts = java.util.concurrent.CopyOnWriteArrayList<List<Long>>()
        val scratchMoves = java.util.concurrent.CopyOnWriteArrayList<Pair<Double, Int>>()
        val scratchCuts = java.util.concurrent.CopyOnWriteArrayList<Float>()
        @Volatile var scratchEnds = 0
        /** How long taking the original takes, as loading it can. */
        @Volatile var scratchStartDelay = 0L
        override suspend fun scratchOriginalStart(asset: Asset, from: Long, start: Long, end: Long): Boolean {
            delay(scratchStartDelay)
            scratchStarts += listOf(from, start, end); return true
        }
        override suspend fun scratchOriginalTo(position: Double, durationFrames: Int): Boolean { scratchMoves += position to durationFrames; return true }
        override suspend fun scratchOriginalCut(gain: Float): Boolean { scratchCuts += gain; return true }
        override suspend fun scratchOriginalEnd(): Boolean { scratchEnds++; return true }
        override suspend fun stopOriginal(): Boolean { stops++; return true }
        override suspend fun seekOriginal(frame: Long): Boolean { seeks += frame; return true }
        val copied = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var clipboardWorks = true
        override fun diagnostics() = ContinuousDiagnostics(outputAttached = true, underruns = 4, pendingFrames = outputDelay)
        override suspend fun copyText(text: String): Boolean = clipboardWorks.also { if (it) copied += text }
        override val drumKitsAvailable get() = kits
        @Volatile var voice = voice
        @Volatile var microphone = VoiceStart.STARTED
        val voiceStarts = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val voiceNames = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var cues = 0
        @Volatile var discards = 0
        @Volatile var full = false
        @Volatile var recordedMillis = 0L
        @Volatile var interrupted = false
        @Volatile var storeFails = false
        /** The take the next stop returns: this many frames, captured this far ahead of the cue; null records nothing. */
        @Volatile var takeFrames: Long? = 96_000
        @Volatile var takeLead = 2_400L
        @Volatile var outputDelay: Long? = 1_920
        /** Engine commands seen when the song was cued. */
        @Volatile var commandsAtCue: List<EngineCommand> = emptyList()
        var engine: FakeEngine? = null
        override val voiceAvailable get() = voice
        var system: SystemAudioCapture? = null
        override val systemAudioCapture get() = system
        override suspend fun startVoice(maxSeconds: Int): VoiceStart { voiceStarts += maxSeconds; return microphone }
        override fun cueVoice() { cues++; commandsAtCue = engine?.commands?.toList().orEmpty() }
        override fun voiceFull() = full
        override fun voiceRecordedMillis() = recordedMillis
        override fun voiceInterrupted() = interrupted
        override suspend fun stopVoice(name: String): VoiceTake? {
            voiceNames += name
            if (storeFails) error("Storage full")
            val frames = takeFrames ?: return null
            return VoiceTake(Asset((voiceNames.size + 0xA0).toString(16).padStart(64, '0'), "wav", 44 + frames * 4, 48_000, 1, frames, name), takeLead)
        }
        override suspend fun discardVoice() { discards++ }
        fun kitHash(kitId: String, slot: Int) = (DrumKits.catalog.indexOfFirst { it.id == kitId } * 16 + slot + 1).toString(16).padStart(64, '0')
        override suspend fun drumKit(kitId: String): List<Asset>? = if (!kits) null else (0 until 16).map { slot ->
            val frames = DrumKits.frames(slot)
            Asset(kitHash(kitId, slot), "wav", 44L + frames * 4, 48_000, 1, frames.toLong(), DrumKits.soundName(DrumKits.kit(kitId), slot), AssetRole.RENDERED)
        }
    }
}
