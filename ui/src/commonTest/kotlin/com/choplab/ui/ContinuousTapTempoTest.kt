package com.choplab.ui

import kotlin.test.*

/** Tapping along with a song fills in its tempo. */
class ContinuousTapTempoTest {
    private fun tapped(vararg millis: Long) = millis.fold(emptyList<Long>(), ::ceTap)

    @Test fun tapsHalfASecondApartAre120Bpm() {
        assertNull(ceTapBpm(tapped(0)), "One tap has no tempo yet")
        assertEquals(120, ceTapBpm(tapped(0, 500)))
        assertEquals(120, ceTapBpm(tapped(0, 500, 1_000, 1_500)))
        // The average of the intervals, rounded: 652 ms is 92 BPM.
        assertEquals(92, ceTapBpm(tapped(0, 652, 1_304, 1_956)))
        // Uneven taps average out: 400 and 600 ms apart are 500 ms, not the last or first interval alone.
        assertEquals(120, ceTapBpm(tapped(0, 400, 1_000)))
    }

    @Test fun aPauseOfTwoSecondsStartsOverAndOnlyTheLastEightTapsCount() {
        val restarted = tapped(0, 500, 1_000, 3_000)
        assertEquals(listOf(3_000L), restarted)
        assertNull(ceTapBpm(restarted))
        assertEquals(2, tapped(0, 1_999).size, "Just under two seconds still counts")
        // Slowing down: the old fast taps drop out, the last eight set the tempo.
        val slowing = tapped(0, 250, 500, 750, 1_750, 2_750, 3_750, 4_750, 5_750, 6_750, 7_750)
        assertEquals(8, slowing.size)
        assertEquals(60, ceTapBpm(slowing))
    }

    @Test fun theTempoStaysWithinWhatTheSongTakes() {
        assertEquals(240, ceTapBpm(tapped(0, 100)))
        assertEquals(240, ceTapBpm(listOf(5L, 5L)))
        assertEquals(40, ceTapBpm(tapped(0, 1_900)))
    }
}
