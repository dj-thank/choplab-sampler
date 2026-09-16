package com.choplab.sampler.midi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class Ddj200Test {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private class Target : Ddj200Target {
        var selectedBank = 0
        val calls = mutableListOf<String>()
        val held = mutableMapOf<Int, Long>()
        val values = mutableMapOf<Pair<DdjParameter, Int>, Float>()
        var generation = 0L
        var allowScratch = true
        override fun bank() = selectedBank
        override fun selectBank(bank: Int) { selectedBank = bank; calls += "bank:$bank" }
        override fun triggerPad(index: Int): Long {
            val token = ++generation; held[index] = token; calls += "pad:$index"; return token
        }
        override fun releasePad(index: Int, ownership: Long) {
            calls += "release:$index:$ownership"
            if (held[index] == ownership) held.remove(index)
        }
        override fun toggleSource() { calls += "source" }
        override fun toggleBeat() { calls += "beat" }
        override fun stopAll() { calls += "stop"; held.clear() }
        override fun toggleRecordArm() { calls += "arm" }
        override fun undo() { calls += "undo" }
        override fun redo() { calls += "redo" }
        override fun beginScratch(deck: Int): Boolean { calls += "begin:$deck"; return allowScratch }
        override fun scratch(speed: Float) { calls += "scratch:$speed" }
        override fun endScratch() { calls += "end" }
        override fun parameter(kind: DdjParameter, pad: Int) = values[kind to pad] ?: 0.5f
        override fun setParameter(kind: DdjParameter, pad: Int, normalized: Float) {
            values[kind to pad] = normalized; calls += "value:$kind:$pad"
        }
    }
    private fun send(session: Ddj200Session, vararg values: Int) { session.stream.accept(bytes(*values)) }
    private fun cc(session: Ddj200Session, channel: Int, control: Int, value: Int, reverse: Boolean = false) {
        val hi = bytes(0xb0 + channel, control, value shr 7)
        val lo = bytes(0xb0 + channel, control + 32, value and 127)
        session.stream.accept(if (reverse) lo + hi else hi + lo)
    }

    @Test fun streamEverySplitPreservesMessagesAndRunningStatus() {
        val data = bytes(0x97, 0, 127, 1, 0xf8, 127, 0, 0, 0x89, 3, 64)
        val expected = listOf(listOf(0x97, 0, 127), listOf(0x97, 1, 127), listOf(0x97, 0, 0), listOf(0x89, 3, 64))
        for (split in 0..data.size) {
            val events = mutableListOf<List<Int>>()
            val parser = MidiByteStream { a, b, c -> events += listOf(a, b, c) }
            parser.accept(data, 0, split); parser.accept(data, split, data.size - split)
            assertEquals(expected, events, "split=$split")
        }
    }
    @Test fun streamSingleByteChunksAndRealtimeInsideSysex() {
        val events = mutableListOf<Int>()
        val parser = MidiByteStream { s, _, _ -> events += s }
        bytes(0xf0, 1, 0xf8, 2, 0xf7, 7, 127, 0x90, 1, 0xfe, 127).forEach { parser.accept(byteArrayOf(it)) }
        assertEquals(listOf(0x90), events)
    }
    @Test fun systemCommonCancelsRunningStatus() {
        val events = mutableListOf<Int>()
        val parser = MidiByteStream { _, n, _ -> events += n }
        parser.accept(bytes(0x90, 1, 127, 0xf2, 0, 0, 2, 127, 0x90, 3, 127))
        assertEquals(listOf(1, 3), events)
    }
    @Test fun oneByteProgramChangeDoesNotStealNextNote() {
        val events = mutableListOf<List<Int>>()
        val parser = MidiByteStream { a, b, c -> events += listOf(a, b, c) }
        parser.accept(bytes(0xc0, 5, 6, 0xd0, 20, 0x99, 0, 127))
        assertEquals(listOf(listOf(0xc0,5,0), listOf(0xc0,6,0), listOf(0xd0,20,0), listOf(0x99,0,127)), events)
    }
    @Test fun newStatusAndResetDiscardIncompleteMessages() {
        val events = mutableListOf<Int>()
        val parser = MidiByteStream { _, n, _ -> events += n }
        parser.accept(bytes(0x90, 1, 0x91, 2, 127, 0x92, 3)); parser.reset()
        parser.accept(bytes(127, 0x93, 4, 127))
        assertEquals(listOf(2,4), events)
    }
    @Test fun offsetsAndLengthsAreValidated() {
        val parser = MidiByteStream { _, _, _ -> error("Unexpected message") }
        assertFailsWith<IllegalArgumentException> { parser.accept(byteArrayOf(0), -1, 1) }
        assertFailsWith<IllegalArgumentException> { parser.accept(byteArrayOf(0), 0, 2) }
        assertFailsWith<IllegalArgumentException> { parser.accept(byteArrayOf(0), 1, Int.MAX_VALUE) }
        parser.accept(byteArrayOf(0), 1, 0)
    }
    @Test fun unboundedSysexIsIgnoredWithoutAccumulatingPayload() {
        val events = mutableListOf<Int>()
        val parser = MidiByteStream { _, n, _ -> events += n }
        parser.accept(bytes(0xf0)); repeat(100) { parser.accept(ByteArray(1024) { 7 }) }
        parser.accept(bytes(0xf7, 0x97, 2, 127))
        assertEquals(listOf(2), events)
    }
    @Test fun all128PadsFollowOfficialFourChannels() {
        val target = Target(); val s = Ddj200Session(target)
        val actual = mutableSetOf<Int>()
        for (bank in 0..3) for (channel in 7..10) for (note in 0..7) {
            target.selectedBank = bank
            val expected = bank*32 + (if (channel < 9) 0 else 8) + (if (channel%2==0) 16 else 0) + note
            send(s, 0x90+channel, note, 127)
            assertTrue(expected in target.held)
            actual += expected
            send(s, 0x80+channel, note, 0)
            assertTrue(target.held.isEmpty())
        }
        assertEquals((0..127).toSet(), actual)
    }
    @Test fun deckChannelsAndUnknownPadsNeverTriggerPads() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x90, 0, 127, 0x91, 7, 127, 0x97, 8, 127, 0x9b, 0, 127)
        assertTrue(t.calls.isEmpty())
    }
    @Test fun noteOffAndVelocityZeroBothRelease() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x97, 0, 127, 0x87, 0, 99, 0x99, 0, 127, 0x99, 0, 0)
        assertTrue(t.held.isEmpty())
        assertEquals(2, t.calls.count { it.startsWith("release:") })
    }
    @Test fun releaseIsPinnedAcrossShiftAndBankChange() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x98, 3, 127)
        t.selectedBank = 3
        send(s, 0x97, 3, 0)
        assertEquals(listOf("pad:19", "release:19:1"), t.calls)
    }
    @Test fun duplicatePressAndShiftVariantDoNotDoubleTrigger() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x97, 1, 127, 1, 127, 0x98, 1, 127)
        assertEquals(listOf("pad:1"), t.calls)
    }
    @Test fun delayedMidiReleaseCannotStopNewerTouchVoice() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x97, 0, 127)
        val touch = t.triggerPad(0)
        send(s, 0x97, 0, 0)
        assertEquals(touch, t.held[0])
    }
    @Test fun heldButtonsAreEdgeTriggeredAndDecksAreIndependent() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x90, 0x0b, 127, 0x0b, 127, 0x91, 0x0b, 127, 0x90, 0x0b, 0, 0x0b, 127)
        assertEquals(listOf("source", "beat", "source"), t.calls)
    }
    @Test fun banksWrapAndLongSyncDoesNotSwitchAgain() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x90, 0x58, 127, 0x5c, 127, 0x91, 0x58, 127)
        assertEquals(listOf("bank:3", "bank:0"), t.calls)
    }
    @Test fun shiftedActionsAreArmUndoRedoAndPanic() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x90, 0x48, 127, 0x60, 127, 0x91, 0x60, 127, 0x47, 127)
        assertEquals(listOf("arm", "undo", "redo", "stop"), t.calls)
    }
    @Test fun panicReleasesPadsAndScratchOnlyOncePerButtonPress() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x97, 0, 127, 0x90, 0x36, 127, 0x0c, 127, 0x0c, 127)
        assertTrue(t.held.isEmpty())
        assertEquals(1, t.calls.count { it == "stop" })
        assertEquals(1, t.calls.count { it == "end" })
    }
    @Test fun resetReleasesThenStopsAndClearsStream() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x99, 2, 127, 0x90, 0x36, 127, 0x97, 1)
        s.reset(stopPlayback = true)
        val before = t.calls.toList(); send(s, 127, 2, 127)
        assertEquals(before, t.calls)
        assertTrue(t.held.isEmpty()); assertEquals("stop", t.calls.last())
    }
    @Test fun jogRequiresAcceptedTouchAndUsesCenteredRelativeDelta() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0xb0, 0x22, 65, 0x90, 0x36, 127, 0xb0, 0x22, 65, 0x23, 63, 0x29, 64, 0x90, 0x67, 0)
        assertEquals(listOf("begin:0", "scratch:0.125", "scratch:-0.125", "scratch:0.0", "end"), t.calls)
    }
    @Test fun secondDeckCannotEndFirstDeckScratch() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x90, 0x36, 127, 0x91, 0x36, 127, 0x36, 0, 0xb1, 0x22, 70)
        assertEquals(listOf("begin:0"), t.calls)
        send(s, 0x90, 0x36, 0); assertEquals("end", t.calls.last())
    }
    @Test fun refusedScratchNeverReceivesSpeedOrEnd() {
        val t = Target(); t.allowScratch = false; val s = Ddj200Session(t)
        send(s, 0x90, 0x36, 127, 0xb0, 0x22, 70, 0x90, 0x36, 0)
        assertEquals(listOf("begin:0"), t.calls)
    }
    @Test fun jogSpeedIsBoundedAndWheelSideIsIgnored() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x91, 0x36, 127, 0xb1, 0x22, 0, 0x22, 127, 0x21, 70)
        assertEquals(listOf("begin:1", "scratch:-4.0", "scratch:4.0"), t.calls)
    }
    @Test fun ccRequiresFreshPairAndSurvivesPacketSplits() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0xb0, 0, 64); assertTrue(t.calls.isEmpty())
        send(s, 0xb0, 32); send(s, 0)
        assertEquals(8192/16383f, t.values[DdjParameter.SOURCE_PITCH to -1])
        send(s, 0xb0, 32, 127)
        assertEquals(1, t.calls.size)
    }
    @Test fun ccReversePairOrderIsAcceptedWithoutMixingChannels() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0xb0, 0, 64, 0xb1, 32, 0)
        assertTrue(t.calls.isEmpty())
        send(s, 0xb1, 0, 64)
        assertEquals(listOf("value:BPM:-1"), t.calls)
        cc(s, 0, 0, 8192, reverse = true)
        assertTrue(t.values.containsKey(DdjParameter.SOURCE_PITCH to -1))
    }
    @Test fun ccPickupPreventsConnectionSnapshotJump() {
        val t = Target(); val s = Ddj200Session(t)
        cc(s, 0, 0, 0); assertTrue(t.calls.isEmpty())
        cc(s, 0, 0, 16383)
        assertEquals(1f, t.values[DdjParameter.SOURCE_PITCH to -1])
        cc(s, 0, 0, 16000)
        assertEquals(16000/16383f, t.values[DdjParameter.SOURCE_PITCH to -1])
    }
    @Test fun fadersRequireSidePadAndFollowPinnedPadNotOtherSide() {
        val t = Target(); val s = Ddj200Session(t)
        cc(s, 0, 19, 8192); assertTrue(t.calls.isEmpty())
        send(s, 0x97, 2, 127, 0x99, 3, 127)
        cc(s, 0, 19, 8192); cc(s, 1, 19, 8192)
        assertTrue(t.values.containsKey(DdjParameter.PAD_GAIN to 2))
        assertTrue(t.values.containsKey(DdjParameter.PAD_GAIN to 11))
    }
    @Test fun colorFxUsesGlobalChannelSevenAndBankChangeRejectsOldPad() {
        val t = Target(); val s = Ddj200Session(t)
        send(s, 0x9a, 7, 127)
        cc(s, 6, 24, 8192)
        assertTrue(t.values.containsKey(DdjParameter.PAD_TONE to 31))
        val size = t.calls.size; t.selectedBank = 1
        cc(s, 6, 24, 8500)
        assertEquals(size, t.calls.size)
    }
    @Test fun eqCrossfaderFaderStartAndHeadphoneNotesAreUnassigned() {
        val t = Target(); val s = Ddj200Session(t)
        cc(s, 0, 7, 8192); cc(s, 0, 11, 8192); cc(s, 0, 15, 8192); cc(s, 6, 31, 8192)
        send(s, 0x90, 0x66, 127, 0x52, 127, 0x54, 127, 0x96, 0x63, 127, 0x59, 127)
        assertTrue(t.calls.isEmpty())
    }
    @Test fun resetRevokesPickupAndPartialCcState() {
        val t = Target(); val s = Ddj200Session(t)
        cc(s, 0, 0, 8192); send(s, 0xb1, 0, 64); s.reset()
        val before = t.calls.size
        cc(s, 0, 0, 0); send(s, 0xb1, 32, 0)
        assertEquals(before, t.calls.size)
    }
    @Test fun pickupTracksExternalChangesAndRejectsNonFiniteValues() {
        val p = MidiPickup()
        assertFalse(p.accept(0f, 0.5f)); assertTrue(p.accept(0.5f, 0.5f))
        assertTrue(p.accept(0.6f, 0.5f)); assertFalse(p.accept(0.7f, 0.1f))
        assertTrue(p.accept(0f, 0.1f)); p.reset()
        assertFalse(p.accept(Float.NaN, 0f)); assertFalse(p.accept(0f, Float.POSITIVE_INFINITY))
    }
    @Test fun deviceMatchingIsModelSpecific() {
        listOf("DDJ-200", "Pioneer DDJ-200", "DDJ 200", "ddj200 MIDI").forEach { assertTrue(isDdj200Name(it), it) }
        listOf(null, "DDJ-400", "DDJ-2000", "NotDDJ200", "Keyboard").forEach { assertFalse(isDdj200Name(it)) }
    }
}
