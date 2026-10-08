package com.choplab.jvm

import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class VocalRecordingMemoryTest {
    @Test fun recordingReservesBeforeInputAndReturnsOnlyAfterFileFinishOrFailure() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("record-ledger-")
        val memory = PcmMemoryBudget(VoiceRecorder.MEMORY_BYTES)
        val store = FileAssetStore(directory.resolve("assets"))
        var opened = 0
        val input = object : MicInput {
            override val sampleRate = 48_000
            private var frames = 0
            override fun read(buffer: FloatArray): Int {
                if (frames >= 4800) return -1
                val count = minOf(buffer.size, 4800 - frames); buffer.fill(.25f, 0, count); frames += count; return count
            }
            override fun stop() = Unit
            override fun close() = Unit
        }
        val takes = VoiceTakes(store, directory.resolve("scratch"), memory = memory) { opened++; input }
        try {
            memory.reserve(8).use {
                assertEquals(VoiceTakes.Start.NO_ROOM, takes.start(1))
                assertEquals(0, opened, "Memory refusal precedes microphone acquisition")
            }
            assertEquals(VoiceTakes.Start.STARTED, takes.start(1))
            withTimeout(5000) { while (!takes.interrupted) delay(1) }
            assertEquals(VoiceRecorder.MEMORY_BYTES, memory.statistics().usedBytes, "Input close is not WAV completion")
            val take = assertNotNull(takes.stop("Candidate"))
            assertEquals(4800, take.asset.frames)
            assertEquals(0, memory.statistics().usedBytes)
            assertTrue(store.verified(take.asset))
            assertEquals(VoiceRecorder.MEMORY_BYTES, memory.statistics().peakBytes)
        } finally { takes.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun nonCooperativeInputKeepsItsSlotFileAndBudgetUntilTheCaptureThreadReallyEnds() = runBlocking<Unit> {
        nonCooperativeDiscard(durable = false)
    }

    @Test fun durableDiscardWaitsForCaptureCleanupBeforeReleasingItsPendingOriginalAndSlot() = runBlocking<Unit> {
        nonCooperativeDiscard(durable = true)
    }

    private suspend fun nonCooperativeDiscard(durable: Boolean) {
        val directory = Files.createTempDirectory("record-stuck-")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val memory = PcmMemoryBudget(VoiceRecorder.MEMORY_BYTES * 2)
        val store = FileAssetStore(directory.resolve("assets"))
        val scratch = directory.resolve("scratch")
        var opened = 0
        val takes = VoiceTakes(store, scratch, memory = memory, durableTakes = durable) {
            opened++
            object : MicInput {
                override val sampleRate = 48_000
                override fun read(buffer: FloatArray): Int { entered.countDown(); release.await(); return -1 }
                override fun stop() = Unit
                override fun close() = Unit
            }
        }
        try {
            assertEquals(VoiceTakes.Start.STARTED, takes.start(1))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { takes.discard() }
            assertEquals(VoiceRecorder.MEMORY_BYTES, memory.statistics().usedBytes)
            assertEquals(1, Files.list(scratch).use { it.count() })
            assertFalse(takes.pendingSave, "A discarding live capture is not a stopped original available for saving")
            assertTrue(takes.inputBusy)
            assertNull(takes.stop("Already stopping"))
            assertEquals(VoiceTakes.Start.NO_INPUT, takes.start(1))
            assertEquals(1, opened, "An uncooperative old microphone still owns the only recording slot")
            release.countDown()
            withTimeout(5000) { while (memory.statistics().usedBytes != 0L) delay(1) }
            assertEquals(0, Files.list(scratch).use { it.count() })
            assertEquals(0, store.storedBytes())
            withTimeout(5000) { while (takes.inputBusy) delay(1) }
            assertFalse(takes.pendingSave)
            assertNull(takes.stop("Already discarded"))
            assertEquals(VoiceTakes.Start.STARTED, takes.start(1))
            assertEquals(2, opened, "Only the completed old capture releases its slot for a new input")
            takes.discard()
            assertEquals(0, memory.statistics().usedBytes)
        } finally { release.countDown(); takes.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun constructorFailureReturnsAReservationBeforeAllocatingAnyRecording() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("record-file-fail-")
        val memory = PcmMemoryBudget(VoiceRecorder.MEMORY_BYTES)
        val scratch = Files.write(directory.resolve("not-a-directory"), byteArrayOf(1))
        val reservation = memory.reserve(TakeFile.MEMORY_BYTES)
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { TakeFile(scratch, 48000, 1, 48000, reservation) }
        assertEquals(0, memory.statistics().usedBytes)
        assertContentEquals(byteArrayOf(1), Files.readAllBytes(scratch))
        directory.toFile().deleteRecursively()
    }
    @Test fun aSuspendingInputOpenerReturnsTypedBudgetRefusalAndCancelsWithoutOpeningCapture() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("record-open-memory-")
        val memory = PcmMemoryBudget(VoiceRecorder.MEMORY_BYTES)
        val store = FileAssetStore(directory.resolve("assets"))
        val tooLarge = VoiceTakes(store, directory.resolve("scratch"), memory = memory) { memory.reserve(8); error("Not admitted") }
        try {
            assertEquals(VoiceTakes.Start.NO_ROOM, tooLarge.start(1))
            assertEquals(0L, memory.statistics().usedBytes)
        } finally { tooLarge.close() }
        val opening = CompletableDeferred<Unit>()
        val waiting = VoiceTakes(store, directory.resolve("scratch"), memory = memory) { opening.complete(Unit); awaitCancellation() }
        try {
            val start = async { waiting.start(1) }
            opening.await()
            assertEquals(VoiceRecorder.MEMORY_BYTES, memory.statistics().usedBytes)
            start.cancelAndJoin()
            assertEquals(0L, memory.statistics().usedBytes)
            assertEquals(0L, store.storedBytes())
        } finally { waiting.close(); directory.toFile().deleteRecursively() }
    }

}
