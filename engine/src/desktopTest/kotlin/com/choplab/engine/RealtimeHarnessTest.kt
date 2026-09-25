package com.choplab.engine

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealtimeHarnessTest {
    @Test fun combinedArrangementPadsFadeAndSeekAllocateZeroAcrossTenThousandBlocks() {
        val source = PcmAsset.fromMono(FloatArray(4096) { (.001 * sin(2 * PI * it / 64)).toFloat() })
        val pads = (0 until 32).map { Pad(it, source, mode = PlayMode.LOOP, attackFrames = 0, loopCrossfadeFrames = 0) }
        val clips = (0 until 1024).map { i ->
            ArrangementClip("clip-$i", source, (i / 32) * 4096L, trackIndex = i % 16)
        }
        val arrangement = Arrangement(clips)
        val engine = EngineCore(EngineProgram(pads, arrangement = arrangement), EngineConfig(controlCapacity = 128, eventCapacity = 2))
        for (i in 0 until 32) engine.controls.offer(EngineCommand.Trigger(0, i.toLong(), i))
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 32, OriginalSource(source, loop = true)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, 33))
        engine.controls.offer(EngineCommand.StartSequence(0, 34))
        val output = FloatArray(192 * 2)
        engine.render(output)
        var id = 35L
        val seekRange = arrangement.durationFrames - 192
        repeat(10000) { block ->
            engine.controls.offer(EngineCommand.Seek(engine.frame, id++, block * 192L % seekRange))
            engine.controls.offer(EngineCommand.SetSongMonitorGain(engine.frame, id++, (block % 2).toFloat()))
            engine.controls.offer(EngineCommand.SetOriginalMonitorGain(engine.frame, id++, if (block % 3 == 0) 0f else 1f))
            engine.controls.offer(EngineCommand.SeekOriginalSource(engine.frame, id++, block * 192L % 4096))
            repeat(16) { engine.controls.offer(EngineCommand.Trigger(engine.frame, id++, 0)) }
            engine.render(output)
        }
        val blocks = 10000
        val commands = Array<EngineCommand>(blocks * 20) { i ->
            val block = i / 20
            when (i % 20) {
                0 -> EngineCommand.Seek(engine.frame + block * 192L, id + i, block * 192L % seekRange)
                1 -> EngineCommand.SetSongMonitorGain(engine.frame + block * 192L, id + i, (block % 2).toFloat())
                2 -> EngineCommand.SetOriginalMonitorGain(engine.frame + block * 192L, id + i, if (block % 3 == 0) 0f else 1f)
                3 -> EngineCommand.SeekOriginalSource(engine.frame + block * 192L, id + i, block * 192L % 4096)
                else -> EngineCommand.Trigger(engine.frame + block * 192L, id + i, 0)
            }
        }
        val times = LongArray(blocks)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        // Warm the VM measurement path too; its compilation must not enter the measured region.
        repeat(100000) { bean.getThreadAllocatedBytes(thread); System.nanoTime() }
        val before = bean.getThreadAllocatedBytes(thread)
        var renderAllocated = 0L
        var firstAllocatingBlock = -1
        for (block in 0 until blocks) {
            for (j in 0 until 20) engine.controls.offer(commands[block * 20 + j])
            // Keep control-producer/test-loop bookkeeping outside the measured render region.
            val renderBefore = bean.getThreadAllocatedBytes(thread)
            val start = System.nanoTime()
            engine.render(output)
            times[block] = System.nanoTime() - start
            val delta = bean.getThreadAllocatedBytes(thread) - renderBefore
            renderAllocated += delta
            if (delta != 0L && firstAllocatingBlock < 0) firstAllocatingBlock = block
        }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        times.sort()
        println("ARRANGEMENT JVM JDK=${System.getProperty("java.version")} rate=48000 block=192 warmup=10000 blocks=$blocks " +
            "documentClips=1024 tracks=16 activeClips=32 pads=32+16fade originalSources=1 dualMonitorGainChanges=true bothBusesSeekEveryBlock=true sourceCrossfadeFrames=96 renderAllocatedBytes=$renderAllocated " +
            "wholeHarnessAllocatedBytes=$allocated firstAllocatingRenderBlock=$firstAllocatingBlock " +
            "p99ns=${times[9899]} maxNs=${times.last()} p99BlockFraction=${times[9899] / 4_000_000.0}; desktop synthetic only")
        assertEquals(0L, renderAllocated)
        assertEquals(32, engine.activeVoiceCount)
        assertEquals(32, engine.activeClipCount)
        assertEquals(0, engine.fadeVoiceCount)
        assertTrue(engine.originalPlaying)
    }

    @Test fun warmedRenderAllocatesZeroBytesAcrossTenThousandBlocks() {
        val source = PcmAsset.fromMono(FloatArray(4096) { (.005 * sin(2 * PI * it / 64)).toFloat() })
        val pads = (0..32).map { Pad(it, source, mode = PlayMode.LOOP, attackFrames = 0, loopCrossfadeFrames = 0) }
        val engine = EngineCore(EngineProgram(pads), EngineConfig(controlCapacity = 128, eventCapacity = 2))
        for (i in 0..31) engine.controls.offer(EngineCommand.Trigger(0, i.toLong(), i))
        val output = FloatArray(192 * 2)
        engine.render(output)
        var id = 32L
        repeat(1500) {
            repeat(16) { engine.controls.offer(EngineCommand.Trigger(engine.frame, id++, 32)) }
            engine.render(output)
        }
        val blocks = 10_000
        val commands = Array(blocks * 16) { i -> EngineCommand.Trigger(engine.frame + (i / 16) * 192, id + i, 32) }
        val times = LongArray(blocks)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        // Initialize management/JIT paths before the measured region.
        repeat(100) { bean.getThreadAllocatedBytes(thread) }
        val before = bean.getThreadAllocatedBytes(thread)
        for (block in 0 until blocks) {
            for (j in 0 until 16) engine.controls.offer(commands[block * 16 + j])
            val start = System.nanoTime()
            engine.render(output)
            times[block] = System.nanoTime() - start
        }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        times.sort()
        println("RENDER JVM JDK=${System.getProperty("java.version")} rate=48000 block=192 warmup=1500 blocks=$blocks " +
            "voices=32+16fade allocatedBytes=$allocated p99ns=${times[9899]} maxNs=${times.last()} " +
            "p99BlockFraction=${times[9899] / 4_000_000.0}; desktop synthetic, no Pixel/underrun claim")
        assertEquals(0L, allocated, "Renderer/queue producer allocated bytes after warm-up")
        assertEquals(32, engine.activeVoiceCount)
        assertEquals(0, engine.fadeVoiceCount)
    }

    @Test fun spscPublicationAndCoherentReadoutSurviveConcurrentProducerAndConsumer() {
        val one = EngineProgram(listOf(Pad(0, PcmAsset.fromMono(FloatArray(8)))), revision = 1)
        val two = EngineProgram(listOf(Pad(0, PcmAsset.fromMono(FloatArray(16)))), revision = 2)
        val engine = EngineCore(one, EngineConfig(controlCapacity = 128, eventCapacity = 128))
        val failure = AtomicReference<Throwable?>(null)
        val renderingDone = AtomicBoolean(false)
        val producer = Thread {
            try {
                for (i in 0L until 10000L) {
                    val command = EngineCommand.SwapProgram(i, i, if (i % 2 == 0L) one else two)
                    while (engine.controls.offer(command) == OfferResult.FULL) Thread.yield()
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        val consumer = Thread {
            try {
                val snapshot = EngineSnapshot()
                val event = MutableEngineEvent()
                var previousFrame = -1L
                var previousOrder = -1L
                while (!renderingDone.get()) {
                    if (engine.readout.copyInto(snapshot)) {
                        check(snapshot.frame >= previousFrame)
                        check(snapshot.programRevision == 1L || snapshot.programRevision == 2L)
                        check(snapshot.residentBytes == if (snapshot.programRevision == 1L) 64L else 128L)
                        check(snapshot.activeVoices == 0 && snapshot.fadeVoices == 0)
                        previousFrame = snapshot.frame
                    }
                    while (engine.events.poll(event)) {
                        check(event.orderId > previousOrder)
                        check(event.appliedFrame >= event.requestedFrame)
                        previousOrder = event.orderId
                    }
                    Thread.yield()
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        producer.start(); consumer.start()
        val output = FloatArray(34)
        while (producer.isAlive || engine.frame < 11000) engine.render(output)
        renderingDone.set(true)
        producer.join(10000); consumer.join(10000)
        assertTrue(!producer.isAlive && !consumer.isAlive)
        assertNull(failure.get())
    }
}
