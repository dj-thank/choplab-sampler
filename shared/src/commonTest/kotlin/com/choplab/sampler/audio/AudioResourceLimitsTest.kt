package com.choplab.sampler.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioResourceLimitsTest {
    @Test
    fun maximumRecordingBytesMatchTenMinutesOfPcm16() {
        assertEquals(
            57_600_000L,
            AudioResourceLimits.maxRecordingPcmBytes(sampleRate = 48_000, channelCount = 1),
        )
        assertEquals(
            115_200_000L,
            AudioResourceLimits.maxRecordingPcmBytes(sampleRate = 48_000, channelCount = 2),
        )
    }

    @Test
    fun importFileSizeRejectsOnlyKnownOversizedInputs() {
        AudioResourceLimits.requireImportFileSize(null)
        AudioResourceLimits.requireImportFileSize(-1L)
        AudioResourceLimits.requireImportFileSize(AudioResourceLimits.MAX_IMPORT_FILE_BYTES)

        assertFailsWith<IllegalArgumentException> {
            AudioResourceLimits.requireImportFileSize(AudioResourceLimits.MAX_IMPORT_FILE_BYTES + 1L)
        }
    }

    @Test
    fun durationLimitReturnsFrameAlignedFinalWrite() {
        val budget = RecordingBudget(
            sampleRate = 48_000,
            channelCount = 2,
            minimumFreeDiskReserveBytes = 0,
            maximumPcmBytes = 10,
        )

        val first = budget.decide(requestedBytes = 8, usableSpaceBytes = 1_000)
        assertEquals(8, first.writableBytes)
        assertEquals(RecordingStopReason.DURATION_LIMIT, first.stopAfterWrite)
        budget.commit(first.writableBytes)

        assertTrue(budget.exhausted)
        assertEquals(
            RecordingWriteDecision(0, RecordingStopReason.DURATION_LIMIT),
            budget.decide(requestedBytes = 8, usableSpaceBytes = 1_000),
        )
    }

    @Test
    fun lowDiskStopsBeforeReserveIsConsumed() {
        val budget = RecordingBudget(
            sampleRate = 48_000,
            channelCount = 1,
            minimumFreeDiskReserveBytes = 100,
            maximumPcmBytes = 1_000,
        )

        assertEquals(
            RecordingWriteDecision(0, RecordingStopReason.LOW_DISK),
            budget.decide(requestedBytes = 64, usableSpaceBytes = 100),
        )
        assertFalse(budget.exhausted)
    }

    @Test
    fun partialDiskWriteIsAlignedAndMarkedForStop() {
        val budget = RecordingBudget(
            sampleRate = 48_000,
            channelCount = 2,
            minimumFreeDiskReserveBytes = 100,
            maximumPcmBytes = 1_000,
        )

        val decision = budget.decide(requestedBytes = 64, usableSpaceBytes = 130)

        assertEquals(28, decision.writableBytes)
        assertEquals(RecordingStopReason.LOW_DISK, decision.stopAfterWrite)
        budget.commit(decision.writableBytes)
        assertEquals(28L, budget.pcmBytesWritten)
    }
}
