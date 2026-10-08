package com.choplab.engine

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.*

/** Same 32-reader load through 16 and 64 configured FX tracks; timing is evidence, not a device pass. */
class LargeArrangementAllocationTest {
    @Test fun fullFxGraphMeasuresSixtyFourTracksWithoutRenderAllocationOrPcmLoss() {
        val bean = assertIs<ThreadMXBean>(ManagementFactory.getThreadMXBean())
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        assertTrue(bean.isCurrentThreadCpuTimeSupported)
        bean.isThreadCpuTimeEnabled = true
        val thread = Thread.currentThread().id
        repeat(100_000) { bean.getThreadAllocatedBytes(thread); System.nanoTime() }
        val source = PcmAsset.fromInterleaved(FloatArray(131_072 * 2) {
            if (it % 2 == 0) .005f * kotlin.math.cos(it * .0031f) else -.002f * kotlin.math.sin(it * .0043f)
        })
        for (tracks in listOf(16, 64)) {
            val insert = MixInsert(MixEq(3f, -2f, 4f), MixFilter(MixFilterMode.HIGH_PASS, 20f),
                MixCompressor(true, -45f, 4f, .1f, 2000f))
            val mixer = MixerProgram(List(tracks) { TrackFx(insert, .3f, .2f) }, MixSettings(
                MixDelay(true, 137, .3f, .4f), MixReverb(true, .1f, .2f, .2f),
                MixInsert(compressor = MixCompressor(true, -30f, 3f)), .7f))
            val program = EngineProgram(arrangement = Arrangement(List(1024) { index ->
                ArrangementClip("clip-$index", source, index / 32 * 131_072L, trackIndex = index % tracks)
            }), mixer = mixer)
            val engine = EngineCore(program, EngineConfig(outputMode = EngineOutputMode.EXPORT))
            val output = FloatArray(192 * 2)
            val stems = FloatArray(192 * MixerProgram.STEM_COUNT * 2)
            val times = LongArray(10_000)
            try {
                assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.StartSequence(0, 1)))
                // Prime both ends and the finite FX tail, avoiding one-sided JIT branch profiles.
                while (engine.frame < program.arrangement!!.durationFrames + mixer.tailFrames) engine.render(output, stemOutput = stems)
                assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.StartSequence(engine.frame, 2)))
                engine.prepareOfflineBlock(192)
                repeat(10_000) { engine.render(output, stemOutput = stems) }
                var allocated = 0L
                var first = -1
                val cpuBefore = bean.currentThreadCpuTime
                for (block in times.indices) {
                    val bytesBefore = bean.getThreadAllocatedBytes(thread)
                    val started = System.nanoTime()
                    engine.render(output, stemOutput = stems)
                    times[block] = System.nanoTime() - started
                    val delta = bean.getThreadAllocatedBytes(thread) - bytesBefore
                    check(bytesBefore >= 0 && delta >= 0)
                    allocated += delta
                    if (delta != 0L && first < 0) first = block
                }
                val cpuNs = bean.currentThreadCpuTime - cpuBefore
                check(cpuBefore >= 0 && cpuNs >= 0)
                times.sort()
                val context = "LARGE_ARRANGEMENT tracks=$tracks simultaneous=32 clips=1024 fullFx=true stemChannels=${MixerProgram.STEM_COUNT} " +
                    "block=192 warmup=10000 blocks=10000 renderAllocatedBytes=$allocated firstAllocatingBlock=$first " +
                    "p99ns=${times[9899]} maxNs=${times.last()} p99BlockFraction=${times[9899] / 4_000_000.0} " +
                    "threadCpuNs=$cpuNs mixerPcmBytes=${MixerDsp.PCM_BYTES} residentPcmBytes=${program.residentBytes} " +
                    "java=${System.getProperty("java.runtime.version")} os=${System.getProperty("os.name")}/${System.getProperty("os.arch")}; synthetic JVM only"
                println(context)
                assertEquals(0L, allocated, context)
                assertEquals(0, engine.pcmUnderrunFrames)
                assertEquals(32, engine.activeClipCount)
                assertTrue(engine.sequencePlaying)
            } finally { engine.close() }
        }
    }
}
