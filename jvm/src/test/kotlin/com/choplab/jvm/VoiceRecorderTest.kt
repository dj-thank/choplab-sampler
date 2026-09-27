package com.choplab.jvm

import java.nio.file.Files
import kotlin.test.*

/** A take recorded from a scripted microphone: its samples, its lead-in before the song, its limit and its end. */
class VoiceRecorderTest {
    @Test fun aTakeKeepsWhatTheMicrophoneHeardAndItsLeadIn() {
        val store = FileAssetStore(Files.createTempDirectory("choplab-voice-store-"))
        val scratch = Files.createTempDirectory("choplab-voice-")
        val mic = ScriptedMic()
        val recorder = VoiceRecorder(mic, scratch, maxSeconds = 10)
        // The microphone starts capturing; the song starts about 50 ms later; 200 ms are captured in all.
        mic.buffers.put(FloatArray(4_800) { .1f })
        Thread.sleep(50)
        recorder.cue()
        mic.buffers.put(FloatArray(4_800) { .2f })
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!mic.drained && System.nanoTime() < deadline) Thread.sleep(5)
        Thread.sleep(20)
        val take = assertNotNull(recorder.finish(store, "VOICE D01"))
        assertEquals("ChopLab-NEXT-voice", mic.closedBy?.name, "The recording thread releases the microphone after its last read")
        assertFalse(recorder.interrupted, "Stopped when asked")
        assertEquals("VOICE D01", take.asset.name)
        assertEquals(9_600L, take.asset.frames)
        // The lead-in is what was captured before the song started: about 50 ms, 2 400 frames.
        assertTrue(take.leadFrames in 1_900..4_300, "lead ${take.leadFrames}")
        val audio = store.openVerified(take.asset).use { WavCodec.read(it) }
        assertContentEquals(FloatArray(4_800) { .1f } + FloatArray(4_800) { .2f }, audio.samples)
        assertEquals(0, Files.list(scratch).use { it.count() })
    }

    @Test fun aLateFirstFrameGivesANegativeLeadAndALostInputIsReported() {
        val store = FileAssetStore(Files.createTempDirectory("choplab-voice-store-"))
        val scratch = Files.createTempDirectory("choplab-voice-")
        val mic = ScriptedMic()
        val recorder = VoiceRecorder(mic, scratch, maxSeconds = 10)
        // The song starts at once; the microphone's first 100 ms arrive only about 100 ms later.
        recorder.cue()
        Thread.sleep(100)
        mic.buffers.put(FloatArray(4_800) { .1f })
        withinSeconds(5) { mic.drained }
        Thread.sleep(20)
        assertFalse(recorder.interrupted)
        // The input goes away by itself (another app took it, a USB microphone was unplugged).
        mic.stopped = true
        withinSeconds(5) { mic.closedBy != null }
        assertTrue(recorder.interrupted)
        val take = assertNotNull(recorder.finish(store, "VOICE D05"))
        // About 100 ms late: 4 800 frames, give or take the test's own timing.
        assertTrue(take.leadFrames in -7_200L..-4_500L, "lead ${take.leadFrames}")
    }

    @Test fun aTakeStopsAtItsLimitAndSilenceStoresNothing() {
        val store = FileAssetStore(Files.createTempDirectory("choplab-voice-store-"))
        val scratch = Files.createTempDirectory("choplab-voice-")
        val mic = ScriptedMic(sampleRate = 8_000)
        val recorder = VoiceRecorder(mic, scratch, maxSeconds = 1)
        repeat(5) { mic.buffers.put(FloatArray(2_000) { .3f }) }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!recorder.full && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(recorder.full)
        withinSeconds(5) { mic.closedBy != null }
        assertTrue(mic.closedBy != null, "At the limit the microphone is released at once")
        assertFalse(recorder.interrupted, "Reaching the limit is not an interruption")
        val take = assertNotNull(recorder.finish(store, "VOICE D02"))
        assertEquals(8_000L, take.asset.frames, "One second at 8 kHz")
        assertEquals(0L, take.leadFrames, "Never cued: no lead-in")

        val quiet = VoiceRecorder(ScriptedMic(), scratch, maxSeconds = 1)
        assertNull(quiet.finish(store, "VOICE D03"), "Nothing heard, nothing stored")
        // A muted input, or one the platform did not allow, delivers only zeros: that is nothing heard either.
        val muted = ScriptedMic().also { it.buffers.put(FloatArray(4_800)) }
        val silent = VoiceRecorder(muted, scratch, maxSeconds = 1)
        withinSeconds(5) { muted.drained }
        Thread.sleep(20)
        assertNull(silent.finish(store, "VOICE D04"))
        val dropped = VoiceRecorder(ScriptedMic().also { it.buffers.put(FloatArray(64)) }, scratch, maxSeconds = 1)
        Thread.sleep(50)
        dropped.discard()
        assertEquals(0, Files.list(scratch).use { it.count() })
    }

}
