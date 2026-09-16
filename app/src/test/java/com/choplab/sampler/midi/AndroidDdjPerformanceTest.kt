package com.choplab.sampler.midi

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class AndroidDdjPerformanceTest {
    @Test fun newConnectionStartsNeutralAndDisconnectResets() {
        val token = Any(); AndroidDdjPerformance.acquire(token) {}
        try {
            assertEquals(DdjMixerSettings(enabled = true), AndroidDdjPerformance.snapshot)
            AndroidDdjPerformance.publish(token, DdjMixerSettings(enabled = true, crossfader = 0f))
            assertEquals(0f, AndroidDdjPerformance.snapshot.crossfader, 0f)
        } finally { AndroidDdjPerformance.release(token) }
        assertEquals(DdjMixerSettings(), AndroidDdjPerformance.snapshot)
    }
    @Test fun oldConnectionCannotClobberNewOwner() {
        val first = Any(); val next = Any()
        AndroidDdjPerformance.acquire(first) {}; AndroidDdjPerformance.acquire(next) {}
        try {
            AndroidDdjPerformance.publish(next, DdjMixerSettings(enabled = true, crossfader = 1f))
            AndroidDdjPerformance.publish(first, DdjMixerSettings(enabled = true, crossfader = 0f))
            AndroidDdjPerformance.release(first)
            assertEquals(1f, AndroidDdjPerformance.snapshot.crossfader, 0f)
        } finally { AndroidDdjPerformance.release(next) }
    }
    @Test fun splitCannotEnableOnUnknownOrWirelessRoute() {
        val token = Any(); val track = Any()
        AndroidDdjPerformance.bindRoute(track); AndroidDdjPerformance.acquire(token) {}
        try {
            AndroidDdjPerformance.publish(token, DdjMixerSettings(enabled = true, splitCue = true))
            assertFalse(AndroidDdjPerformance.snapshot.splitCue)
            AndroidDdjPerformance.updateRoute(track, DdjAudioRoute(5, false))
            AndroidDdjPerformance.publish(token, DdjMixerSettings(enabled = true, splitCue = true))
            assertFalse(AndroidDdjPerformance.snapshot.splitCue)
        } finally { AndroidDdjPerformance.release(token); AndroidDdjPerformance.unbindRoute(track) }
    }
    @Test fun unplugTurnsSplitOffBeforeInvokingStopCallback() {
        val token = Any(); val track = Any(); var stops = 0
        AndroidDdjPerformance.bindRoute(track)
        AndroidDdjPerformance.acquire(token) {
            assertFalse(AndroidDdjPerformance.snapshot.splitCue); stops++
        }
        try {
            AndroidDdjPerformance.updateRoute(track, DdjAudioRoute(4, true))
            AndroidDdjPerformance.publish(token, DdjMixerSettings(enabled = true, splitCue = true))
            assertTrue(AndroidDdjPerformance.snapshot.splitCue)
            AndroidDdjPerformance.updateRoute(track, DdjAudioRoute())
            assertFalse(AndroidDdjPerformance.snapshot.splitCue); assertEquals(1, stops)
            AndroidDdjPerformance.updateRoute(track, DdjAudioRoute())
            assertEquals(1, stops)
        } finally { AndroidDdjPerformance.release(token); AndroidDdjPerformance.unbindRoute(track) }
    }
    @Test fun changingBetweenTwoWiredRoutesStillRequiresReconfirmation() {
        val token = Any(); val track = Any(); var stops = 0
        AndroidDdjPerformance.bindRoute(track); AndroidDdjPerformance.acquire(token) { stops++ }
        try {
            AndroidDdjPerformance.updateRoute(track, DdjAudioRoute(4, true))
            AndroidDdjPerformance.publish(token, DdjMixerSettings(enabled = true, splitCue = true))
            AndroidDdjPerformance.updateRoute(track, DdjAudioRoute(5, true))
            assertFalse(AndroidDdjPerformance.snapshot.splitCue); assertEquals(1, stops)
        } finally { AndroidDdjPerformance.release(token); AndroidDdjPerformance.unbindRoute(track) }
    }
    @Test fun oldTrackCallbacksCannotInvalidateNewTrack() {
        val old = Any(); val current = Any(); val token = Any()
        AndroidDdjPerformance.bindRoute(old); AndroidDdjPerformance.bindRoute(current)
        AndroidDdjPerformance.acquire(token) { error("Old route must not revoke current split") }
        try {
            AndroidDdjPerformance.updateRoute(current, DdjAudioRoute(5, true))
            AndroidDdjPerformance.publish(token, DdjMixerSettings(enabled = true, splitCue = true))
            AndroidDdjPerformance.updateRoute(old, DdjAudioRoute())
            AndroidDdjPerformance.unbindRoute(old)
            assertTrue(AndroidDdjPerformance.snapshot.splitCue)
            assertEquals(DdjAudioRoute(5, true), AndroidDdjPerformance.route.value)
        } finally { AndroidDdjPerformance.release(token); AndroidDdjPerformance.unbindRoute(current) }
    }
    @Test fun repeatedSameRouteDoesNotStopAndOrdinaryStereoIsUnchanged() {
        val track = Any(); val token = Any(); var stops = 0
        AndroidDdjPerformance.bindRoute(track); AndroidDdjPerformance.acquire(token) { stops++ }
        try {
            AndroidDdjPerformance.updateRoute(track, DdjAudioRoute(7, true))
            AndroidDdjPerformance.publish(token, DdjMixerSettings(enabled = true, splitCue = true))
            AndroidDdjPerformance.updateRoute(track, DdjAudioRoute(7, true))
            assertEquals(0, stops)
            AndroidDdjPerformance.publish(token, DdjMixerSettings(enabled = true))
            AndroidDdjPerformance.unbindRoute(track)
            assertEquals(0, stops)
        } finally { AndroidDdjPerformance.release(token); AndroidDdjPerformance.unbindRoute(track) }
    }
    @Test fun trackShutdownRevokesSplitButNotAnotherClientOnLateCleanup() {
        val track = Any(); val token = Any(); var stops = 0
        AndroidDdjPerformance.bindRoute(track); AndroidDdjPerformance.acquire(token) { stops++ }
        try {
            AndroidDdjPerformance.updateRoute(track, DdjAudioRoute(7, true))
            AndroidDdjPerformance.publish(token, DdjMixerSettings(enabled = true, splitCue = true))
            AndroidDdjPerformance.unbindRoute(track)
            assertEquals(1, stops); assertFalse(AndroidDdjPerformance.snapshot.splitCue)
        } finally { AndroidDdjPerformance.release(token); AndroidDdjPerformance.unbindRoute(track) }
    }
}
