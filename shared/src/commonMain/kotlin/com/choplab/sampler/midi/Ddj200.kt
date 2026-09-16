package com.choplab.sampler.midi

import kotlin.math.abs

/** MIDI 1.0 byte stream, NOT USB event packets or raw BLE packets. Android removes framing. */
class MidiByteStream(private val emit: (Int, Int, Int) -> Unit) {
    private var status = 0
    private var first = -1
    private var sysex = false

    fun reset() { status = 0; first = -1; sysex = false }

    fun accept(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        for (index in offset until offset + count) {
            val value = bytes[index].toInt() and 0xff
            // Real-time bytes may occur inside ANY message, including SysEx.
            if (value >= 0xf8) continue
            if (value >= 0x80) {
                first = -1
                if (value >= 0xf0) {
                    status = 0 // System common cancels running status.
                    sysex = value == 0xf0
                } else {
                    sysex = false
                    status = value
                }
                continue
            }
            if (sysex || status == 0) continue
            val type = status and 0xf0
            if (type == 0xc0 || type == 0xd0) {
                emit(status, value, 0)
            } else if (first < 0) {
                first = value
            } else {
                emit(status, first, value)
                first = -1
            }
        }
    }
}

enum class DdjParameter { SOURCE_PITCH, BPM, PAD_GAIN, PAD_TONE }

/** Values are normalized to 0..1. Parameter writes stay in the existing controller/history seam. */
interface Ddj200Target {
    fun bank(): Int
    fun selectBank(bank: Int)
    fun triggerPad(index: Int): Long
    fun releasePad(index: Int, ownership: Long)
    fun toggleSource()
    fun toggleBeat()
    fun stopAll()
    fun toggleRecordArm()
    fun undo()
    fun redo()
    fun beginScratch(deck: Int): Boolean
    fun scratch(speed: Float)
    fun endScratch()
    fun parameter(kind: DdjParameter, pad: Int): Float
    fun setParameter(kind: DdjParameter, pad: Int, normalized: Float)
}

/** Prevent connection snapshots or a different PAD's fader position from jumping a value. */
class MidiPickup {
    private var previous: Float? = null
    private var accepted: Float? = null

    fun reset() { previous = null; accepted = null }

    fun accept(value: Float, target: Float): Boolean {
        if (!value.isFinite() || !target.isFinite()) return false
        val v = value.coerceIn(0f, 1f)
        val t = target.coerceIn(0f, 1f)
        // A UI edit (or another physical knob) revokes this knob's ownership.
        if (accepted?.let { abs(t - it) > 0.025f } == true) reset()
        val prior = previous
        val pickedUp = accepted != null || abs(v - t) <= 0.025f ||
            (prior != null && (prior - t) * (v - t) <= 0f)
        previous = v
        if (pickedUp) accepted = v
        return pickedUp
    }
}

/**
 * DDJ-200 E2 profile. All methods are serialized on the application's control/UI thread.
 * Source: AlphaTheta DDJ-200_MIDI_Message_List_E2.pdf, page 2 (2019).
 * Pads use channels 8/10, SHIFT pads 9/11 (one-based), NOT deck channels 1/2.
 * This is a sampler mapping, not a two-deck mixer or headphone audio interface.
 */
