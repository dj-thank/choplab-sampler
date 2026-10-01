package com.choplab.engine

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.*

class PracticeLoopAllocationTest {
    @Volatile private var allocationWitness: ByteArray? = null

    @Test fun loopingSourceAllocatesNothingDuringTenThousandRenderBlocks() {
        val asset = PcmAsset.fromInterleaved(FloatArray(4_802) { if (it % 2 == 0) .1f else -.03f })
        val engine = EngineCore(EngineProgram(emptyList()))
        try {
            engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset, loop = true, loopCrossfadeFrames = 480)))
            engine.controls.offer(EngineCommand.PlayOriginalSource(0, 1))
            val buffer = FloatArray(384)
            repeat(BLOCKS) { engine.render(buffer) }
            val meter = allocationMeter()
            val measured = measure(meter) { engine.render(buffer) }
            val context = "Practice SOURCE loop: JDK=${System.getProperty("java.version")} VM=${System.getProperty("java.vm.name")} " +
                "OS=${System.getProperty("os.name")}/${System.getProperty("os.arch")} block=192 warmup=$BLOCKS measured=$BLOCKS " +
                "renderAllocatedBytes=${measured.renderBytes} wholeHarnessAllocatedBytes=${measured.harnessBytes} firstAllocatingRenderBlock=${measured.firstBlock}; synthetic JVM only"
            println(context)
            assertEquals(0L, measured.renderBytes, context)
            assertTrue(engine.originalPlaying, "The loop remains audible throughout the measurement")
        } finally { engine.close() }
    }

    @Test fun measurementSeparatesHarnessAllocationsAndStillDetectsEveryAllocatingRender() {
        val meter = allocationMeter()
        val outside = measure(meter, beforeRender = { allocationWitness = ByteArray(128) }) {}
        assertTrue(outside.harnessBytes >= BLOCKS * 128L, outside.toString())
        assertEquals(0L, outside.renderBytes, "Control-producer allocations must not be labelled render allocations: $outside")
        val inside = measure(meter) { allocationWitness = ByteArray(128) }
        assertTrue(inside.renderBytes >= BLOCKS * 128L, "An allocating render must fail the zero-byte contract: $inside")
        assertEquals(0, inside.firstBlock)
        println("Practice meter controls: outside=$outside inside=$inside")
        allocationWitness = null
    }

    private data class Meter(val bean: ThreadMXBean, val thread: Long)
    private data class Measurement(val renderBytes: Long, val harnessBytes: Long, val firstBlock: Int)

    private fun allocationMeter(): Meter {
        val bean = assertIs<ThreadMXBean>(ManagementFactory.getThreadMXBean(), "This JVM must expose thread allocation counters")
        assertTrue(bean.isThreadAllocatedMemorySupported, "Thread allocation counters are required")
        bean.isThreadAllocatedMemoryEnabled = true
        assertTrue(bean.isThreadAllocatedMemoryEnabled, "Thread allocation counters must be enabled")
        val thread = Thread.currentThread().id
        // Initialize the VM's measurement path separately from the engine warm-up.
        repeat(100_000) { bean.getThreadAllocatedBytes(thread) }
        assertTrue(bean.getThreadAllocatedBytes(thread) >= 0, "A negative counter is unavailable, not zero allocation")
        return Meter(bean, thread)
    }

    private inline fun measure(meter: Meter, beforeRender: () -> Unit = {}, render: () -> Unit): Measurement {
        val bean = meter.bean
        val thread = meter.thread
        var allocated = 0L
        var first = -1
        val before = bean.getThreadAllocatedBytes(thread)
        check(before >= 0) { "Thread allocation counter unavailable: $before" }
        repeat(BLOCKS) { block ->
            beforeRender()
            val renderBefore = bean.getThreadAllocatedBytes(thread)
            check(renderBefore >= 0) { "Thread allocation counter unavailable at block $block: $renderBefore" }
            render()
            val after = bean.getThreadAllocatedBytes(thread)
            val delta = after - renderBefore
            check(delta >= 0) { "Thread allocation counter invalid at block $block: before=$renderBefore after=$after delta=$delta" }
            allocated += delta
            if (delta != 0L && first < 0) first = block
        }
        val harness = bean.getThreadAllocatedBytes(thread) - before
        check(harness >= 0) { "Whole-harness allocation counter decreased: $harness" }
        return Measurement(allocated, harness, first)
    }

    private companion object { const val BLOCKS = 10_000 }
}
