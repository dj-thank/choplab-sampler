package com.choplab.jvm

import com.choplab.core.edit.Intent
import com.choplab.core.edit.Reducer
import com.choplab.core.model.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AudioRecoveryBoundaryTest {
    @Test fun preparedReceiptRecognizesOnlyTheExactPersistedVoiceDocumentAndOriginal() = runBlocking<Unit> {
        val root = Files.createTempDirectory("accepted-voice-")
        val store = FileAssetStore(root.resolve("assets"))
        val scratch = root.resolve("voice")
        val mic = ScriptedMic().also { it.buffers.put(FloatArray(4800) { .3f }) }
        val voice = VoiceTakes(store, scratch, durableTakes = true) { mic }
        assertEquals(VoiceTakes.Start.STARTED, voice.start(5))
        withTimeout(5000) { while (voice.recordedMillis < 100) delay(5) }
        val take = assertNotNull(voice.stop("VOICE"))
        val track = Track("vocal", "VOICE", TrackKind.VOCAL)
        val candidate = Take("take", track.id, take.asset.hash, FrameRange(0, take.asset.frames), 24_000)
        val base = Project()
        val accepted = Reducer.reduce(base, Intent.AddVoiceTake(take.asset, null,
            Clip("clip", track.id, take.asset.hash, candidate.range, timelineStartFrame = 24_000), track, candidate)).project
        voice.prepareAcceptance(accepted, 7) // Before any document save, including the automatic save.
        val original = Files.list(scratch).use { paths -> paths.filter { it.fileName.toString().endsWith(".wav") }.toList().single() }
        assertFalse(RecordingAcceptance.matches(original, base, 6), "Prepared but not committed is not accepted")
        val assetOnly = Reducer.reduce(base, Intent.ImportAsset(take.asset)).project
        assertFalse(RecordingAcceptance.matches(original, assetOnly, 7), "The same asset in SOURCE proves no voice edit")
        assertFalse(RecordingAcceptance.matches(original, accepted, 6), "A different document revision proves no commit")
        val autosave = AutosaveStore(root.resolve("autosave"), store)
        assertTrue(autosave.save(accepted, 7))
        voice.close() // Simulates process teardown without acknowledgement.
        val saved = assertNotNull(autosave.recover())
        val recovered = VoiceTakes(store, scratch, durableTakes = true, recoveredProject = saved.project, recoveredRevision = saved.revision) { error("No new input") }
        assertTrue(recovered.pendingSave)
        assertTrue(recovered.pendingAccepted)
        recovered.acknowledge()
        assertFalse(recovered.pendingSave)
        assertEquals(saved, autosave.recover(), "Cleanup does not replace SOURCE or append another take/revision")
        assertTrue(store.verified(take.asset))
        assertEquals(0, Files.list(scratch).use { it.count() })
        recovered.close()
    }

    @Test fun failedDiscardKeepsTheSameStoppedOriginalForBothSaveAndDiscardRetries() = runBlocking<Unit> {
        val root = Files.createTempDirectory("discard-retry-")
        val store = FileAssetStore(root.resolve("assets"))
        val memory = PcmMemoryBudget()
        var refuseDelete = true
        val mic = ScriptedMic().also { it.buffers.put(FloatArray(4800) { .2f }) }
        val voice = VoiceTakes(store, root.resolve("voice"), memory = memory, durableTakes = true,
            deleteRecovery = { if (refuseDelete) error("Synthetic delete failure") else Files.deleteIfExists(it) }) { mic }
        assertEquals(VoiceTakes.Start.STARTED, voice.start(5))
        withTimeout(5000) { while (voice.recordedMillis < 100) delay(5) }
        val first = assertNotNull(voice.stop("original"))
        assertFailsWith<IllegalStateException> { voice.discard() }
        assertTrue(voice.pendingSave)
        assertNotNull(mic.closedBy)
        assertEquals(0, memory.statistics().usedBytes)
        assertEquals(first.asset.hash, assertNotNull(voice.stop("retry")).asset.hash)
        refuseDelete = false
        voice.discard()
        assertFalse(voice.pendingSave)
        assertFalse(voice.inputBusy)
        assertTrue(store.verified(first.asset))
        assertEquals(0, Files.list(root.resolve("voice")).use { it.count() })
        voice.close()
    }

    @Test fun outerDeadlineCancelsItsNativeWorkerClosesLateInputAndAllowsANewAttempt() = runBlocking<Unit> {
        val root = Files.createTempDirectory("input-timeout-")
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val cancellations = AtomicInteger()
        val late = ScriptedMic()
        val voice = VoiceTakes(FileAssetStore(root.resolve("assets")), root.resolve("voice"),
            openTimeoutMillis = 80, cancelNativeOpening = { cancellations.incrementAndGet(); release.countDown() }) {
            if (calls.incrementAndGet() == 1) { release.await(3, TimeUnit.SECONDS); late } else ScriptedMic()
        }
        assertEquals(VoiceTakes.Start.NO_INPUT, voice.start(5))
        assertEquals(InputOpeningFailure.TIMEOUT, voice.openingFailure)
        withTimeout(3000) { while (voice.inputBusy || late.closedBy == null) delay(2) }
        assertEquals(1, cancellations.get())
        assertEquals(VoiceTakes.Start.STARTED, voice.start(5))
        assertEquals(2, calls.get())
        voice.discard(); voice.close()
    }

    @Test fun cancellationKeepsItsWorkerSlotUntilItsNativeHookCompletes() = runBlocking<Unit> {
        val entered = CountDownLatch(1); val releaseFactory = CountDownLatch(1)
        val nativeEntered = CountDownLatch(1); val releaseCancellation = CountDownLatch(1)
        val calls = AtomicInteger()
        val opener = CancellableInputOpener({ calls.incrementAndGet(); entered.countDown(); releaseFactory.await(3, TimeUnit.SECONDS); ScriptedMic() },
            cancelNative = { releaseFactory.countDown(); nativeEntered.countDown(); releaseCancellation.await(3, TimeUnit.SECONDS) })
        val opening = async(Dispatchers.IO) { opener.open() }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val cancellation = async(Dispatchers.IO) { opener.cancel() }
        assertTrue(nativeEntered.await(3, TimeUnit.SECONDS))
        assertNull(withTimeout(500) { opening.await() })
        assertTrue(opener.busy)
        assertNull(opener.open())
        assertEquals(1, calls.get(), "The old cancellation must not target a new helper")
        releaseCancellation.countDown(); cancellation.await()
        withTimeout(3000) { while (opener.busy) delay(2) }
        assertNotNull(opener.open()).close()
        assertEquals(2, calls.get())
    }

    @Test fun stereoMetersDistinguishBothSidesWithoutChangingAnyStoredSample() = runBlocking<Unit> {
        for ((left, right) in listOf(.5f to 0f, 0f to .5f)) {
            val root = Files.createTempDirectory("stereo-meter-")
            val samples = FloatArray(2048) { if (it % 2 == 0) left else right }
            val mic = object : MicInput {
                override val sampleRate = 48_000
                override val channels = 2
                var delivered = false
                @Volatile var stopped = false
                override fun read(buffer: FloatArray): Int {
                    if (!delivered) { delivered = true; samples.copyInto(buffer); return samples.size }
                    while (!stopped) Thread.sleep(2)
                    return -1
                }
                override fun stop() { stopped = true }
                override fun close() { stopped = true }
            }
            val store = FileAssetStore(root.resolve("assets"))
            val voice = VoiceTakes(store, root.resolve("voice"), captureChannels = 2, durableTakes = true) { mic }
            assertEquals(VoiceTakes.Start.STARTED, voice.start(5))
            withTimeout(5000) { while (voice.recordedMillis == 0L) delay(2) }
            assertEquals(2, voice.inputChannels); assertEquals(left, voice.leftPeak); assertEquals(right, voice.rightPeak)
            val take = assertNotNull(voice.stop("stereo"))
            assertContentEquals(samples, store.openVerified(take.asset).use { WavCodec.read(it).samples })
            assertNull(voice.interruption, "An explicit stop is not an interrupted input")
            voice.acknowledge(); voice.close()
        }
    }
}