class Ddj200Session(
    private val target: Ddj200Target,
    private val channelFadersControlPads: Boolean = true,
) {
    private data class Held(val pad: Int, val token: Long)
    private val held = arrayOfNulls<Held>(16) // physical keys, independent of SHIFT and bank
    private val buttons = BooleanArray(6)
    private val lastPad = intArrayOf(-1, -1)
    private val msb = IntArray(16 * 32) { -1 }
    private val lsb = IntArray(16 * 32) { -1 }
    private val pickups = Array(6) { MidiPickup() }
    private var scratchDeck = -1
    val stream = MidiByteStream(::message)

    fun reset(stopPlayback: Boolean = false) {
        held.indices.forEach(::release)
        if (scratchDeck >= 0) target.endScratch()
        scratchDeck = -1
        buttons.fill(false)
        lastPad.fill(-1)
        msb.fill(-1); lsb.fill(-1)
        pickups.forEach { it.reset() }
        stream.reset()
        if (stopPlayback) target.stopAll()
    }

    private fun release(key: Int) {
        val press = held[key] ?: return
        held[key] = null
        target.releasePad(press.pad, press.token)
    }

    private fun message(status: Int, number: Int, value: Int) {
        val type = status and 0xf0
        val channel = status and 0x0f
        if (type == 0x80 || type == 0x90) {
            note(channel, number, type == 0x90 && value > 0)
        } else if (type == 0xb0) {
            control(channel, number, value)
        }
    }

    private fun note(channel: Int, number: Int, down: Boolean) {
        if (channel in 7..10 && number in 0..7) {
            val deck = if (channel < 9) 0 else 1
            val shifted = channel == 8 || channel == 10
            val key = deck * 8 + number
            if (!down) { release(key); return }
            if (held[key] != null) return // repeated press/SHIFT variant, same physical key
            val pad = target.bank().coerceIn(0, 3) * 32 + deck * 8 +
                (if (shifted) 16 else 0) + number
            if (lastPad[deck] != pad) {
                pickups[2 + deck].reset(); pickups[4 + deck].reset()
                lastPad[deck] = pad
            }
            held[key] = Held(pad, target.triggerPad(pad))
            return
        }
        if (channel !in 0..1) return
        if (number == 0x36 || number == 0x67) {
            if (down && scratchDeck < 0 && target.beginScratch(channel)) scratchDeck = channel
            if (!down && scratchDeck == channel) {
                scratchDeck = -1
                target.endScratch()
            }
            return
        }
        val button = when (number) {
            0x0b, 0x47 -> 0
            0x0c, 0x48 -> 1
            0x58, 0x60 -> 2
            else -> return // SHIFT itself, long SYNC and fader-start do not cause actions.
        }
        val key = channel * 3 + button
        if (!down) { buttons[key] = false; return }
        if (buttons[key]) return
        buttons[key] = true
        when (number) {
            0x0b -> if (channel == 0) target.toggleSource() else target.toggleBeat()
            0x0c, 0x47 -> panic()
            0x48 -> target.toggleRecordArm()
            0x58 -> {
                target.selectBank((target.bank() + if (channel == 0) 3 else 1) % 4)
                lastPad.fill(-1)
                pickups.forEach { it.reset() }
            }
            0x60 -> if (channel == 0) target.undo() else target.redo()
        }
    }

    private fun panic() {
        held.indices.forEach(::release)
        if (scratchDeck >= 0) target.endScratch()
        scratchDeck = -1
        // Keep the pressed button latched until its release; duplicate packets must not retrigger.
        target.stopAll()
    }

    private fun control(channel: Int, number: Int, value: Int) {
        if (channel in 0..1 && (number == 0x22 || number == 0x23 || number == 0x29)) {
            if (scratchDeck == channel) target.scratch(((value - 64) / 8f).coerceIn(-4f, 4f))
            return
        }
        val low = number >= 32
        val base = if (low) number - 32 else number
        val kind: DdjParameter
        val slot: Int
        val deck: Int
        when {
            channel in 0..1 && base == 0 -> {
                deck = channel; slot = channel
                kind = if (channel == 0) DdjParameter.SOURCE_PITCH else DdjParameter.BPM
            }
            channelFadersControlPads && channel in 0..1 && base == 0x13 -> {
                deck = channel; slot = 2 + channel; kind = DdjParameter.PAD_GAIN
            }
            channel == 6 && base in 0x17..0x18 -> {
                deck = base - 0x17; slot = 4 + deck; kind = DdjParameter.PAD_TONE
            }
            else -> return // EQ/crossfader intentionally unassigned; no fake mixer.
        }
        val key = channel * 32 + base
        if (low) lsb[key] = value else msb[key] = value
        if (msb[key] < 0 || lsb[key] < 0) return
        val normalized = ((msb[key] shl 7) or lsb[key]) / 16383f
        msb[key] = -1; lsb[key] = -1 // fresh pair required; partial messages never write a value.
        val pad = if (kind == DdjParameter.PAD_GAIN || kind == DdjParameter.PAD_TONE) lastPad[deck] else -1
        if (pad >= 0 && pad / 32 != target.bank()) return
        if (pad < 0 && slot >= 2) return // hit a PAD on that side before editing it.
        if (pickups[slot].accept(normalized, target.parameter(kind, pad))) {
            target.setParameter(kind, pad, normalized)
        }
    }
}

fun isDdj200Name(name: String?): Boolean = name != null &&
    Regex("(^|[^a-z0-9])ddj[-_ ]?200($|[^a-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(name)
