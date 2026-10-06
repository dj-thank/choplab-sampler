package com.choplab.jvm

import com.choplab.core.StemSampleFormat
import com.choplab.engine.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class StemExportSelectionTest {
    private fun fx() = TrackFx(MixInsert(MixEq(3f, -2f, 4f), MixFilter(MixFilterMode.HIGH_PASS, 20f),
        MixCompressor(true, -45f, 4f, .1f, 2000f)), .3f, .2f)
    private fun mix(channels: Int) = MixerProgram(List(channels) { fx() }, MixSettings(
        MixDelay(true, 137, .3f, .4f), MixReverb(true, .1f, .2f, .2f),
        MixInsert(compressor = MixCompressor(true, -30f, 3f)), .7f))
    private fun pcm(frames: Int, bus: Int = 0) = PcmAsset.fromInterleaved(FloatArray(frames * 2) { i ->
        if (i % 2 == 0) .08f * cos((i / 2) * (.031 + bus * .001)).toFloat()
        else -.019f * sin((i / 2) * (.043 + bus * .002)).toFloat()
    })

    @Test fun individualStemsMatchTheFullGraphBytesAcrossTailActivityAndLateRestart() {
        val source = pcm(2048)
        for (mixer in listOf(MixerProgram(List(2) { TrackFx() }), mix(2))) {
            // Another bus keeps the graph active after bus 0 ends. Restart bus 0 after its own
            // hypothetical tail, but before the full graph would reset its compressor history.
            val late = mixer.tailFrames.toLong() + 3000
            val frames = (late + 1800).toInt() // Stop also truncates the final clip deliberately.
            val program = EngineProgram(arrangement = Arrangement(listOf(
                ArrangementClip("first", source, 0, sourceStartFrame = 123, gain = .8f, pan = -.2f),
                ArrangementClip("other", source, 8000, trackIndex = 1, gain = .5f, pan = .3f),
                ArrangementClip("restart", source, late, trackIndex = 0),
            )), mixer = mixer)
            val buses = listOf(0, 1, MixerProgram.DELAY_RETURN, MixerProgram.REVERB_RETURN)
            for (format in StemSampleFormat.entries) {
                // Multiple outputs retain the original complete DSP graph. Each single-output
                // production pass must agree byte for byte, including seeded 16/24-bit dither.
                val reference = buses.map { it to ByteArrayOutputStream() }
                StreamingStemRenderer.render(program, reference, frames, mixer.tailFrames, format, seed = 997, blockFrames = 17)
                for (bus in buses) {
                    val actual = ByteArrayOutputStream()
                    StreamingStemRenderer.render(program, listOf(bus to actual), frames, mixer.tailFrames, format,
                        seed = 997, blockFrames = 4096)
                    assertContentEquals(reference.first { it.first == bus }.second.toByteArray(), actual.toByteArray(),
                        "bypass=${mixer.bypass} format=$format bus=$bus")
                }
            }
        }
    }

    @Test fun patternChokeAndVoiceSchedulingRetainTheCompleteGraph() {
        val source = pcm(4096)
        val program = EngineProgram(List(2) { bus -> Pad(bus, source, mixBus = bus, chokeGroup = 1, attackFrames = 0) },
            Pattern(3840, listOf(SequenceNote(0, 0), SequenceNote(97, 1), SequenceNote(150, 0))), mixer = mix(2))
        val reference = listOf(0 to ByteArrayOutputStream(), 1 to ByteArrayOutputStream())
        StreamingStemRenderer.render(program, reference, 8000, 200, blockFrames = 17)
        for ((bus, expected) in reference) {
            val actual = ByteArrayOutputStream()
            StreamingStemRenderer.render(program, listOf(bus to actual), 8000, 200, blockFrames = 192)
            assertContentEquals(expected.toByteArray(), actual.toByteArray())
        }
    }

    @Test fun twelveTrackSequentialExportReportsItsWorkAndCpuWithoutATimingPassThreshold() {
        val frames = 6 * EngineFormat.SAMPLE_RATE
        val mixer = mix(12)
        val program = EngineProgram(arrangement = Arrangement(List(12) { bus ->
            ArrangementClip("track-$bus", pcm(frames, bus), 0, trackIndex = bus)
        }), mixer = mixer)
        val buses = (0 until 12).toList() + listOf(MixerProgram.DELAY_RETURN, MixerProgram.REVERB_RETURN)
        val bean = ManagementFactory.getThreadMXBean()
        check(bean.isCurrentThreadCpuTimeSupported)
        if (!bean.isThreadCpuTimeEnabled) bean.isThreadCpuTimeEnabled = true
        fun passes(measure: Boolean): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val startCpu = bean.currentThreadCpuTime
            val start = System.nanoTime()
            var blocks = 0L
            for (bus in buses) {
                var previous = 0L
                StreamingStemRenderer.render(program, listOf(bus to DigestOutputStream(OutputStream.nullOutputStream(), digest)),
                    frames, mixer.tailFrames, progress = { written ->
                        assertTrue(written > previous)
                        previous = written
                        blocks++
                    })
                assertEquals(frames.toLong() + mixer.tailFrames, previous)
            }
            val elapsed = System.nanoTime() - start
            val cpu = bean.currentThreadCpuTime - startCpu
            check(startCpu >= 0 && cpu >= 0)
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (measure) println("STEM_SEQUENTIAL_PERF tracks=12 passes=${buses.size} framesPerPass=${frames + mixer.tailFrames} " +
                "residentBytes=${program.residentBytes} blockFrames=480 blocks=$blocks wallNs=$elapsed threadCpuNs=$cpu sha256=$hash " +
                "os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")} java=${System.getProperty("java.runtime.version")}")
            return hash
        }
        val warm = passes(false)
        assertEquals(warm, passes(true))
    }
}
