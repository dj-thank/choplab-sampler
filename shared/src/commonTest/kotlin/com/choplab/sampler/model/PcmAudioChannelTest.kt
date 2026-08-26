package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PcmAudioChannelTest {
    @Test
    fun stereoUsesInterleavedSamplesButFrameBasedDurationAndAccess() {
        val audio = PcmAudio(
            name = "asymmetric-stereo.wav",
            samples = shortArrayOf(1_000, -3_000, 2_000, -4_000, 3_000, -5_000),
            sampleRate = 48_000,
            channelCount = 2,
        )

        assertEquals(3, audio.frameCount)
        assertEquals(3f / 48_000f, audio.durationSeconds)
        assertEquals(2_000.toShort(), audio.sampleAt(frame = 1, channel = 0))
        assertEquals((-4_000).toShort(), audio.sampleAt(frame = 1, channel = 1))
        assertEquals((-1_000).toShort(), audio.monoSampleAt(frame = 1))
    }

    @Test
    fun monoPlaybackProjectionDuplicatesOneStoredChannel() {
        val audio = PcmAudio(
            name = "mono.wav",
            samples = shortArrayOf(1_000, -2_000),
            sampleRate = 48_000,
        )

        assertEquals(1, audio.channelCount)
        assertEquals(1_000.toShort(), audio.playbackSampleAt(frame = 0, outputChannel = 0))
        assertEquals(1_000.toShort(), audio.playbackSampleAt(frame = 0, outputChannel = 1))
    }

    @Test
    fun rejectsUnsupportedOrPartialStoredFrames() {
        assertFailsWith<IllegalArgumentException> {
            PcmAudio(
                name = "partial.wav",
                samples = shortArrayOf(1, 2, 3),
                sampleRate = 48_000,
                channelCount = 2,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PcmAudio(
                name = "surround.wav",
                samples = shortArrayOf(1, 2, 3),
                sampleRate = 48_000,
                channelCount = 3,
            )
        }
    }

    @Test
    fun legacyProjectSnapshotCarriesTheActualStoredChannelCount() {
        val stereo = PcmAudio(
            id = 19L,
            name = "snapshot-stereo.wav",
            samples = shortArrayOf(1, -1, 2, -2),
            sampleRate = 48_000,
            channelCount = 2,
        )

        val snapshot = LegacyProjectAdapter.toSnapshot(
            SamplerUiState(currentAudio = stereo, rangeEndFrame = stereo.frameCount),
        )

        assertEquals(2, snapshot.audioAssets.single().channelCount)
        assertEquals(2L, snapshot.audioAssets.single().frameCount)
    }
}
