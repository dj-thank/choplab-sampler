package com.choplab.sampler.model

import com.choplab.sampler.audio.BuiltInDrumKits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionBootstrapTest {
    private val audio = PcmAudio(101L, "source.wav", ShortArray(1_000), 1_000)

    @Test
    fun launchTargetFollowsRecoveredAudibleWork() {
        val sourceOnly = SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount)
        val samplePad = PadModel(0, audio, 0, 500)
        val pads = sourceOnly.pads.toMutableList().also { it[0] = samplePad }
        val beat = sourceOnly.copy(
            pads = pads,
            activeSteps = setOf(stepKey(0, 0)),
        )
        val sourceWithStarter = BuiltInDrumKits.installStarterKit(sourceOnly)

        assertEquals(ProjectLaunchTarget.CAPTURE, inferProjectLaunchTarget(SamplerUiState()))
        assertEquals(ProjectLaunchTarget.CHOP, inferProjectLaunchTarget(sourceOnly))
        assertEquals(ProjectLaunchTarget.BEAT, inferProjectLaunchTarget(beat))
        assertEquals(
            ProjectLaunchTarget.BEAT,
            inferProjectLaunchTarget(SamplerUiState(pads = pads)),
        )
        assertEquals(
            ProjectLaunchTarget.CHOP,
            inferProjectLaunchTarget(
                sourceWithStarter,
                starterOnly = BuiltInDrumKits.hasUntouchedStarterDrums(sourceWithStarter),
            ),
        )
        assertEquals(
            ProjectLaunchTarget.CAPTURE,
            inferProjectLaunchTarget(SamplerUiState(), starterOnly = true),
        )
    }

    @Test
    fun starterKitCanOnlyEnterAnOtherwiseUneditedProduction() {
        val blankWithSource = SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount)
        val occupiedPads = blankWithSource.pads.toMutableList().also {
            it[0] = PadModel(0, audio, 0, 500)
        }

        assertTrue(starterDrumKitInstallationAllowed(SamplerUiState()))
        assertTrue(starterDrumKitInstallationAllowed(blankWithSource))
        assertFalse(starterDrumKitInstallationAllowed(blankWithSource.copy(pads = occupiedPads)))
        assertFalse(
            starterDrumKitInstallationAllowed(
                blankWithSource.copy(activeSteps = setOf(stepKey(0, 0))),
            ),
        )
        assertFalse(
            starterDrumKitInstallationAllowed(
                blankWithSource.copy(patternArrangement = PatternArrangement(songModeEnabled = true)),
            ),
        )
    }

    @Test
    fun scratchReturnPrefersTheActiveLoopThenAnAudibleTransport() {
        val pads = SamplerUiState().pads.toMutableList().also {
            it[0] = PadModel(0, audio, 0, 500, playMode = PadPlayMode.LOOP)
            it[1] = PadModel(1, audio, 500, 1_000)
        }
        val transport = SamplerUiState(
            pads = pads,
            activeSteps = setOf(stepKey(1, 4)),
            transportPlaying = true,
        )
        val loop = transport.copy(loopingPadIndex = 0)

        assertEquals(ScratchReturnTarget.PadLoop(0), selectScratchReturnTarget(loop))
        assertEquals(ScratchReturnTarget.Transport, selectScratchReturnTarget(transport))
        assertEquals(ScratchReturnTarget.None, selectScratchReturnTarget(SamplerUiState()))
        val songTransport = transport.copy(
            activeSteps = emptySet(),
            patternArrangement = PatternArrangement(
                storedStepsBySlot = listOf(emptySet(), setOf(stepKey(1, 4))),
                songSections = listOf(1, 0, 1, 0),
                songModeEnabled = true,
            ),
        )
        assertEquals(ScratchReturnTarget.Transport, selectScratchReturnTarget(songTransport))
        assertTrue(scratchReturnTargetIsValid(ScratchReturnTarget.Transport, songTransport))
        assertTrue(scratchReturnTargetIsValid(ScratchReturnTarget.PadLoop(0), loop))
        assertFalse(
            scratchReturnTargetIsValid(
                ScratchReturnTarget.PadLoop(0),
                loop.copy(pads = List(SamplerConfig.PAD_COUNT) { PadModel(it) }),
            ),
        )
    }
}
