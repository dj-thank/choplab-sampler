package com.choplab.desktop.next

import com.choplab.desktop.audio.FakeSystemAudioHelper
import com.choplab.jvm.FileAssetStore
import com.choplab.jvm.VoiceTakes
import com.choplab.jvm.WavCodec
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class MacSystemInputTest {
    @Test fun stereoFloatCaptureKeepsEverySampleAndEndsAtItsFrameBudget() = runBlocking<Unit> {
        val factory = MacSystemInput({ File("synthetic") }, FakeSystemAudioHelper.launcher("float"))
        val store = FileAssetStore(Files.createTempDirectory("system-store-"), maxStoredBytes = 44 + 48_000L * 8)
        val scratch = Files.createTempDirectory("system-scratch-")
        val takes = VoiceTakes(store, scratch, captureChannels = 2, microphone = factory::open)
        try {
            assertEquals(VoiceTakes.Start.STARTED, takes.start(300))
            withTimeout(5_000) { while (!takes.full) delay(5) }
            assertEquals(1_000L, takes.recordedMillis)
            val asset = assertNotNull(takes.stop("SYSTEM 1")).asset
            assertEquals(48_000L, asset.frames)
            assertEquals(2, asset.channels)
            val audio = store.openVerified(asset).use { WavCodec.read(it) }
            repeat(48_000) { frame ->
                assertEquals(.1234567f + frame / 1_000_000f, audio.samples[frame * 2])
                assertEquals(-.2345678f - frame / 1_000_000f, audio.samples[frame * 2 + 1])
            }
            assertEquals(VoiceTakes.Start.NO_ROOM, takes.start(300))
            assertEquals(0L, Files.list(scratch).use { it.count() })
        } finally { takes.close(); factory.close() }
    }

    @Test fun earlyExitAndStalledInputKeepCompleteStereoFramesAndReleaseTheHelper() = runBlocking<Unit> {
        for (mode in listOf("float-dies", "float")) {
            val child = AtomicReference<Process>()
            val factory = MacSystemInput({ File("synthetic") }, {
                FakeSystemAudioHelper.launcher(mode)(it).also(child::set)
            }, idleTimeoutMillis = 100)
            val store = FileAssetStore(Files.createTempDirectory("system-interrupted-"))
            val takes = VoiceTakes(store, Files.createTempDirectory("system-scratch-"),
                captureChannels = 2, microphone = factory::open)
            try {
                assertEquals(VoiceTakes.Start.STARTED, takes.start(30))
                withTimeout(5_000) { while (!takes.interrupted) delay(5) }
                val asset = assertNotNull(takes.stop("SYSTEM interrupted")).asset
                assertEquals(48_000L, asset.frames)
                assertEquals(2, asset.channels)
                assertFalse(takes.full)
                withTimeout(2_000) { while (child.get().isAlive) delay(5) }
            } finally { takes.close(); factory.close() }
        }
    }

    @Test fun permissionDisplayTimeoutAndCancellationAreDistinctAndKillOnlyTheOwnedHelper() = runBlocking<Unit> {
        for ((mode, reason) in listOf("unavailable" to MacSystemInput.Failure.UNAVAILABLE, "refused" to MacSystemInput.Failure.DENIED, "no-display" to MacSystemInput.Failure.NO_DISPLAY,
                "normal" to MacSystemInput.Failure.INVALID, "late" to MacSystemInput.Failure.TIMEOUT)) {
            val child = AtomicReference<Process>()
            val factory = MacSystemInput({ File("synthetic") }, { FakeSystemAudioHelper.launcher(mode, "10000")(it).also(child::set) },
                headerTimeoutMillis = if (mode == "late") 1_000 else 5_000)
            try {
                assertNull(factory.open())
                assertEquals(reason, factory.failure)
                withTimeout(2_000) { while (child.get().isAlive) delay(5) }
            } finally { factory.close() }
        }
        val child = AtomicReference<Process>()
        val factory = MacSystemInput({ File("synthetic") }, { FakeSystemAudioHelper.launcher("late", "10000")(it).also(child::set) })
        try {
            val opening = async(Dispatchers.IO) { factory.open() }
            withTimeout(5_000) { while (child.get() == null) delay(5) }
            factory.cancelOpening()
            assertNull(withTimeout(2_000) { opening.await() })
            assertEquals(MacSystemInput.Failure.CANCELLED, factory.failure)
            withTimeout(2_000) { while (child.get().isAlive) delay(5) }
        } finally { factory.close() }
    }
}
