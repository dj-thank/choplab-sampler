package com.choplab.jvm

import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DurableVoiceTakesTest {
    @Test fun failedPublicationRetainsBytesAndReleasesMemoryUntilExplicitAcceptance() = runBlocking<Unit> {
        val scratch = Files.createTempDirectory("retained-take-")
        val small = FileAssetStore(Files.createTempDirectory("small-store-"), maxStoredBytes = 100)
        val roomy = FileAssetStore(Files.createTempDirectory("roomy-store-"))
        val memory = PcmMemoryBudget()
        val take = TakeFile(scratch, 48_000, 2, 1000, memory.reserve(TakeFile.MEMORY_BYTES))
        val samples = FloatArray(2000) { if (it % 2 == 0) .25f else -.75f }
        take.write(samples, samples.size)
        assertFailsWith<IllegalArgumentException> { take.finishRetaining(small, "take") }
        assertEquals(0, memory.statistics().usedBytes)
        val file = Files.list(scratch).use { it.toList().single() }
        val original = Files.readAllBytes(file)
        val asset = assertNotNull(take.finishRetaining(roomy, "take"))
        assertContentEquals(original, Files.readAllBytes(file))
        assertContentEquals(samples, roomy.openVerified(asset).use { WavCodec.read(it).samples })
        assertEquals(asset.hash, assertNotNull(take.finishRetaining(roomy, "retry")).hash)
        take.discard()
        assertFalse(Files.exists(file))
        assertTrue(roomy.verified(asset), "Discard removes recovery ownership, never shared assets")
    }

    @Test fun publishingWithoutDocumentAcknowledgementSurvivesRestart() = runBlocking<Unit> {
        val scratch = Files.createTempDirectory("recovered-take-")
        val store = FileAssetStore(Files.createTempDirectory("recovered-store-"))
        val mic = ScriptedMic().also { it.buffers.put(FloatArray(4800) { .5f }) }
        val takes = VoiceTakes(store, scratch, durableTakes = true) { mic }
        assertEquals(VoiceTakes.Start.STARTED, takes.start(10))
        withinSeconds(5) { takes.recordedMillis == 100L }
        assertEquals(.5f, takes.peakLevel)
        val first = assertNotNull(takes.stop("voice"))
        assertTrue(takes.pendingSave)
        assertEquals(100, takes.recordedMillis)
        assertEquals(VoiceTakes.Start.NO_INPUT, takes.start(10))
        takes.close()
        val reopened = VoiceTakes(store, scratch, durableTakes = true) { error("No recording during recovery") }
        assertTrue(reopened.pendingSave)
        assertEquals(first.asset.hash, assertNotNull(reopened.stop("recovered")).asset.hash)
        reopened.acknowledge()
        assertFalse(reopened.pendingSave)
        assertEquals(0, Files.list(scratch).use { it.count() })
        assertTrue(store.verified(first.asset))
        reopened.close()
    }

    @Test fun crashOriginalRecoversOnlyWholeCommittedFramesAndInvalidRemnantsRequireDiscard() = runBlocking<Unit> {
        val scratch = Files.createTempDirectory("crash-take-")
        val store = FileAssetStore(Files.createTempDirectory("crash-store-"))
        val raw = scratch.resolve("take-000.wav")
        Files.newOutputStream(raw).use { out ->
            WavCodec.floatHeader(out, 0, 44_100, 2)
            val bits = java.nio.ByteBuffer.allocate(800).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(100) { bits.putFloat(.125f); bits.putFloat(-.25f) }
            out.write(bits.array())
        }
        val invalid = scratch.resolve("take-001.wav")
        Files.write(invalid, byteArrayOf(1, 2, 3))
        val recovered = VoiceTakes(store, scratch, durableTakes = true) { null }
        val asset = assertNotNull(recovered.stop("recovered")).asset
        assertEquals(100, asset.frames)
        assertEquals(44_100, asset.sampleRate)
        assertEquals(2, asset.channels)
        recovered.acknowledge()
        assertTrue(recovered.pendingSave)
        assertFailsWith<IllegalArgumentException> { recovered.stop("invalid") }
        assertTrue(Files.exists(invalid))
        recovered.discard()
        assertFalse(Files.exists(invalid))
        assertFalse(recovered.pendingSave)
        recovered.close()
    }

    @Test fun cancelReturnsWhileNativeOpenIsBlockedAndOwnsItsLateResult() = runBlocking<Unit> {
        val store = FileAssetStore(Files.createTempDirectory("cancel-store-"))
        val scratch = Files.createTempDirectory("cancel-take-")
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val mic = ScriptedMic()
        val takes = VoiceTakes(store, scratch, durableTakes = true) {
            count.incrementAndGet(); opening.countDown(); release.await(5, TimeUnit.SECONDS); mic
        }
        val start = async(Dispatchers.Default) { takes.start(10) }
        assertTrue(opening.await(5, TimeUnit.SECONDS))
        takes.cancelOpening()
        assertEquals(VoiceTakes.Start.NO_INPUT, withTimeout(500) { start.await() })
        assertEquals(VoiceTakes.Start.NO_INPUT, takes.start(10))
        assertEquals(1, count.get())
        release.countDown()
        withinSeconds(5) { mic.closedBy != null }
        assertFalse(takes.pendingSave)
        assertEquals(0, Files.list(scratch).use { it.count() })
        takes.close()
    }

    @Test fun availableDiskShortensTheAdvertisedLimitBeforeCapture() = runBlocking<Unit> {
        val store = FileAssetStore(Files.createTempDirectory("limited-store-"))
        val takes = VoiceTakes(store, Files.createTempDirectory("limited-take-"), diskReserveBytes = 0,
            usableDiskBytes = { 44L + 48_000 * 4 * 2 }, durableTakes = true) { ScriptedMic() }
        assertEquals(VoiceTakes.Start.STARTED, takes.start(300))
        assertEquals(2000, takes.limitMillis)
        takes.discard()
        takes.close()
    }
}
