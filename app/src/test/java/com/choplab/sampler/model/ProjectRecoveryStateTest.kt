package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRecoveryStateTest {
    @Test
    fun recoveryPublishesLoadingTruthWithoutDiscardingCurrentMaterial() {
        val audio = PcmAudio(1L, "source.wav", ShortArray(32), 48_000)
        val state = SamplerUiState(currentAudio = audio, statusMessage = "READY")

        val recovering = beginAutosaveRecovery(state)

        assertTrue(recovering.isLoading)
        assertEquals("前回の自動保存を復元しています…", recovering.statusMessage)
        assertSame(audio, recovering.currentAudio)
    }

    @Test
    fun emptyAndFailedRecoveryAlwaysLeaveLoadingState() {
        val recovering = beginAutosaveRecovery()

        val empty = completeAutosaveRecoveryWithoutProject(recovering)
        assertFalse(empty.isLoading)
        assertEquals("音声を読み込むか録音してください", empty.statusMessage)

        val failed = failAutosaveRecovery(recovering, "archive broken")
        assertFalse(failed.isLoading)
        assertEquals("archive broken", failed.statusMessage)

        val failedWithoutMessage = failAutosaveRecovery(recovering, null)
        assertEquals("前回の自動保存を復元できませんでした", failedWithoutMessage.statusMessage)
    }
}
