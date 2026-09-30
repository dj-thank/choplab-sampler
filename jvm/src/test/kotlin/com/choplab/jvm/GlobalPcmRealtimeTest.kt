package com.choplab.jvm

import com.choplab.core.model.Asset
import com.choplab.engine.*
import com.sun.management.ThreadMXBean
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import kotlin.test.*

/** The global near-full cache and its actual worker stay alive throughout this render measurement. */
class GlobalPcmRealtimeTest {
    @Test fun fullLongCachesAndEightyReadersKeepRenderAllocationZeroWithinOne128MiBBudget() = measure(false)
    @Test fun fullMixerFxAndEightyReadersKeepRenderAllocationZeroWithinOne128MiBBudget() = measure(true)

    private fun measure(effects: Boolean): Unit = runBlocking {
        val directory = Files.createTempDirectory("pcm-global-realtime-")
        val memory = PcmMemoryBudget()
        val decoder = GlobalPcmBudgetTest.SyntheticDecoder()
        val store = FileAssetStore(directory, decoder = decoder)
        val pcm = WavPcmPort(store, decoder = decoder, memory = memory)
        val leases = mutableListOf<PcmLease>()
        val late = 375 * 48_000
        try {
            repeat(7) { value ->
                val bytes = byteArrayOf(value.toByte())
                val asset = Asset(sha256(bytes), "flac", 1, 48_000, 2, 19_200_000, "fixture")
                store.publish(asset, ByteArrayInputStream(bytes))
                val lease = pcm.acquire(asset).also(leases::add)
                pcm.prefetch(lease.pcm, 0, 512 * PagedPcm.PAGE_FRAMES)
                pcm.prefetch(lease.pcm, late - 16_384, late + if (effects) 212_992 else 24_576)
                assertEquals(512, lease.pcm.pages!!.statistics().loadedPages)
            }
            // A failed new decode cannot silence/replace any of the seven active sources.
            assertFailsWith<PcmMemoryLimit> { memory.reserve(16L * 1024 * 1024) }
            assertTrue(leases.none { it.pcm.evicted })
            val pads = (0 until 30).map { Pad(it, leases[it % 7].pcm, late, late + 8_192, mode = PlayMode.LOOP,
                attackFrames = 0, loopCrossfadeFrames = 0, mixBus = it % MixerProgram.MAX_BUSES) }
            val arrangement = Arrangement((0 until 32).map {
                ArrangementClip("clip-$it", leases[it % 7].pcm, 0, late, late + if (effects) 200_000 else 8_192, trackIndex = it % 16)
            })
            val mixer = if (!effects) MixerProgram.BYPASS else MixerProgram(List(MixerProgram.MAX_BUSES) {
                TrackFx(MixInsert(MixEq(18f, -18f, 18f), MixFilter(MixFilterMode.HIGH_PASS, 20f),
                    MixCompressor(true, -60f, 20f, .1f, 2000f, 18f)), 1f, 1f)
            }, MixSettings(MixDelay(true, 96_000, .6f, 2f), MixReverb(true, 3f, .95f, 2f),
                MixInsert(MixEq(18f, -18f, 18f), MixFilter(MixFilterMode.LOW_PASS, 20_000f),
                    MixCompressor(true, -60f, 20f, .1f, 2000f, 18f)), 8f))
            memory.reserve(MixerDsp.PCM_BYTES).use {
            val engine = EngineCore(EngineProgram(pads, arrangement = arrangement, mixer = mixer), EngineConfig(controlCapacity = 128, eventCapacity = 2))
            try {
                memory.reserve(8192).use {
                    var id = 0L
                    val source = leases.first().pcm
                    engine.controls.offer(EngineCommand.SetOriginalSource(0, id++, OriginalSource(source, late, late + 8_192, loop = true)))
                    engine.controls.offer(EngineCommand.PlayOriginalSource(0, id++))
                    engine.controls.offer(EngineCommand.ScratchOriginalStart(0, id++, late + 1_000.0, late + 500, late + 7_500))
                    repeat(30) { engine.controls.offer(EngineCommand.Trigger(0, id++, it)) }
                    engine.controls.offer(EngineCommand.SetMetronome(0, id++, true))
                    engine.controls.offer(EngineCommand.StartSequence(0, id++))
                    val single = FloatArray(2)
                    engine.render(single)
                    repeat(16) { engine.controls.offer(EngineCommand.Trigger(engine.frame, id++, 0)) }
                    engine.render(single)
                    assertEquals(32, engine.activeVoiceCount)
                    assertEquals(16, engine.fadeVoiceCount)
                    assertEquals(32, engine.activeClipCount)
                    val output = FloatArray(192 * 2)
                    val stopOutput = FloatArray(400 * 2)
                    engine.render(output) // Complete the initial 16 fade voices before the first new burst.
                    val perBlock = 22
                    fun command(frame: Long, order: Long, block: Int, index: Int): EngineCommand = when (index) {
                        // Full-FX passes keep >2 seconds of ring history; a seek every block would
                        // measure permanently empty delay/reverb rings, not the actual worst graph.
                        0 -> if (!effects || block % 1000 == 0) EngineCommand.Seek(frame, order, 0)
                            else EngineCommand.SetSongMonitorGain(frame, order, 1f)
                        1 -> EngineCommand.SeekOriginalSource(frame, order, late + (block * 193L) % 8_192)
                        2 -> EngineCommand.SetOriginalPitch(frame, order, if (block % 2 == 0) 24f else 17f)
                        3 -> EngineCommand.ScratchOriginalPosition(frame, order, late + if (block % 2 == 0) 7_499.0 else 500.0, 192)
                        4 -> EngineCommand.SetHandMonitorGain(frame, order, if (block % 3 == 0) .5f else 1f)
                        5 -> EngineCommand.ScratchOriginalCut(frame, order, if (block % 5 == 0) 0f else 1f)
                        else -> EngineCommand.Trigger(frame, order, (block + index) % 30)
                    }
                    repeat(10_000) { block ->
                        repeat(perBlock) { engine.controls.offer(command(engine.frame, id++, block, it)) }
                        engine.render(output)
                    }
                    val blocks = 10_000
                    val first = engine.frame
                    val commands = Array(blocks * perBlock) { index -> command(first + index / perBlock * 192L, id++, index / perBlock, index % perBlock) }
                    val times = LongArray(blocks)
                    val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
                    assertTrue(bean.isThreadAllocatedMemorySupported)
                    bean.isThreadAllocatedMemoryEnabled = true
                    val thread = Thread.currentThread().id
                    repeat(100_000) { bean.getThreadAllocatedBytes(thread); System.nanoTime() }
                    var allocated = 0L
                    var firstAllocatingBlock = -1
                    for (block in 0 until blocks) {
                        for (index in 0 until perBlock) engine.controls.offer(commands[block * perBlock + index])
                        val before = bean.getThreadAllocatedBytes(thread)
                        val started = System.nanoTime()
                        engine.render(output)
                        times[block] = System.nanoTime() - started
                        val increment = bean.getThreadAllocatedBytes(thread) - before
                        allocated += increment
                        if (increment != 0L && firstAllocatingBlock < 0) firstAllocatingBlock = block
                    }
                    times.sort()
                    val stats = memory.statistics()
                    println("GLOBAL_PCM_MAX_MIX JDK=${System.getProperty("java.version")} rate=48000 block=192 warmup=10000 blocks=10000 " +
                        "seven400sCaches=7x512pages arrangement=32 primary=30PAD+1HAND+1click fade=16 SOURCE=1 maxPcmReaders=80 " +
                        "fullMixerFx=$effects fxBuses=${if (effects) 17 else 0} mixerPcmBytes=${MixerDsp.PCM_BYTES} " +
                        "sourcePitch=24/17st handSpeed=+/-8 lateSourceFrame=$late renderAllocatedBytes=$allocated firstAllocatingBlock=$firstAllocatingBlock " +
                        "p99ns=${times[9899]} maxNs=${times.last()} p99BlockFraction=${times[9899] / 4_000_000.0} " +
                        "globalPcmPeak=${stats.peakBytes} globalPcmLimit=${stats.limitBytes} underrunFrames=${engine.pcmUnderrunFrames}; synthetic JVM, no device claim")
                    assertEquals(0, allocated)
                    assertEquals(0, engine.pcmUnderrunFrames)
                    assertEquals(0, engine.rejectedVoices)
                    assertEquals(32, engine.activeVoiceCount)
                    assertEquals(32, engine.activeClipCount)
                    assertTrue(engine.originalPlaying)
                    assertTrue(stats.peakBytes <= stats.limitBytes)
                    if (effects) {
                        val meter = MixerSnapshot()
                        assertTrue(engine.mixerReadout.copyInto(meter))
                        assertTrue(meter.peak[MixerProgram.DELAY_RETURN * 2] > 0f)
                        assertTrue(meter.peak[MixerProgram.REVERB_RETURN * 2] > 0f)
                        assertTrue((0 until MixerProgram.MAX_BUSES).all { meter.peak[it * 2] > 0f })
                    }
                    engine.controls.offer(EngineCommand.StopAll(engine.frame, id))
                    engine.render(stopOutput)
                    assertEquals(0, engine.activeVoiceCount)
                    assertEquals(0, engine.fadeVoiceCount)
                    assertEquals(0, engine.activeClipCount)
                    assertTrue(stopOutput.drop(EngineCore.STOP_TAIL_FRAMES * 2).all { it == 0f })
                }
            } finally { engine.close() }
            }
        } finally {
            leases.forEach { it.close() }; pcm.close()
            assertEquals(0, memory.statistics().usedBytes)
            directory.toFile().deleteRecursively()
        }
    }
}
