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
    @Test fun sourceHandAndMaximumArrangementPadFadeMixAllocateZeroAndStopCompletely() {
        val source = PcmAsset.fromMono(FloatArray(8_192) { (.001 * sin(2 * PI * it / 64)).toFloat() })
        val pads = (0 until 31).map { Pad(it, source, mode = PlayMode.LOOP, attackFrames = 0, loopCrossfadeFrames = 0) }
        val arrangement = Arrangement((0 until 32).map { ArrangementClip("clip-$it", source, 0, trackIndex = it % 16) })
        val engine = EngineCore(EngineProgram(pads, arrangement = arrangement), EngineConfig(controlCapacity = 128, eventCapacity = 2))
        var id = 0L
        engine.controls.offer(EngineCommand.SetOriginalSource(0, id++, OriginalSource(source, loop = true)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, id++))
        engine.controls.offer(EngineCommand.ScratchOriginalStart(0, id++, 1_000.0, 500, 7_500))
        for (i in 0 until 31) engine.controls.offer(EngineCommand.Trigger(0, id++, i))
        engine.controls.offer(EngineCommand.StartSequence(0, id++))
        engine.render(FloatArray(2))
        repeat(16) { engine.controls.offer(EngineCommand.Trigger(engine.frame, id++, 0)) }
        engine.render(FloatArray(2))
        assertEquals(32, engine.activeVoiceCount) // 31 PAD + one reserved HAND.
        assertEquals(16, engine.fadeVoiceCount)
        assertEquals(32, engine.activeClipCount)
        assertTrue(engine.originalPlaying)
        val output = FloatArray(192 * 2)
        engine.render(output)
        val perBlock = 22
        fun command(frame: Long, order: Long, block: Int, index: Int): EngineCommand = when (index) {
            0 -> EngineCommand.Seek(frame, order, (block * 97L) % 7_000)
            1 -> EngineCommand.SeekOriginalSource(frame, order, (block * 193L) % 8_192)
            2 -> EngineCommand.SetOriginalPitch(frame, order, if (block % 2 == 0) 24f else 17f)
            3 -> EngineCommand.ScratchOriginalPosition(frame, order, if (block % 2 == 0) 7_499.0 else 500.0, 192)
            4 -> EngineCommand.SetHandMonitorGain(frame, order, if (block % 3 == 0) .5f else 1f)
            5 -> EngineCommand.ScratchOriginalCut(frame, order, if (block % 5 == 0) 0f else 1f)
            else -> EngineCommand.Trigger(frame, order, 0)
        }
        repeat(10_000) { block ->
            repeat(perBlock) { engine.controls.offer(command(engine.frame, id++, block, it)) }
            engine.render(output)
        }
        val blocks = 10_000
        val base = engine.frame
        val commands = Array(blocks * perBlock) { i -> command(base + i / perBlock * 192L, id++, i / perBlock, i % perBlock) }
        val times = LongArray(blocks)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        repeat(100_000) { bean.getThreadAllocatedBytes(thread); System.nanoTime() }
        var renderAllocated = 0L
        var firstAllocatingBlock = -1
        for (block in 0 until blocks) {
            for (j in 0 until perBlock) engine.controls.offer(commands[block * perBlock + j])
            val before = bean.getThreadAllocatedBytes(thread)
            val start = System.nanoTime()
            engine.render(output)
            times[block] = System.nanoTime() - start
            val delta = bean.getThreadAllocatedBytes(thread) - before
            renderAllocated += delta
            if (delta != 0L && firstAllocatingBlock < 0) firstAllocatingBlock = block
        }
        times.sort()
        println("SOURCE_HAND JVM JDK=${System.getProperty("java.version")} rate=48000 block=192 warmup=10000 blocks=$blocks " +
            "arrangement=32 primary=31PAD+1HAND fade=16 SOURCE=1 maxReaders=81 sourcePitch=24/17st handSpeed=+/-8 " +
            "renderAllocatedBytes=$renderAllocated firstAllocatingBlock=$firstAllocatingBlock p99ns=${times[9899]} maxNs=${times.last()} " +
            "p99BlockFraction=${times[9899] / 4_000_000.0}; desktop synthetic only")
        assertEquals(0L, renderAllocated)
        assertEquals(0L, engine.rejectedVoices)
        assertEquals(32, engine.activeVoiceCount)
        assertEquals(32, engine.activeClipCount)
        assertTrue(engine.originalPlaying)
        engine.controls.offer(EngineCommand.StopAll(engine.frame, id))
        val stopped = FloatArray(400 * 2)
        engine.render(stopped)
        assertEquals(0, engine.activeVoiceCount)
        assertEquals(0, engine.fadeVoiceCount)
        assertEquals(0, engine.activeClipCount)
        assertEquals(-1.0, engine.handSourceFrame)
        assertTrue(stopped.drop(EngineCore.STOP_TAIL_FRAMES * 2).all { it == 0f })
    }

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
            // The worst case for the original: a pitched key through the widest band-limited reader, changing every block.
            engine.controls.offer(EngineCommand.SetOriginalPitch(engine.frame, id++, if (block % 2 == 0) 24f else 17f))
            repeat(15) { engine.controls.offer(EngineCommand.Trigger(engine.frame, id++, 0)) }
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
                4 -> EngineCommand.SetOriginalPitch(engine.frame + block * 192L, id + i, if (block % 2 == 0) 24f else 17f)
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
            "documentClips=1024 tracks=16 activeClips=32 pads=32+16fade originalSources=1 originalKeyChangesEveryBlock=24/17st dualMonitorGainChanges=true bothBusesSeekEveryBlock=true sourceCrossfadeFrames=96 renderAllocatedBytes=$renderAllocated " +
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

    @Test fun scratchingAPadAndTheOriginalAllocatesZeroAcrossTenThousandBlocks() {
        val source = PcmAsset.fromMono(FloatArray(8_192) { (.005 * sin(2 * PI * it / 64)).toFloat() })
        val pads = (0 until 32).map { Pad(it, source, mode = PlayMode.LOOP, attackFrames = 0, loopCrossfadeFrames = 0) }
        val engine = EngineCore(EngineProgram(pads), EngineConfig(controlCapacity = 128, eventCapacity = 2))
        for (i in 0 until 31) engine.controls.offer(EngineCommand.Trigger(0, i.toLong(), i))
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 31, OriginalSource(source)))
        val output = FloatArray(192 * 2)
        engine.render(output)
        var id = 32L
        // Each block moves both scratches, now and then works the cut, and every 50 blocks lets go and takes hold again.
        fun block(frame: Long, block: Int, next: () -> Long): Array<EngineCommand> {
            val target = (block * 37 % 8_000).toDouble()
            val commands = ArrayList<EngineCommand>(6)
            if (block % 50 == 0) {
                commands += EngineCommand.ScratchStart(frame, next(), 31, target)
                commands += EngineCommand.ScratchOriginalStart(frame, next(), target, 100, 8_000)
            }
            commands += EngineCommand.ScratchPosition(frame, next(), target + 150, 192)
            commands += EngineCommand.ScratchOriginalPosition(frame, next(), 8_000 - target, 192)
            if (block % 7 == 0) {
                commands += EngineCommand.ScratchCut(frame, next(), (block % 2).toFloat())
                commands += EngineCommand.ScratchOriginalCut(frame, next(), (block % 3 % 2).toFloat())
            }
            if (block % 50 == 49) {
                commands += EngineCommand.ScratchEnd(frame, next())
                commands += EngineCommand.ScratchOriginalEnd(frame, next())
            }
            return commands.toTypedArray()
        }
        repeat(1_500) { b ->
            for (command in block(engine.frame, b) { id++ }) engine.controls.offer(command)
            engine.render(output)
        }
        val blocks = 10_000
        val base = engine.frame
        val commands = Array(blocks) { b -> block(base + b * 192L, b) { id++ } }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        repeat(100_000) { bean.getThreadAllocatedBytes(thread); System.nanoTime() }
        var renderAllocated = 0L
        for (b in 0 until blocks) {
            for (command in commands[b]) engine.controls.offer(command)
            val renderBefore = bean.getThreadAllocatedBytes(thread)
            engine.render(output)
            renderAllocated += bean.getThreadAllocatedBytes(thread) - renderBefore
        }
        println("SCRATCH JVM JDK=${System.getProperty("java.version")} rate=48000 block=192 warmup=1500 blocks=$blocks " +
            "primaryBudget=32 handReservesOne padScratch=1 renderAllocatedBytes=$renderAllocated; desktop synthetic only")
        assertEquals(0L, renderAllocated)
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
