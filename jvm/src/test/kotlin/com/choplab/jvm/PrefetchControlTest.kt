package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.Asset
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

class PrefetchControlTest {
    @Test fun aLateWarmCannotOverrideANewerSeekReleaseOrClose() = runBlocking<Unit> {
        val frames = 400 * 48_000
        val pages = PagedPcm(frames)
        val data = PcmAsset.paged(pages)
        val gate = AtomicReference<Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>?>()
        val port = object : PrefetchPcmPort {
            override fun residentBytes(asset: Asset) = data.residentBytes
            override suspend fun load(asset: Asset) = data
            override suspend fun <T> prepared(windows: List<PcmWindow>, render: () -> T): T = render()
            override suspend fun prefetch(pcm: PcmAsset, firstFrame: Int, endFrame: Int) {
                gate.getAndSet(null)?.let { (entered, released) ->
                    // An uncancellable codec completion still cannot issue a stale transport command.
                    withContext(NonCancellable) { entered.complete(Unit); released.await() }
                }
                for (page in firstFrame / pages.pageFrames..(endFrame - 1) / pages.pageFrames) {
                    val count = minOf(pages.pageFrames, frames - page * pages.pageFrames)
                    pages.publish(page, FloatArray(count * 2) { if (it % 2 == 0) .1f else -.2f })
                }
            }
        }
        val driver = StreamingEnginePort(ProgramCompiler(port), { object : AudioSink {
            override val encoding = SinkEncoding.FLOAT32
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int = length.also {
                LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000 / 48_000)
            }
            override fun close() = Unit
        } })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = SourceAuditionController(driver, port, scope)
        val asset = Asset("c".repeat(64), "wav", frames.toLong() * 8 + 44, 48_000, 2, frames.toLong(), "long")
        suspend fun await(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(2) }
        try {
            await { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(source.seek(asset, 48_000))
            for (exit in 0..2) {
                val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
                gate.set(entered to released)
                val pending = async {
                    runCatching { if (exit == 0) source.seek(asset, 390L * 48_000)
                        else source.scratchStart(asset, 375L * 48_000, 374L * 48_000, 376L * 48_000) }
                }
                try {
                    withTimeout(5_000) { entered.await() }
                    when (exit) {
                        0 -> assertTrue(source.seek(asset, 5L * 48_000))
                        1 -> assertTrue(source.scratchEnd())
                        else -> source.close()
                    }
                    released.complete(Unit)
                    assertFalse(withTimeout(5_000) { pending.await() }.getOrDefault(false))
                    assertEquals(5L * 48_000, source.nativeFrame().takeIf { exit != 2 } ?: driver.originalPlayback().sourceFrame)
                    assertFalse(driver.originalPlayback().playing)
                    assertEquals(-1.0, driver.handPlayback().sourceFrame)
                } finally { released.complete(Unit); pending.cancelAndJoin() }
            }
        } finally { source.close(); scope.cancel(); driver.close(); pages.close() }
    }
}
