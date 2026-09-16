package com.choplab.sampler.midi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DdjLedFeedbackTest {
    private fun notes(bytes: ByteArray): Map<Pair<Int, Int>, Int> = bytes.toList().chunked(3)
        .associate { (it[0].toInt() and 15 to (it[1].toInt() and 127)) to (it[2].toInt() and 127) }
    @Test fun initialSnapshotHasBoundedCompleteLegalMessages() {
        val bytes = DdjLedFeedback().messages(DdjLedSnapshot())
        assertEquals(46 * 3, bytes.size)
        for (i in bytes.indices step 3) {
            assertTrue((bytes[i].toInt() and 255) in 0x90..0x9f)
            assertTrue((bytes[i + 1].toInt() and 255) in 0..127)
            assertTrue((bytes[i + 2].toInt() and 255) in listOf(0, 127))
        }
    }
    @Test fun all32PadLampsUseOfficialNormalAndShiftChannels() {
        for (pad in 0..31) {
            val n = notes(DdjLedFeedback().messages(DdjLedSnapshot(assignedPads = 1 shl pad)))
            val channel = 7 + ddjPadBus(pad) * 2 + if (pad >= 16) 1 else 0
            assertEquals(127, n[channel to pad % 8])
            assertEquals(1, n.count { it.key.first in 7..10 && it.value == 127 })
        }
    }
    @Test fun unchangedSnapshotDoesNotSendAndBankChangeSendsOff() {
        val feedback = DdjLedFeedback()
        val assigned = DdjLedSnapshot(assignedPads = -1)
        feedback.messages(assigned)
        assertTrue(feedback.messages(assigned).isEmpty())
        val off = notes(feedback.messages(DdjLedSnapshot()))
        assertEquals(32, off.size); assertTrue(off.values.all { it == 0 })
    }
    @Test fun fullSnapshotAfterSkippedUpdatesPreservesAllFinalChanges() {
        val f = DdjLedFeedback()
        f.messages(DdjLedSnapshot())
        // Intermediate state is intentionally skipped by a conflated channel.
        val last = DdjLedSnapshot(assignedPads = 1 shl 31, sourcePlaying = true, cueRight = true)
        val n = notes(f.messages(last))
        assertEquals(127, n[10 to 7]); assertEquals(127, n[0 to 0x0b])
        assertEquals(127, n[1 to 0x54]); assertEquals(127, n[1 to 0x68])
    }
    @Test fun transportRecordAndHeadphoneStatesUseDocumentedNotes() {
        val n = notes(DdjLedFeedback().messages(DdjLedSnapshot(sourcePlaying = true, beatPlaying = false,
            recordArmed = true, cueLeft = true, cueMaster = true)))
        assertEquals(127, n[0 to 0x0b]); assertEquals(0, n[1 to 0x0b])
        assertEquals(127, n[0 to 0x48]); assertEquals(127, n[1 to 0x48])
        assertEquals(127, n[0 to 0x54]); assertEquals(0, n[1 to 0x54])
        assertEquals(127, n[6 to 0x63]); assertEquals(127, n[6 to 0x78])
    }
    @Test fun reconnectResetsCacheAndSendsFullState() {
        val f = DdjLedFeedback(); val s = DdjLedSnapshot(assignedPads = 7, beatPlaying = true)
        val first = f.messages(s).toList(); assertTrue(f.messages(s).isEmpty())
        f.reset(); assertEquals(first, f.messages(s).toList())
    }
}
