package com.choplab.sampler.ui

import com.choplab.sampler.model.PcmAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutomaticLoopEditorTest {
    @Test fun pendingStopBlocksPlaybackStartsButNotOrdinaryDocumentEditing() {
        val stopping = com.choplab.sampler.model.SamplerUiState(sourcePlaying = true,
            pendingSourceCommand = com.choplab.sampler.model.PendingSourceCommand.STOP)
        assertTrue(com.choplab.sampler.model.playbackStartBlockedReason(stopping) != null)
        assertEquals(null, com.choplab.sampler.model.projectEditBlockedReason(stopping))
    }

    @Test fun rechopSessionConnectsNewCutsWithoutRewritingEarlierTrimmedPads() {
        val audio = PcmAudio(name = "cuts", samples = ShortArray(1000), sampleRate = 8000)
        val original = com.choplab.sampler.model.SamplerUiState(currentAudio = audio, rangeEndFrame = 1000,
            liveChopPadIndices = emptySet(), pads = List(com.choplab.sampler.model.SamplerConfig.PAD_COUNT) { index ->
                when (index) {
                    0 -> com.choplab.sampler.model.PadModel(0, audio, 10, 90)
                    1 -> com.choplab.sampler.model.PadModel(1, audio, 100, 190)
                    else -> com.choplab.sampler.model.PadModel(index)
                }
            })
        val first = com.choplab.sampler.model.assignLiveChopToPad(original, 2, 400).state
        val second = com.choplab.sampler.model.assignLiveChopToPad(first, 3, 600).state
        assertEquals(90, second.pads[0].endFrame)
        assertEquals(190, second.pads[1].endFrame)
        assertEquals(600, second.pads[2].endFrame)
        assertEquals(setOf(2, 3), second.liveChopPadIndices)
        val restarted = com.choplab.sampler.model.stopAllPlaybackState(second)
        val next = com.choplab.sampler.model.assignLiveChopToPad(restarted, 4, 800).state
        assertEquals(600, next.pads[2].endFrame)
        assertEquals(1000, next.pads[3].endFrame)
    }

    @Test fun rollingIsIndependentOfEventChunkingAndIgnoresInvalidInput() {
        val whole = LoopDialMotion(48000).consume(120f)
        val split = LoopDialMotion(48000)
        val sum = (1..120).sumOf { split.consume(1f) }
        assertEquals(whole, sum)
        assertEquals(0, split.consume(Float.NaN))
        assertEquals(0, split.consume(Float.POSITIVE_INFINITY))
        assertEquals(-whole, split.consume(-120f))
    }

    @Test fun envelopeIncludesOnlyTheSelectedFramesAndPreservesStereoExtrema() {
        val audio = PcmAudio(name = "wave", samples = ShortArray(200) { sample ->
            val frame = sample / 2
            if (frame !in 20 until 40) 30000 else if (sample % 2 == 0) 8000 else -8000
        }, sampleRate = 8000, channelCount = 2)
        val envelope = automaticRangeEnvelope(audio, 20, 40, 10)
        assertTrue(envelope.maximums.all { it == 8000 / 32768f })
        assertTrue(envelope.minimums.all { it == -8000 / 32768f })
    }
}
