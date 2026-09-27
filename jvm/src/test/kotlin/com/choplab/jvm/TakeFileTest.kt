package com.choplab.jvm

import com.choplab.core.model.AssetRole
import java.nio.file.Files
import kotlin.test.*

/** A recorded take streams to a scratch file and becomes a verified float WAV asset. */
class TakeFileTest {
    @Test fun aTakeBecomesAVerifiedFloatWavAndLeavesNoScratch() {
        val scratch = Files.createTempDirectory("choplab-take-")
        val store = FileAssetStore(Files.createTempDirectory("choplab-take-store-"))
        val take = TakeFile(scratch, 48_000, 1, maxFrames = 25_000)
        val block = FloatArray(10_000) { (it % 200 - 100) / 100f }
        take.write(block, 10_000)
        take.write(FloatArray(4) { if (it == 1) Float.NaN else .25f }, 4)
        take.write(block, 10_000)
        assertFalse(take.full)
        take.write(block, 10_000)
        assertTrue(take.full, "The take stops growing at its limit")
        take.write(block, 10_000)
        assertEquals(25_000, take.frames)

        val asset = assertNotNull(take.finish(store, "VOICE 1"))
        assertEquals(AssetRole.ORIGINAL, asset.role)
        assertEquals(listOf(48_000, 1), listOf(asset.sampleRate, asset.channels))
        assertEquals(25_000L, asset.frames)
        assertTrue(store.verified(asset))
        val audio = store.openVerified(asset).use { WavCodec.read(it) }
        assertEquals(WavInfo(48_000, 1, 25_000, 32, true), audio.info)
        assertContentEquals(block, audio.samples.copyOfRange(0, 10_000))
        assertContentEquals(floatArrayOf(.25f, 0f, .25f, .25f), audio.samples.copyOfRange(10_000, 10_004), "Not-a-number is silence")
        assertContentEquals(block, audio.samples.copyOfRange(10_004, 20_004))
        assertContentEquals(block.copyOfRange(0, 4_996), audio.samples.copyOfRange(20_004, 25_000), "What did not fit is dropped")
        assertEquals(0, Files.list(scratch).use { it.count() }, "No scratch file is left")
    }

    @Test fun aTakeTheStoreHasNoRoomForLeavesNothingBehind() {
        val scratch = Files.createTempDirectory("choplab-take-")
        val store = FileAssetStore(Files.createTempDirectory("choplab-take-store-"), maxStoredBytes = 1_000)
        val take = TakeFile(scratch, 48_000, 1, maxFrames = 1_000)
        take.write(FloatArray(1_000) { .5f }, 1_000)
        assertFailsWith<IllegalArgumentException>("4 044 bytes do not fit in 1 000") { take.finish(store, "VOICE 1") }
        assertEquals(0L, store.storedBytes())
        assertEquals(0, Files.list(scratch).use { it.count() })

        // A take identical to one already stored needs no second copy.
        val roomy = FileAssetStore(Files.createTempDirectory("choplab-take-store-"))
        val first = TakeFile(scratch, 48_000, 1, maxFrames = 10).apply { write(FloatArray(10) { .5f }, 10) }.finish(roomy, "VOICE 1")
        val second = TakeFile(scratch, 48_000, 1, maxFrames = 10).apply { write(FloatArray(10) { .5f }, 10) }.finish(roomy, "VOICE 1")
        assertEquals(first, second)
        assertEquals(44L + 40, roomy.storedBytes())
        assertEquals(0, Files.list(scratch).use { it.count() })
    }

    @Test fun nothingRecordedStoresNothingAndDiscardCleansUp() {
        val scratch = Files.createTempDirectory("choplab-take-")
        val store = FileAssetStore(Files.createTempDirectory("choplab-take-store-"))
        assertNull(TakeFile(scratch, 48_000, 1, 100).finish(store, "VOICE 1"))
        val dropped = TakeFile(scratch, 48_000, 2, 100)
        dropped.write(FloatArray(8) { .5f }, 8)
        assertFailsWith<IllegalArgumentException>("Whole frames only") { dropped.write(FloatArray(3), 3) }
        dropped.discard()
        assertEquals(0, Files.list(scratch).use { it.count() })
    }
}
