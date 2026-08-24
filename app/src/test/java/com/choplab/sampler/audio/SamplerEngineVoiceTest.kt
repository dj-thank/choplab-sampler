package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplerEngineVoiceTest {
    @Test
    fun toneFilterCoefficientIsBoundedAndComputedAtTheControlBoundary() {
        assertEquals(1f, SamplerDspPrimitives.toneFilterAlpha(tone = 1f, outputSampleRate = 48_000), 0f)
        val dark = SamplerDspPrimitives.toneFilterAlpha(tone = 0.15f, outputSampleRate = 48_000)
        val medium = SamplerDspPrimitives.toneFilterAlpha(tone = 0.65f, outputSampleRate = 48_000)
        assertTrue(dark in 0f..1f)
        assertTrue(medium in 0f..1f)
        assertTrue(medium > dark)
    }

    @Test
    fun loopStartRetiresPreviewAndTargetAuditionButKeepsOtherLayers() {
        val audio = PcmAudio(
            name = "loop.wav",
            samples = ShortArray(128) { 8_000 },
            sampleRate = 48_000,
        )
        fun activeVoice(padIndex: Int) = SamplerEngine.Voice(
            SamplerEngine.PadSnapshot(
                padIndex = padIndex,
                audio = audio,
                startFrame = 0,
                endFrame = audio.frameCount,
                pitchSemitones = 0f,
                tone = 1f,
                gain = 1f,
                reverse = false,
                playMode = PadPlayMode.ONE_SHOT,
                chokeGroup = 0,
            ),
            outputSampleRate = 48_000,
        )
        val preview = activeVoice(-1)
        val targetAudition = activeVoice(4)
        val intentionalOtherLayer = activeVoice(33)

        retireConflictingVoicesForLoopStart(
            voices = arrayOf(preview, targetAudition, intentionalOtherLayer),
            loopPadIndex = 4,
        )

        assertFalse(preview.active)
        assertFalse(targetAudition.active)
        assertTrue(intentionalOtherLayer.active)
    }

    @Test
    fun loopStartBoundaryStopsTheImportedSourceVoice() {
        val audio = PcmAudio(
            name = "source.wav",
            samples = ShortArray(128) { 8_000 },
            sampleRate = 48_000,
        )
        val sourceState = SourcePlaybackState()
        val playingGeneration = sourceState.issuePlay()
        assertTrue(sourceState.applyPlay(playingGeneration))
        val sourceVoice = SamplerEngine.Voice(
            SamplerEngine.PadSnapshot(
                padIndex = -2,
                audio = audio,
                startFrame = 0,
                endFrame = audio.frameCount,
                pitchSemitones = 0f,
                tone = 1f,
                gain = 1f,
                reverse = false,
                playMode = PadPlayMode.ONE_SHOT,
                chokeGroup = 0,
            ),
            outputSampleRate = 48_000,
        )
        val loopStartGeneration = sourceState.issueStop()

        val stopped = retireSourceVoiceForLoopStart(
            sourcePlaybackState = sourceState,
            sourceVoice = sourceVoice,
            stopGeneration = loopStartGeneration,
        )

        assertTrue(stopped)
        assertFalse(sourceVoice.active)
        assertFalse(sourceState.isPlaying)
    }

    @Test
    fun runningLoopAppliesLivePitchToneAndLevelWithoutRestartingItsCursor() {
        val audio = PcmAudio(
            name = "loop.wav",
            samples = ShortArray(128) { 8_000 },
            sampleRate = 48_000,
        )
        val initialPad = PadModel(
            globalIndex = 0,
            audio = audio,
            startFrame = 0,
            endFrame = audio.frameCount,
            pitchSemitones = 0f,
            tone = 1f,
            gain = 1f,
            playMode = PadPlayMode.LOOP,
        )
        val voice = SamplerEngine.Voice(
            SamplerEngine.PadSnapshot.from(initialPad),
            outputSampleRate = 48_000,
        )

        repeat(10) { voice.render(outputSampleRate = 48_000) }
        val frameBeforeEdit = voice.currentFrame

        voice.updateLiveParameters(
            SamplerEngine.PadSnapshot.from(
                initialPad.copy(
                    pitchSemitones = 12f,
                    tone = 0.25f,
                    gain = 0.5f,
                ),
            ),
            outputSampleRate = 48_000,
        )

        assertEquals(frameBeforeEdit, voice.currentFrame)
        assertEquals(2.0, voice.liveSourceStep, 0.0001)
        assertEquals(0.25f, voice.liveTone, 0.0001f)
        assertEquals(0.5f, voice.liveGain, 0.0001f)

        voice.render(outputSampleRate = 48_000)
        assertEquals(frameBeforeEdit + 2, voice.currentFrame)
    }

    @Test
    fun pooledVoiceCanBeDeactivatedAndStartedWithDifferentAudio() {
        val firstAudio = PcmAudio(
            name = "first.wav",
            samples = ShortArray(32) { 4_000 },
            sampleRate = 48_000,
        )
        val secondAudio = PcmAudio(
            name = "second.wav",
            samples = ShortArray(48) { 8_000 },
            sampleRate = 48_000,
        )
        val voice = SamplerEngine.Voice()

        assertFalse(voice.active)
        voice.start(
            SamplerEngine.PadSnapshot.from(
                PadModel(globalIndex = 0, audio = firstAudio, startFrame = 0, endFrame = 32),
            ),
            outputSampleRate = 48_000,
        )
        assertTrue(voice.active)
        voice.render(outputSampleRate = 48_000)

        voice.deactivate()
        voice.start(
            SamplerEngine.PadSnapshot.from(
                PadModel(
                    globalIndex = 17,
                    audio = secondAudio,
                    startFrame = 8,
                    endFrame = 40,
                    reverse = true,
                ),
            ),
            outputSampleRate = 48_000,
        )

        assertTrue(voice.active)
        assertEquals(17, voice.padIndex)
        assertEquals(39, voice.currentFrame)
    }

    @Test
    fun pooledVoiceResetsFilterStateAndBypassTracksTheCurrentSample() {
        val audio = PcmAudio(
            name = "filter-step.wav",
            samples = ShortArray(256) { frame -> if (frame < 96) 8_000 else 24_000 },
            sampleRate = 48_000,
        )
        val darkPad = PadModel(
            globalIndex = 0,
            audio = audio,
            startFrame = 0,
            endFrame = audio.frameCount,
            tone = 0.2f,
            playMode = PadPlayMode.LOOP,
        )
        val brightPad = darkPad.copy(tone = 1f)
        val voice = SamplerEngine.Voice(SamplerEngine.PadSnapshot.from(darkPad), 48_000)

        val firstPass = FloatArray(80) { voice.render(48_000) }
        voice.start(SamplerEngine.PadSnapshot.from(darkPad), 48_000)
        val secondPass = FloatArray(80) { voice.render(48_000) }
        assertTrue(firstPass.contentEquals(secondPass))

        repeat(120) { voice.render(48_000) }
        voice.updateLiveParameters(SamplerEngine.PadSnapshot.from(brightPad), 48_000)
        val bypassed = voice.render(48_000)
        voice.updateLiveParameters(SamplerEngine.PadSnapshot.from(darkPad), 48_000)
        val resumedFilter = voice.render(48_000)
        assertTrue(kotlin.math.abs(resumedFilter - bypassed) < 0.05f)
    }

    @Test
    fun realtimeVoiceMatchesTheSharedHostPadOracle() {
        val audio = PcmAudio(
            name = "parity.wav",
            samples = ShortArray(512) { frame -> (frame * 53 - 12_000).toShort() },
            sampleRate = 48_000,
        )
        val pad = PadModel(
            globalIndex = 0,
            audio = audio,
            startFrame = 24,
            endFrame = 480,
            pitchSemitones = 3f,
            tone = 0.4f,
            gain = 0.7f,
        )
        val expected = PadPcmRenderer.render(pad, outputSampleRate = 48_000)
        val voice = SamplerEngine.Voice(SamplerEngine.PadSnapshot.from(pad), 48_000)
        val actual = ShortArray(expected.size) {
            (voice.render(48_000).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }

        val maximumDelta = expected.indices.maxOf { index ->
            kotlin.math.abs(expected[index].toInt() - actual[index].toInt())
        }
        assertTrue("Realtime/host PAD delta was $maximumDelta", maximumDelta <= 1)
    }

    @Test
    fun runtimeMixesFinalReturnedPadSampleBeforeRetirement() {
        val sampleRate = 8_000
        val audio = PcmAudio(
            name = "terminal-sample.wav",
            samples = ShortArray(512) { frame ->
                when {
                    frame % 5 == 0 -> 12_000
                    frame % 3 == 0 -> -9_000
                    else -> (frame * 29 - 6_000).coerceIn(-12_000, 12_000).toShort()
                }
            },
            sampleRate = sampleRate,
        )
        val pad = PadModel(
            globalIndex = 0,
            audio = audio,
            startFrame = 16,
            endFrame = 496,
            pitchSemitones = 3f,
            tone = 0.35f,
            gain = 0.7f,
            reverse = true,
        )
        val voice = SamplerEngine.Voice(SamplerEngine.PadSnapshot.from(pad), sampleRate)
        var renderedFrames = 0
        var terminalMix = 0f

        while (voice.active) {
            terminalMix = mixVoiceSampleAndRetire(
                voice = voice,
                outputSampleRate = sampleRate,
                monoMix = 0f,
            )
            renderedFrames++
        }

        val terminalPcm = (
            SamplerDspPrimitives.softLimit(terminalMix).coerceIn(-1f, 1f) * Short.MAX_VALUE
        ).toInt()
        assertEquals(403, renderedFrames)
        assertEquals(-61, terminalPcm)
        assertFalse(voice.active)
        assertTrue(voice.finished)
    }

    @Test
    fun realtimeSnapshotNeutralizesNonFinitePadControls() {
        val audio = PcmAudio(
            name = "non-finite.wav",
            samples = ShortArray(128) { 8_000 },
            sampleRate = 48_000,
        )
        val snapshot = SamplerEngine.PadSnapshot.from(
            PadModel(
                globalIndex = 0,
                audio = audio,
                startFrame = 0,
                endFrame = audio.frameCount,
                pitchSemitones = Float.NaN,
                tone = Float.NaN,
                gain = Float.NaN,
            ),
        )

        assertEquals(0f, snapshot.pitchSemitones, 0f)
        assertEquals(1f, snapshot.tone, 0f)
        assertEquals(0f, snapshot.gain, 0f)
        val voice = SamplerEngine.Voice(snapshot, 48_000)
        repeat(64) { assertTrue(voice.render(48_000).isFinite()) }
    }
}
