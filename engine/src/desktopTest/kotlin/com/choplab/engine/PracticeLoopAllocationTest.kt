package com.choplab.engine

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.*

class PracticeLoopAllocationTest {
    @Test fun loopingSourceAllocatesNothingDuringTenThousandRenderBlocks() {
        val asset = PcmAsset.fromInterleaved(FloatArray(4_802) { if (it % 2 == 0) .1f else -.03f })
        val engine = EngineCore(EngineProgram(emptyList()))
        try {
            engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset, loop = true, loopCrossfadeFrames = 480)))
            engine.controls.offer(EngineCommand.PlayOriginalSource(0, 1))
            val buffer = FloatArray(384)
            repeat(10_000) { engine.render(buffer) }
            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            bean.isThreadAllocatedMemoryEnabled = true
            val thread = Thread.currentThread().id
            repeat(100_000) { bean.getThreadAllocatedBytes(thread) }
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { engine.render(buffer) }
            val bytes = bean.getThreadAllocatedBytes(thread) - before
            println("Practice SOURCE loop: block=192, 10000 measured blocks, renderAllocatedBytes=$bytes; synthetic JVM only")
            assertEquals(0L, bytes)
        } finally { engine.close() }
    }
}
