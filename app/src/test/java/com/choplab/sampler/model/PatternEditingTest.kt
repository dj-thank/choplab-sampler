package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PatternEditingTest {
    @Test
    fun beatLaneDistinguishesSelectedSoundFromAnotherSoundInTheSameBank() {
        val selectedPad = SamplerConfig.PADS_PER_BANK
        val otherPad = selectedPad + 1
        val steps = setOf(stepKey(selectedPad, 0), stepKey(otherPad, 4))

        assertEquals(LaneStepState.SELECTED_SOUND, laneStepState(steps, 1, selectedPad, 0))
        assertEquals(LaneStepState.OTHER_SOUND, laneStepState(steps, 1, selectedPad, 4))
        assertEquals(LaneStepState.OFF, laneStepState(steps, 1, selectedPad, 8))
    }

    @Test
    fun `quarter repeat replaces only selected pad with four beats`() {
        val existing = setOf(
            stepKey(3, 2),
            stepKey(3, 7),
            stepKey(18, 5),
        )

        val result = existing.replacePadSteps(3, RepeatGrid.QUARTER)

        assertEquals(
            setOf(
                stepKey(3, 0),
                stepKey(3, 4),
                stepKey(3, 8),
                stepKey(3, 12),
                stepKey(18, 5),
            ),
            result,
        )
    }

    @Test
    fun `eighth and sixteenth repeats fill their musical grids`() {
        assertEquals(
            (0 until SamplerConfig.STEP_COUNT step 2).map { stepKey(7, it) }.toSet(),
            emptySet<Int>().replacePadSteps(7, RepeatGrid.EIGHTH),
        )
        assertEquals(
            (0 until SamplerConfig.STEP_COUNT).map { stepKey(7, it) }.toSet(),
            emptySet<Int>().replacePadSteps(7, RepeatGrid.SIXTEENTH),
        )
    }

    @Test
    fun `clear removes selected pad while preserving every other layer`() {
        val existing = setOf(
            stepKey(0, 0),
            stepKey(0, 4),
            stepKey(SamplerConfig.PADS_PER_BANK, 0),
            stepKey(SamplerConfig.PADS_PER_BANK * 2, 0),
            stepKey(SamplerConfig.PADS_PER_BANK * 3, 0),
        )

        assertEquals(
            setOf(
                stepKey(SamplerConfig.PADS_PER_BANK, 0),
                stepKey(SamplerConfig.PADS_PER_BANK * 2, 0),
                stepKey(SamplerConfig.PADS_PER_BANK * 3, 0),
            ),
            existing.clearPadSteps(0),
        )
    }

    @Test
    fun `bank activity reports layered banks for one step`() {
        val pattern = setOf(
            stepKey(0, 4),
            stepKey(SamplerConfig.PADS_PER_BANK + 1, 4),
            stepKey(SamplerConfig.PADS_PER_BANK * 2 + 2, 4),
            stepKey(SamplerConfig.PADS_PER_BANK * 2 + 15, 3),
        )

        assertEquals(setOf(0, 1, 2), pattern.activeBanksAtStep(4))
        assertEquals(setOf(2), pattern.activeBanksAtStep(3))
        assertEquals(emptySet<Int>(), pattern.activeBanksAtStep(5))
    }

    @Test
    fun `audible steps omit events on empty pads`() {
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 0 || index == 1) {
                PadModel(
                    globalIndex = index,
                    audio = PcmAudio(name = "kick", samples = shortArrayOf(1, 2), sampleRate = 48_000),
                    startFrame = 0,
                    endFrame = 2,
                    playMode = if (index == 1) PadPlayMode.LOOP else PadPlayMode.ONE_SHOT,
                )
            } else {
                PadModel(globalIndex = index)
            }
        }
        val pattern = setOf(stepKey(0, 0), stepKey(1, 8), stepKey(16, 0), stepKey(32, 4))

        assertEquals(setOf(stepKey(0, 0)), pattern.audibleStepKeys(pads))
    }

    @Test
    fun `pattern content accepts a loop without step events`() {
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 1) {
                PadModel(
                    globalIndex = index,
                    audio = PcmAudio(name = "loop", samples = shortArrayOf(1, 2), sampleRate = 48_000),
                    startFrame = 0,
                    endFrame = 2,
                    playMode = PadPlayMode.LOOP,
                )
            } else {
                PadModel(globalIndex = index)
            }
        }

        assertEquals(true, emptySet<Int>().hasAudiblePatternContent(pads))
        assertEquals(false, setOf(stepKey(0, 0)).hasAudiblePatternContent(pads.map { PadModel(it.globalIndex) }))
    }

    @Test
    fun `pattern content accepts a vocal take without step events`() {
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == SamplerConfig.VOCAL_BANK_INDEX * SamplerConfig.PADS_PER_BANK) {
                PadModel(
                    globalIndex = index,
                    audio = PcmAudio(name = "voice", samples = shortArrayOf(1, 2), sampleRate = 48_000),
                    startFrame = 0,
                    endFrame = 2,
                    contentKind = PadContentKind.VOCAL,
                )
            } else {
                PadModel(index)
            }
        }

        assertEquals(true, emptySet<Int>().hasAudiblePatternContent(pads))
    }

    @Test
    fun `repeat grid recognition matches only exact selected pad pattern`() {
        val layered = emptySet<Int>()
            .replacePadSteps(0, RepeatGrid.QUARTER)
            .replacePadSteps(SamplerConfig.PADS_PER_BANK, RepeatGrid.EIGHTH)

        assertEquals(RepeatGrid.QUARTER, layered.repeatGridForPad(0))
        assertEquals(RepeatGrid.EIGHTH, layered.repeatGridForPad(SamplerConfig.PADS_PER_BANK))
        assertEquals(null, (layered + stepKey(0, 2)).repeatGridForPad(0))
    }
}
