package com.choplab.sampler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPolicyTest {
    @Test
    fun permissionDenialNamesTheOperationThatWasRequested() {
        assertEquals(
            "マイク素材を録音するにはマイク権限が必要です",
            recordPermissionDeniedMessage(PendingPermissionAction.MICROPHONE),
        )
        assertEquals(
            "ビートに声を重ねるにはマイク権限が必要です",
            recordPermissionDeniedMessage(PendingPermissionAction.VOCAL),
        )
        assertEquals(
            "端末音声を録音するにはマイク権限が必要です",
            recordPermissionDeniedMessage(PendingPermissionAction.SYSTEM_AUDIO),
        )
    }

    @Test
    fun activityStopInterruptsPlaybackExceptDuringConfigurationChange() {
        assertTrue(shouldInterruptPlaybackOnActivityStop(isChangingConfigurations = false))
        assertFalse(shouldInterruptPlaybackOnActivityStop(isChangingConfigurations = true))
    }
}
