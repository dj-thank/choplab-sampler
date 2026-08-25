package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLayeringPolicyTest {
    @Test
    fun retriggerConflictsWithTheSamePadButNotAnotherPad() {
        assertTrue(samePadVoiceConflictsForRetrigger(activePadIndex = 4, requestedPadIndex = 4))
        assertFalse(samePadVoiceConflictsForRetrigger(activePadIndex = 7, requestedPadIndex = 4))
    }

    @Test
    fun selectedVocalLoopIsNotStartedAgainAsItsOwnCompanionLayer() {
        val audio = PcmAudio(
            name = "voice.wav",
            samples = ShortArray(128) { 8_000 },
            sampleRate = 48_000,
        )
        val pads = listOf(
            PadModel(4, audio, 0, audio.frameCount, contentKind = PadContentKind.VOCAL),
            PadModel(5, audio, 0, audio.frameCount, contentKind = PadContentKind.VOCAL),
            PadModel(6, audio, 0, audio.frameCount, contentKind = PadContentKind.SAMPLE),
            PadModel(7, contentKind = PadContentKind.VOCAL),
        )

        assertEquals(
            listOf(5),
            pads.vocalCompanionPadIndicesForLoopStart(loopPadIndex = 4),
        )
    }

    @Test
    fun matchingChokeTriggerStopsTheLoopOwnerAndEveryOwnedVocalCompanion() {
        val audio = PcmAudio(
            name = "loop-session.wav",
            samples = ShortArray(128) { 8_000 },
            sampleRate = 48_000,
        )
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            when (index) {
                4 -> PadModel(
                    globalIndex = index,
                    audio = audio,
                    startFrame = 0,
                    endFrame = audio.frameCount,
                    playMode = PadPlayMode.LOOP,
                    chokeGroup = 1,
                )
                5 -> PadModel(
                    globalIndex = index,
                    audio = audio,
                    startFrame = 0,
                    endFrame = audio.frameCount,
                    contentKind = PadContentKind.VOCAL,
                )
                6 -> PadModel(
                    globalIndex = index,
                    audio = audio,
                    startFrame = 0,
                    endFrame = audio.frameCount,
                    chokeGroup = 1,
                )
                else -> PadModel(index)
            }
        }
        val state = SamplerUiState(
            pads = pads,
            loopingPadIndex = 4,
            loopPlayheadFrame = 72,
        )

        val transition = state.chokeLoopSessionTransition(triggeredPadIndex = 6)

        assertTrue(transition.stopsLoopSession)
        assertEquals(1, transition.chokeGroup)
        assertEquals(listOf(5, 4), transition.padIndicesToStop)
        assertEquals(null, transition.state.loopingPadIndex)
        assertEquals(-1, transition.state.loopPlayheadFrame)
    }

    @Test
    fun nonmatchingOrInvalidTriggerPreservesTheLoopSession() {
        val audio = PcmAudio(
            name = "loop-negative.wav",
            samples = ShortArray(64) { 4_000 },
            sampleRate = 48_000,
        )
        fun pad(index: Int, chokeGroup: Int) = PadModel(
            globalIndex = index,
            audio = audio,
            startFrame = 0,
            endFrame = audio.frameCount,
            chokeGroup = chokeGroup,
        )
        val basePads = List(SamplerConfig.PAD_COUNT) { index ->
            when (index) {
                4 -> pad(index, chokeGroup = 1).copy(playMode = PadPlayMode.LOOP)
                5 -> pad(index, chokeGroup = 2)
                6 -> pad(index, chokeGroup = 0)
                else -> PadModel(index)
            }
        }
        val state = SamplerUiState(
            pads = basePads,
            loopingPadIndex = 4,
            loopPlayheadFrame = 32,
        )

        listOf(4, 5, 6, -1, SamplerConfig.PAD_COUNT).forEach { requestedPad ->
            val transition = state.chokeLoopSessionTransition(requestedPad)
            assertFalse(transition.stopsLoopSession)
            assertTrue(transition.padIndicesToStop.isEmpty())
            assertEquals(state, transition.state)
        }
        val groupZeroOwner = state.copy(
            pads = basePads.toMutableList().apply { this[4] = pad(4, chokeGroup = 0) },
        )
        assertFalse(groupZeroOwner.chokeLoopSessionTransition(6).stopsLoopSession)
    }
}
