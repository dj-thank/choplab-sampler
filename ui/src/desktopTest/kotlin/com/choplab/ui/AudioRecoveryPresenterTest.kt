package com.choplab.ui

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.test.*

class AudioRecoveryPresenterTest {
    @Test fun failedWaveformsStayDistinctFromSilenceAndOnlyTheFailedAssetRetries() = runBlocking<Unit> {
        val calls = mutableMapOf<String, Int>()
        val h = Harness { base -> object : ContinuousEditorPorts by base {
            override suspend fun peaks(asset: Asset): List<Float> {
                val count = (calls[asset.hash] ?: 0) + 1; calls[asset.hash] = count
                if (asset.hash == SOURCE.hash && count == 1) error("Transient PCM failure")
                return listOf(.2f, .75f)
            }
        } }
        try {
            h.until { it.assetWaveforms[SOURCE.hash] == WaveformLoadState.FAILED && it.assetWaveforms[OTHER.hash] == WaveformLoadState.READY }
            val before = h.studio.document.value
            assertTrue(h.presenter.state.value.original!!.peaks.isEmpty())
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RetryWaveforms(SOURCE.hash)))
            h.until { it.assetWaveforms[SOURCE.hash] == WaveformLoadState.READY && it.original!!.peaks == listOf(.2f, .75f) }
            assertEquals(2, calls[SOURCE.hash]); assertEquals(1, calls[OTHER.hash])
            assertEquals(before, h.studio.document.value)
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.RetryWaveforms(OTHER.hash)))
        } finally { h.close() }
    }

    @Test fun recoveredAcceptedTakeRetriesOnlyCleanupWithoutChangingSourceRevisionOrTakes() = runBlocking<Unit> {
        var pending = true; var cleanups = 0
        val h = Harness { base -> object : ContinuousEditorPorts by base {
            override fun voiceInputReadout() = RecordingInputReadout(pendingSave = pending, pendingAccepted = pending)
            override suspend fun stopVoice(name: String): VoiceTake? = error("An accepted recording must not be imported again")
            override suspend fun acknowledgeVoiceTake() { cleanups++; pending = false }
        } }
        try {
            h.until { it.pendingRecording && it.pendingRecordingApplied }
            val before = h.studio.document.value
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RetryRecordingSave))
            h.until { !it.pendingRecording }
            assertEquals(1, cleanups); assertEquals(before, h.studio.document.value)
        } finally { h.close() }
    }

    @Test fun punchPublicationAndDocumentRejectionBothRetryTheOriginalTwoCandidateContext() = runBlocking<Unit> {
        for (boundary in listOf("publish", "edit")) {
            var pending = false; var publishRetries = 0; var prepares = 0; var acknowledgements = 0
            val captured = VocalCapturedSession(VOICE, listOf(VocalCapturedPass(FrameRange(0, 24_000), 0), VocalCapturedPass(FrameRange(24_000, 48_000), 120)))
            val punch = object : VocalPunchPort {
                override val progress = MutableStateFlow(VocalPunchProgress())
                override suspend fun capture(project: Project, expectedRevision: Long, request: VocalPunchRequest, stopped: () -> Boolean): VocalPunchResult {
                    pending = true
                    return if (boundary == "publish") VocalPunchResult(problem = PunchProblem.SAVE_FAILED) else VocalPunchResult(captured)
                }
                override fun requestStop() = Unit
                override fun interrupt() = Unit
            }
            val h = Harness { base -> object : ContinuousEditorPorts by base {
                override val vocalPunch = punch
                override fun voiceInputReadout() = RecordingInputReadout(pendingSave = pending)
                override suspend fun retryPunchTake(name: String): VocalCapturedSession { publishRetries++; return captured }
                override suspend fun prepareVoiceTakeAcceptance(project: Project, revision: Long) { prepares++ }
                override suspend fun stopVoice(name: String): VoiceTake? = error("Punch candidates must not become SOURCE")
                override suspend fun acknowledgeVoiceTake() { acknowledgements++; pending = false }
            } }
            try {
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenVocalPunch))
                val controller = assertNotNull(h.presenter.vocalPunch.value)
                controller.update { it.copy(startSeconds = "0.25", endSeconds = "0.75", passes = 2, preRollBars = 0, countInBars = 0) }
                if (boundary == "edit") h.engine.rejectVoice = true
                val before = h.studio.document.value
                assertFalse(controller.record())
                h.until { it.pendingRecording && !it.recordingPunch }
                assertEquals(before, h.studio.document.value)
                h.engine.rejectVoice = false
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.RetryRecordingSave))
                h.until { !it.pendingRecording }
                val after = h.studio.document.value
                assertEquals(before.revision + 1, after.revision)
                assertEquals(before.project.source, after.project.source)
                assertEquals(2, after.project.takes.size)
                val captureStart = VocalPunchRequest(12_000, 36_000, 0, 0, 2).plan(before.project).captureStart
                assertEquals(listOf(captureStart, captureStart + 120), after.project.takes.map { it.timelineStartFrame })
                assertEquals(captured.passes.map { it.range }, after.project.takes.map { it.range })
                assertEquals(1, acknowledgements)
                assertEquals(if (boundary == "publish") 1 else 0, publishRetries)
                assertTrue(prepares >= 1)
                assertFalse(h.presenter.dispatch(ContinuousEditorAction.RetryRecordingSave))
            } finally { h.engine.rejectVoice = false; h.close() }
        }
    }

    @Test fun changedPunchDocumentRequiresExplicitSourceRecoveryChoice() = runBlocking<Unit> {
        var pending = false; var sourceRecoveries = 0
        val punch = object : VocalPunchPort {
            override val progress = MutableStateFlow(VocalPunchProgress())
            override suspend fun capture(project: Project, expectedRevision: Long, request: VocalPunchRequest, stopped: () -> Boolean): VocalPunchResult {
                pending = true; return VocalPunchResult(problem = PunchProblem.SAVE_FAILED)
            }
            override fun requestStop() = Unit
            override fun interrupt() = Unit
        }
        val h = Harness { base -> object : ContinuousEditorPorts by base {
            override val vocalPunch = punch
            override fun voiceInputReadout() = RecordingInputReadout(pendingSave = pending)
            override suspend fun stopVoice(name: String): VoiceTake { sourceRecoveries++; return VoiceTake(VOICE, 0) }
            override suspend fun acknowledgeVoiceTake() { pending = false }
        } }
        try {
            h.presenter.dispatch(ContinuousEditorAction.OpenVocalPunch)
            val controller = assertNotNull(h.presenter.vocalPunch.value)
            controller.update { it.copy(endSeconds = "1", preRollBars = 0, countInBars = 0) }
            assertFalse(controller.record())
            assertTrue(h.studio.dispatch(Action.Edit(Intent.Rename("Changed elsewhere"))).accepted)
            h.until { it.pendingRecordingCanRecoverSource }
            val changed = h.studio.document.value
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.RetryRecordingSave))
            assertEquals(changed, h.studio.document.value); assertEquals(0, sourceRecoveries)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecoverRecordingAsSource))
            assertEquals(VOICE.hash, h.studio.document.value.project.source?.assetHash)
            assertEquals(1, sourceRecoveries)
        } finally { h.close() }
    }

    private class Harness(decorate: (ContinuousEditorPorts) -> ContinuousEditorPorts) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        class TestEngine : EnginePort {
            @Volatile var rejectVoice = false
            override suspend fun prepare(project: Project, patternId: String, revision: Long) = EngineProgram(revision = revision)
            override suspend fun prepare(project: Project, target: PlaybackTarget, revision: Long): EngineProgram {
                check(!rejectVoice || project.takes.isEmpty())
                return EngineProgram(revision = revision)
            }
            override suspend fun apply(command: EngineCommand) = true
            override fun snapshot() = TransportState(outputAttached = true)
        }
        val engine = TestEngine()
        val initial = Project(assets = frozenListOf(SOURCE, OTHER), source = Source(SOURCE.hash, FrameRange(0, SOURCE.frames)))
        val studio = Studio(scope, Services(object : AssetStore {
            override suspend fun containsVerified(asset: Asset) = true
            override suspend fun write(asset: Asset, bytes: ByteArray) = Unit
            override suspend fun read(asset: Asset) = byteArrayOf()
        }, object : ImportPort { override suspend fun import(location: Location) = SOURCE }, object : ProjectPort {
            override suspend fun save(project: Project, revision: Long, location: Location) = Unit
            override suspend fun open(location: Location) = initial
        }, object : ExportPort {
            override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt = error("Unused")
        }, engine), initial)
        val presenter = ContinuousEditorPresenter(studio, scope, decorate(object : ContinuousEditorPorts {
            override val voiceAvailable = true
            override suspend fun chooseAudio(): Location? = null
            override suspend fun chooseOpen(): Location? = null
            override suspend fun chooseSave(): Location? = null
            override suspend fun chooseExport(frames: Long): ExportRequest? = null
            override suspend fun peaks(asset: Asset) = listOf(.5f)
            override fun readout() = ContinuousEditorReadout()
            override suspend fun setSongMonitorGain(gain: Float) = true
            override suspend fun stopOriginal() = true
        }))
        suspend fun until(condition: (ContinuousEditorState) -> Boolean) = withTimeout(5000) { presenter.state.first(condition) }
        suspend fun close() { presenter.close(); studio.dispatch(Action.Close); scope.cancel() }
    }
    private companion object {
        val SOURCE = Asset("a".repeat(64), "wav", 44 + 96_000L * 4, 48_000, 1, 96_000, "SOURCE")
        val OTHER = Asset("b".repeat(64), "wav", 44 + 48_000L * 4, 48_000, 1, 48_000, "Other")
        val VOICE = Asset("c".repeat(64), "wav", 44 + 48_000L * 4, 48_000, 1, 48_000, "VOICE")
    }
}
