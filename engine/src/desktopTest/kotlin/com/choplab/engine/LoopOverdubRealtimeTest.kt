package com.choplab.engine

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.math.sin
import kotlin.test.*

class LoopOverdubRealtimeTest {
    @Test fun overdubWithThirtyOnePrimarySlotsAndThirtyTwoClipsAllocatesZeroAcrossTenThousandBlocks() {
        for (handAndClick in listOf(true)) {
            val source = PcmAsset.fromMono(FloatArray(4_000_000) { (.001 * sin(it * .05)).toFloat() })
            val count = if (handAndClick) 14 else 15
            val pads = (0 until 16).map { Pad(it, source, endFrame = 8_192, mode = PlayMode.LOOP, attackFrames = 0) }
            val arrangement = Arrangement((0 until 32).map { ArrangementClip("clip-$it", source, 0, trackIndex = it % 16) })
            val engine = EngineCore(EngineProgram(pads, tempo = Tempo(97_125, 710), arrangement = arrangement),
                EngineConfig(controlCapacity = 128, eventCapacity = 2))
            val take = LoopOverdub(0, 48_000)
            try {
                var id = 0L
                engine.controls.offer(EngineCommand.SetOriginalSource(0, id++, OriginalSource(source, endFrame = 8_192, loop = true)))
                engine.controls.offer(EngineCommand.PlayOriginalSource(0, id++))
                engine.controls.offer(EngineCommand.StartLoopOverdub(0, id++, take))
                engine.controls.offer(EngineCommand.Resume(0, id++))
                if (handAndClick) {
                    engine.controls.offer(EngineCommand.SetMetronome(0, id++, true))
                    engine.controls.offer(EngineCommand.ScratchOriginalStart(0, id++, 1_000.0, 500, 7_500))
                    for (pad in 14..14) engine.controls.offer(EngineCommand.Trigger(0, id++, pad))
                }
                repeat(count) { engine.controls.offer(EngineCommand.StartNoteRepeat(0, id++, it, if (it % 2 == 0) 160 else 120)) }
                val output = FloatArray(192 * 2)
                repeat(10_000) { engine.render(output) }
                val blocks = 10_000
                val times = LongArray(blocks)
                val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
                assertTrue(bean.isThreadAllocatedMemorySupported)
                bean.isThreadAllocatedMemoryEnabled = true
                val thread = Thread.currentThread().id
                repeat(100_000) { bean.getThreadAllocatedBytes(thread); System.nanoTime() }
                val runtime = Runtime.getRuntime()
                val collectors = ManagementFactory.getGarbageCollectorMXBeans()
                val gcCountBefore = collectors.sumOf { it.collectionCount }
                val gcMillisBefore = collectors.sumOf { it.collectionTime }
                val heapBefore = runtime.totalMemory() - runtime.freeMemory()
                val startedAt = java.time.Instant.now()
                var allocated = 0L
                for (block in 0 until blocks) {
                    val before = bean.getThreadAllocatedBytes(thread)
                    val start = System.nanoTime()
                    engine.render(output)
                    times[block] = System.nanoTime() - start
                    allocated += bean.getThreadAllocatedBytes(thread) - before
                }
                val completedAt = java.time.Instant.now()
                val heapAfter = runtime.totalMemory() - runtime.freeMemory()
                val gcCount = collectors.sumOf { it.collectionCount } - gcCountBefore
                val gcMillis = collectors.sumOf { it.collectionTime } - gcMillisBefore
                times.sort()
                println("LOOP_OVERDUB JVM JDK=${System.getProperty("java.version")} rate=48000 block=192 " +
                    "warmup=10000 blocks=$blocks held=$count handAndClick=$handAndClick source=1 arrangement=32 " +
                    "renderAllocatedBytes=$allocated p99ns=${times[9899]} maxNs=${times.last()} " +
                    "p99BlockFraction=${times[9899] / 4_000_000.0} startedAt=$startedAt completedAt=$completedAt " +
                    "heapMaxBytes=${runtime.maxMemory()} heapBeforeBytes=$heapBefore heapAfterBytes=$heapAfter " +
                    "gcCollections=$gcCount gcMillis=$gcMillis sourcePcmBytes=${engine.residentBytes} " +
                    "loopReservedBytes=${LoopOverdub.memoryBytes(take.frames)} mixerReservedBytes=${MixerDsp.PCM_BYTES} " +
                    "pcmUnderrunFrames=${engine.pcmUnderrunFrames}; synthetic desktop only")
                assertEquals(0L, allocated)
                assertEquals(0L, engine.rejectedVoices)
                assertEquals(32, engine.activeVoiceCount)
                assertEquals(32, engine.activeClipCount)
                assertTrue(engine.residentBytes <= EngineFormat.MAX_RESIDENT_BYTES)
                engine.controls.offer(EngineCommand.StopAll(engine.frame, id))
                repeat(100) { engine.render(output) }
                assertEquals(0, engine.activeVoiceCount)
                assertTrue(output.all { it == 0f })
            } finally { engine.close(); take.dispose() }
        }
    }
}
