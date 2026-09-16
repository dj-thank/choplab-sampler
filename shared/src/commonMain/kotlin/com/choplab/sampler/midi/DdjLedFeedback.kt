package com.choplab.sampler.midi

/** PAD lamps indicate assignment in the current bank, NOT measured sounding voices. */
data class DdjLedSnapshot(
    val assignedPads: Int = 0,
    val sourcePlaying: Boolean = false,
    val beatPlaying: Boolean = false,
    val recordArmed: Boolean = false,
    val cueLeft: Boolean = false,
    val cueRight: Boolean = false,
    val cueMaster: Boolean = false,
)

/** Run only on one MIDI-output worker. Feed latest full snapshots, never conflate deltas. */
class DdjLedFeedback {
    private val previous = IntArray(16 * 128) { -1 }
    fun reset() { previous.fill(-1) }

    fun messages(state: DdjLedSnapshot): ByteArray {
        val bytes = ByteArray(48 * 3)
        var size = 0
        fun note(channel: Int, number: Int, enabled: Boolean) {
            val value = if (enabled) 127 else 0
            val key = channel * 128 + number
            if (previous[key] == value) return
            previous[key] = value
            bytes[size++] = (0x90 + channel).toByte()
            bytes[size++] = number.toByte(); bytes[size++] = value.toByte()
        }
        for (pad in 0 until 32) {
            val deck = ddjPadBus(pad)
            val channel = 7 + deck * 2 + if (pad >= 16) 1 else 0
            note(channel, pad % 8, (state.assignedPads ushr pad) and 1 != 0)
        }
        for (deck in 0..1) {
            val playing = if (deck == 0) state.sourcePlaying else state.beatPlaying
            note(deck, 0x0b, playing)
            // Shift+PLAY is ALL STOP; no misleading transport-running lamp there.
            note(deck, 0x47, false)
            note(deck, 0x0c, !state.sourcePlaying && !state.beatPlaying)
            note(deck, 0x48, state.recordArmed)
            note(deck, 0x54, if (deck == 0) state.cueLeft else state.cueRight)
            note(deck, 0x68, if (deck == 0) state.cueLeft else state.cueRight)
        }
        note(6, 0x63, state.cueMaster); note(6, 0x78, state.cueMaster)
        return bytes.copyOf(size)
    }
}
