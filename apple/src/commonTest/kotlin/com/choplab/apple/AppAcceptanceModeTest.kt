package com.choplab.apple

import kotlin.test.*

class AppAcceptanceModeTest {
    private val profile = "0123456789abcdef0123456789abcdef"
    private val token = listOf("01234567", "89ab", "cdef", "0123", "456789abcdef").joinToString("-")
    @Test fun ordinaryLaunchAndReadinessProbeDoNotSelectFixtureData() {
        assertNull(AppAcceptanceMode.parse(null, null, null))
        assertNull(AppAcceptanceMode.parse(null, null, token))
    }
    @Test fun restartKeepsTheSameIsolatedProfile() {
        assertEquals(profile, AppAcceptanceMode.parse(profile, "first", token)?.profile)
        assertEquals(profile, AppAcceptanceMode.parse(profile, "relaunch", token)?.profile)
    }
    @Test fun traversalMissingTokenAndUnknownPhaseFailClosed() {
        for (path in listOf("../next-v10", "/next-v10", "", profile + "/x"))
            assertFailsWith<IllegalArgumentException> { AppAcceptanceMode.parse(path, "first", token) }
        assertFailsWith<IllegalArgumentException> { AppAcceptanceMode.parse(profile, "first", null) }
        assertFailsWith<IllegalArgumentException> { AppAcceptanceMode.parse(profile, "erase", token) }
        assertFailsWith<IllegalArgumentException> { AppAcceptanceMode.parse(profile, "first", "bad") }
    }
}
