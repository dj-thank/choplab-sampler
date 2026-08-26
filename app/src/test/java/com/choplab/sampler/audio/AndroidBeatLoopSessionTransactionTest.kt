package com.choplab.sampler.audio

import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.PendingSourceCommand
import com.choplab.sampler.model.ProductionSession
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBeatLoopSessionTransactionTest {
    private val audio = PcmAudio(
        name = "loop.wav",
        samples = ShortArray(800) { 4_000 },
        sampleRate = 48_000,
    )

    @Test
    fun rejectedCompleteSessionPreservesProductionAndHistory() {
        val session = ProductionSession()
        val engine = FakePlaybackEngine(admitLoopSession = false)
        val transaction = AndroidBeatLoopSessionTransaction(session, engine)
        val before = state()

        val result = transaction.start(before, loopPadIndex = 0)

        assertTrue(result is AndroidBeatLoopSessionResult.Rejected)
        assertEquals(0L, session.revision)
        assertFalse(session.canUndo)
        assertEquals(1, engine.loopRequests.size)
        assertEquals(PadPlayMode.LOOP, engine.loopRequests.single().first.playMode)
        assertEquals(listOf(96), engine.loopRequests.single().second.map(PadModel::globalIndex))

        val later = session.applyEdit(before, before.copy(bpm = 126f))
        assertEquals(1L, later.revision)
        assertTrue(later.state.canUndo)
    }

    @Test
    fun admittedCompleteSessionCommitsOneEditAndOneRuntimeRequest() {
        val session = ProductionSession()
        val engine = FakePlaybackEngine(admitLoopSession = true)
        val transaction = AndroidBeatLoopSessionTransaction(session, engine)

        val result = transaction.start(state(), loopPadIndex = 0) as AndroidBeatLoopSessionResult.Started

        assertEquals(1, engine.loopRequests.size)
        assertEquals(listOf(0, 1), result.changedPads.map(PadModel::globalIndex))
        assertEquals(0, result.transition.state.loopingPadIndex)
        assertEquals(PadPlayMode.LOOP, result.transition.state.pads[0].playMode)
        assertEquals(PadPlayMode.ONE_SHOT, result.transition.state.pads[1].playMode)
        assertFalse(result.transition.state.transportPlaying)
        assertEquals(-1, result.transition.state.currentStep)
        assertTrue(result.transition.state.sourcePlaying)
        assertEquals(PendingSourceCommand.STOP, result.transition.state.pendingSourceCommand)
        assertEquals(1L, result.transition.revision)
        assertTrue(result.transition.state.canUndo)
    }

    @Test
    fun unexpectedEngineFailureCancelsThePlanAndPropagates() {
        val session = ProductionSession()
        val engine = FakePlaybackEngine(
            admitLoopSession = true,
            loopFailure = AssertionError("test fatal loop admission"),
        )
        val transaction = AndroidBeatLoopSessionTransaction(session, engine)
        val before = state()

        val failure = assertThrows(AssertionError::class.java) {
            transaction.start(before, loopPadIndex = 0)
        }

        assertEquals("test fatal loop admission", failure.message)
        assertEquals(0L, session.revision)
        assertFalse(session.canUndo)
        val later = session.applyEdit(before, before.copy(bpm = 130f))
        assertEquals(1L, later.revision)
    }

    @Test
    fun runtimeRestartUsesOneCompleteRequestAndCanExcludeExistingCompanions() {
        val withCompanions = FakePlaybackEngine(admitLoopSession = false)
        val withoutCompanions = FakePlaybackEngine(admitLoopSession = false)
        val pads = state().pads.map { pad ->
            if (pad.globalIndex == 0) pad.copy(playMode = PadPlayMode.LOOP) else pad
        }

        assertFalse(startAndroidPadLoopSession(withCompanions, pads, loopPadIndex = 0))
        assertFalse(
            startAndroidPadLoopSession(
                withoutCompanions,
                pads,
                loopPadIndex = 0,
                includeCompanions = false,
            ),
        )

        assertEquals(listOf(96), withCompanions.loopRequests.single().second.map(PadModel::globalIndex))
        assertTrue(withoutCompanions.loopRequests.single().second.isEmpty())
    }

    private fun state(): SamplerUiState {
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            when (index) {
                0 -> PadModel(index, audio, 0, audio.frameCount)
                1 -> PadModel(index, audio, 0, audio.frameCount, playMode = PadPlayMode.LOOP)
                96 -> PadModel(
                    globalIndex = index,
                    audio = audio,
                    startFrame = 0,
                    endFrame = audio.frameCount,
                    contentKind = PadContentKind.VOCAL,
                )
                else -> PadModel(index)
            }
        }
        return SamplerUiState(
            currentAudio = audio,
            rangeEndFrame = audio.frameCount,
            pads = pads,
            selectedPad = 0,
            sourcePlaying = true,
            transportPlaying = true,
            currentStep = 7,
        )
    }

    private class FakePlaybackEngine(
        private val admitLoopSession: Boolean,
        private val loopFailure: Throwable? = null,
    ) : SamplerPlaybackEngine {
        val loopRequests = mutableListOf<Pair<PadModel, List<PadModel>>>()
        override val currentStep = -1
        override val currentSourceFrame = -1
        override val sourcePlaying = false
        override val currentLoopPad = -1
        override val currentLoopFrame = -1
        override val currentScratchPad = -1
        override val currentScratchFrame = -1
        override val outputSampleRate = 48_000
        override fun start(): Result<Unit> = Result.success(Unit)
        override fun updatePad(pad: PadModel) = Unit
        override fun updateAllPads(pads: List<PadModel>) = Unit
        override fun triggerPad(globalIndex: Int): Long? = 1L
        override fun startPadLoopSession(loopPad: PadModel, companionPads: List<PadModel>): Boolean {
            loopFailure?.let { throw it }
            loopRequests += loopPad to companionPads
            return admitLoopSession
        }
        override fun stopPad(globalIndex: Int) = Unit
        override fun beginScratch(globalIndex: Int, startFrame: Int) = Unit
        override fun beginSourceScratch(audio: PcmAudio, startFrame: Int, endFrame: Int) = Unit
        override fun updateScratchSpeed(speed: Float) = Unit
        override fun endScratch() = Unit
        override fun releasePad(globalIndex: Int) = Unit
        override fun releasePadIfOwned(globalIndex: Int, ownership: Long) = Unit
        override fun preview(audio: PcmAudio, startFrame: Int, endFrame: Int) = Unit
        override fun playSource(audio: PcmAudio, startFrame: Int, pitchSemitones: Float) = Unit
        override fun stopSource() = Unit
        override fun setPattern(activeSteps: Set<Int>, bpm: Float, swing: Float) = Unit
        override fun startTransport() = Unit
        override fun stopTransport() = Unit
        override fun stopAllVoices() = Unit
        override fun shutdown() = Unit
    }
}
