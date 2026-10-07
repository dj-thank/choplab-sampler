package com.choplab.desktop.audio

import kotlin.test.*

class MacPermissionClassificationTest {
    @Test fun microphonePermissionRequiresAnExplicitNativeResult() {
        assertEquals(MacMicrophonePermission.Status.DENIED, MacMicrophonePermission.parse("CHOPLAB-MIC DENIED"))
        assertEquals(MacMicrophonePermission.Status.RESTRICTED, MacMicrophonePermission.parse("CHOPLAB-MIC RESTRICTED"))
        assertEquals(MacMicrophonePermission.Status.AUTHORIZED, MacMicrophonePermission.parse("CHOPLAB-MIC AUTHORIZED"))
        assertEquals(MacMicrophonePermission.Status.UNKNOWN, MacMicrophonePermission.parse("Device disconnected"))
    }
    @Test fun unavailableSystemCaptureDoesNotAskForPermissions() {
        val unknown = assertFails { parseSystemAudioHeader("CHOPLAB-ERROR UNAVAILABLE Stream failed") }
        assertFalse(unknown.message.orEmpty().contains("許可"))
        val denied = assertFails { parseSystemAudioHeader("CHOPLAB-ERROR DENIED User declined") }
        assertTrue(denied.message.orEmpty().contains("許可"))
    }
}
