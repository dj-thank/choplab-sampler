package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectModelsTest {
    @Test
    fun stereoBufferDefensivelyCopiesSamplesAndPreservesChannelOrder() {
        val source = shortArrayOf(100, -100, 200, -200)
        val buffer = PcmBuffer.fromInterleaved(source, sampleRate = 48_000, channelCount = 2)

        source[0] = 9_999
        val exported = buffer.copyInterleaved()
        exported[1] = 9_999

        assertEquals(2, buffer.frameCount)
        assertEquals(100.toShort(), buffer.sampleAt(frame = 0, channel = 0))
        assertEquals((-100).toShort(), buffer.sampleAt(frame = 0, channel = 1))
        assertNotEquals(9_999.toShort(), buffer.sampleAt(frame = 0, channel = 1))
    }

    @Test
    fun stereoBufferRejectsPartialFrames() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmBuffer.fromInterleaved(shortArrayOf(1, 2, 3), sampleRate = 48_000, channelCount = 2)
        }
    }

    @Test
    fun legacySnapshotPreservesPadRangesAndPatternEvents() {
        val audio = PcmAudio(name = "legacy", samples = ShortArray(1_000), sampleRate = 48_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 3) {
                PadModel(index, audio = audio, startFrame = 100, endFrame = 300, gain = 1.1f)
            } else {
                PadModel(index)
            }
        }
        val state = SamplerUiState(
            currentAudio = audio,
            pads = pads,
            activeSteps = setOf(stepKey(3, 7)),
            bpm = 110f,
            swing = 57f,
        )

        val snapshot = LegacyProjectAdapter.toSnapshot(state, projectName = "Legacy")

        assertEquals(ProjectSchema.CURRENT_VERSION, snapshot.schemaVersion)
        assertEquals(1, snapshot.audioAssets.size)
        assertEquals(1, snapshot.audioAssets.single().channelCount)
        assertEquals(100L, snapshot.pads[3].startFrame)
        assertEquals(300L, snapshot.pads[3].endFrame)
        assertEquals(1.1f, snapshot.pads[3].gain)
        assertEquals(2, snapshot.patterns.size)
        assertEquals(listOf(PatternStep(padIndex = 3, stepIndex = 7)), snapshot.patterns[0].events)
        assertEquals(emptyList<PatternStep>(), snapshot.patterns[1].events)
        assertEquals(emptyList<SongSection>(), snapshot.songSections)
    }

    @Test
    fun legacySnapshotPreservesBothVariationsAndEnabledSongOrder() {
        val patternA = setOf(stepKey(2, 0), stepKey(2, 8))
        val patternB = setOf(stepKey(3, 4), stepKey(3, 12))
        val state = SamplerUiState(
            activeSteps = patternB,
            patternArrangement = PatternArrangement(
                storedStepsBySlot = listOf(patternA, emptySet()),
                selectedSlot = 1,
                songSections = listOf(0, 1, 1, 0),
                songModeEnabled = true,
            ),
        )

        val snapshot = LegacyProjectAdapter.toSnapshot(state, projectName = "A/B Song")

        assertEquals(listOf("pattern-a", "pattern-b"), snapshot.patterns.map(ProjectPattern::id))
        assertEquals(
            listOf(
                PatternStep(padIndex = 2, stepIndex = 0),
                PatternStep(padIndex = 2, stepIndex = 8),
            ),
            snapshot.patterns[0].events,
        )
        assertEquals(
            listOf(
                PatternStep(padIndex = 3, stepIndex = 4),
                PatternStep(padIndex = 3, stepIndex = 12),
            ),
            snapshot.patterns[1].events,
        )
        assertEquals(
            listOf("pattern-a", "pattern-b", "pattern-b", "pattern-a"),
            snapshot.songSections.map(SongSection::patternId),
        )
    }

    @Test
    fun projectRejectsPadRangeBeyondReferencedAsset() {
        val asset = AudioAssetMetadata(
            id = AudioAssetId("asset-1"),
            name = "asset",
            sampleRate = 48_000,
            channelCount = 2,
            frameCount = 2,
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProjectSnapshot(
                projectId = "invalid",
                name = "Invalid",
                audioAssets = listOf(asset),
                pads = List(SamplerConfig.PAD_COUNT) { index ->
                    if (index == 0) ProjectPad(index, asset.id, 0, 3) else ProjectPad(index)
                },
            )
        }
    }

    @Test
    fun projectRejectsOversizedMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectSnapshot(
                projectId = "p".repeat(ProjectLimits.MAX_IDENTIFIER_CHARS + 1),
                name = "Invalid",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioAssetMetadata(
                id = AudioAssetId("asset-1"),
                name = "a".repeat(ProjectLimits.MAX_ASSET_NAME_CHARS + 1),
                sampleRate = 48_000,
                channelCount = 2,
                frameCount = 2,
            )
        }
    }
}
