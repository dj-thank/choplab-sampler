package com.choplab.jvm

import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The hosts' microphone: one take at a time into the asset store, sized to the room left, and nothing left behind. */
class VoiceTakesTest {
    @Test fun cancelledArmedStartReleasesALatePermissionResultWithoutPublishingATake() = runBlocking<Unit> {
        val store = FileAssetStore(Files.createTempDirectory("armed-permission-store-"))
        val scratch = Files.createTempDirectory("armed-permission-")
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mic = ScriptedMic()
        val takes = VoiceTakes(store, scratch) { opening.countDown(); release.await(5, TimeUnit.SECONDS); mic }
        val start = async(Dispatchers.Default) { takes.start(300, waitForCue = true) }
        assertTrue(opening.await(5, TimeUnit.SECONDS))
        start.cancel()
        release.countDown()
        start.join()
        assertNotNull(mic.closedBy)
        assertFalse(takes.cueAt(System.nanoTime()))
        assertNull(takes.stop("LATE"))
        assertEquals(0, store.storedBytes())
        assertEquals(0, Files.list(scratch).use { it.count() })
        takes.close()
    }
    @Test fun oneTakeAtATimeGoesToTheStore() = runBlocking<Unit> {
        val store = FileAssetStore(Files.createTempDirectory("choplab-takes-store-"))
        val scratch = Files.createTempDirectory("choplab-takes-")
        // A crash left a scratch file behind; a new session clears it and nothing else.
        Files.write(scratch.resolve("take-left.wav"), byteArrayOf(1, 2, 3, 4))
        Files.write(scratch.resolve("keep.txt"), byteArrayOf(1))
        val mics = ArrayDeque<ScriptedMic>()
        val takes = VoiceTakes(store, scratch) { mics.removeFirstOrNull() }
        assertEquals(listOf("keep.txt"), Files.list(scratch).use { files -> files.map { it.fileName.toString() }.toList() })

        assertEquals(VoiceTakes.Start.NO_INPUT, takes.start(10), "No microphone")
        assertNull(takes.stop("VOICE 1"), "Nothing ran")

        val mic = ScriptedMic().also { it.buffers.put(FloatArray(4_800) { .25f }) }
        mics += mic
        assertEquals(VoiceTakes.Start.STARTED, takes.start(10))
        takes.cue()
        assertFailsWith<IllegalStateException>("One take at a time") { takes.start(10) }
        withinSeconds(5) { mic.drained }
        Thread.sleep(20)
        assertFalse(takes.full || takes.interrupted)
        val take = assertNotNull(takes.stop("VOICE 1"))
        assertEquals("VOICE 1", take.asset.name)
        assertEquals(4_800L, take.asset.frames)
        assertTrue(store.verified(take.asset))
        assertNotNull(mic.closedBy, "The microphone is released")

        // A discarded take stores nothing; a microphone that fails to open reads as none.
        mics += ScriptedMic().also { it.buffers.put(FloatArray(480) { .5f }) }
        assertEquals(VoiceTakes.Start.STARTED, takes.start(10))
        takes.discard()
        assertNull(takes.stop("VOICE 2"))
        val failing = VoiceTakes(store, scratch) { error("Input in use") }
        assertEquals(VoiceTakes.Start.NO_INPUT, failing.start(10))
        assertEquals(listOf("keep.txt"), Files.list(scratch).use { files -> files.map { it.fileName.toString() }.toList() })
    }

    @Test fun aTakeIsSizedToTheRoomTheStoreAndTheDiskHave() = runBlocking<Unit> {
        val scratch = Files.createTempDirectory("choplab-takes-")
        // Room in the store for one second of 48 kHz float: the take stops there however long it was allowed.
        val small = FileAssetStore(Files.createTempDirectory("choplab-takes-store-"), maxStoredBytes = 44L + 4 * 48_000 + 100)
        val mic = ScriptedMic().also { repeat(12) { _ -> it.buffers.put(FloatArray(4_800) { .25f }) } }
        val takes = VoiceTakes(small, scratch) { mic }
        assertEquals(VoiceTakes.Start.STARTED, takes.start(300))
        withinSeconds(5) { takes.full }
        assertTrue(takes.full)
        assertEquals(48_000L, assertNotNull(takes.stop("VOICE 1")).asset.frames)
        // Now the store is full, and a disk short of its reserve has no room either: the microphone stays shut.
        var opened = 0
        assertEquals(VoiceTakes.Start.NO_ROOM, VoiceTakes(small, scratch) { opened++; ScriptedMic() }.start(10))
        val roomyStore = FileAssetStore(Files.createTempDirectory("choplab-takes-store-"))
        val fullDisk = VoiceTakes(roomyStore, scratch, diskReserveBytes = 64L shl 20, usableDiskBytes = { 64L shl 20 }) { opened++; ScriptedMic() }
        assertEquals(VoiceTakes.Start.NO_ROOM, fullDisk.start(10))
        assertEquals(0, opened)
    }

    @Test fun closingWhileATakeStartsLeavesTheMicrophoneShut() = runBlocking<Unit> {
        val store = FileAssetStore(Files.createTempDirectory("choplab-takes-store-"))
        val scratch = Files.createTempDirectory("choplab-takes-")
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mic = ScriptedMic()
        val takes = VoiceTakes(store, scratch) { opening.countDown(); release.await(5, TimeUnit.SECONDS); mic }
        val start = async(Dispatchers.Default) { takes.start(10) }
        assertTrue(opening.await(5, TimeUnit.SECONDS))
        // The editor closes while the platform is still opening the input.
        val closing = async(Dispatchers.Default) { takes.close() }
        closing.await()
        release.countDown()
        assertEquals(VoiceTakes.Start.NO_INPUT, start.await())
        withinSeconds(5) { mic.closedBy != null }
        assertNotNull(mic.closedBy, "The input that opened too late is released at once")
        assertEquals(VoiceTakes.Start.NO_INPUT, takes.start(10), "Nothing starts after close")
        assertEquals(0, Files.list(scratch).use { it.count() })
    }
}
