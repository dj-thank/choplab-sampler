package com.choplab.apple

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

/** Production actor/storage/export always run. Real output, or its explicit refusal, is recorded separately. */
class AppleStudioFlowTest {
    @Test fun productionBackendImportExportArchiveAndAutosaveResume(): Unit = runBlocking {
        withTimeout(60_000) {
            val directory = IosPaths.temporary() + "/" + uniqueName("studio-flow-")
            ensureDirectory(directory)
            val fixture = ArrayOutput()
            IosWav.FloatWriter(fixture, 96_000).apply {
                write(FloatArray(192_000) { i ->
                    val wave = sin(2 * PI * 440 * (i / 2) / 48_000).toFloat()
                    if (i % 2 == 0) wave * .3f else wave * -.15f
                }); finish()
            }
            val originalBytes = fixture.toByteArray()
            val sourcePath = "$directory/source.wav"
            FileWriter(sourcePath).use { it.write(originalBytes); it.syncAndClose() }
            val savedPath = "$directory/created.choplab"
            var saved: Project? = null
            var host = IosBackend.create("$directory/profile")
            try {
                val outputAvailable = inspectOutput(host, "initial")
                complete(host.studio, Action.Import(host.files.register(HostFile(sourcePath, "source.wav"))), Operation.IMPORT)
                val asset = host.studio.document.value.project.assets.single()
                assertEquals(sha256(originalBytes), asset.hash)
                edit(host, Intent.EqualChop(4))
                edit(host, Intent.AssignSlice(0, 0))
                val track = Track("song", "Song", TrackKind.BANK)
                val clip = Clip("part", track.id, asset.hash, FrameRange(0, asset.frames))
                edit(host, Intent.SetArrangement(frozenListOf(track), frozenListOf(clip), frozenListOf()))
                assertTrue(host.studio.dispatch(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement())).accepted)
                if (outputAvailable) {
                    val consumedBefore = requireNotNull(host.engine.outputConsumedFrames())
                    assertTrue(host.studio.dispatch(Action.Play).accepted)
                    withTimeout(5_000) {
                        while ((host.engine.outputConsumedFrames() ?: 0) <= consumedBefore + 1024) delay(10)
                    }
                    assertTrue(host.engine.snapshot().playing)
                    assertTrue(host.engine.snapshot().sequenceFrame > 0)
                    assertTrue(host.engine.health().measuredBlocks > 0)
                    assertTrue(host.audition.play(asset))
                    assertTrue(host.audition.scratchStart(asset, 1000, 0, asset.frames))
                    assertTrue(host.audition.scratchTo(2400.0, 512))
                    assertTrue(host.audition.scratchEnd())
                    assertTrue(host.engine.originalPlayback().playing)
                    assertTrue(host.engine.snapshot().playing)
                    assertTrue(host.audition.pause())
                    recordOutput(host, "playback", "verified-real-output")
                } else {
                    assertEquals(Rejection.ENGINE_REFUSED, (host.studio.dispatch(Action.Play).notice as? Notice.Rejected)?.reason)
                    assertFalse(host.engine.snapshot().playing)
                    assertFalse(host.engine.snapshot().outputAttached)
                    assertNull(host.engine.outputConsumedFrames())
                    assertFalse(host.audition.play(asset))
                    recordOutput(host, "playback", "unavailable-refusal-verified")
                }
                assertTrue(host.studio.dispatch(Action.Stop).accepted)

                val wavPath = "$directory/song.wav"
                complete(host.studio, Action.Export(ExportRequest(host.files.register(HostFile(wavPath, "song.wav")),
                    96_000, bits = 24, tailMode = ExportTailMode.EXACT), PlaybackTarget.Arrangement()), Operation.EXPORT)
                val wav = FileReader(wavPath).use { IosWav.read(it.asInput()) }
                assertEquals(24, wav.info.bits)
                assertEquals(96_000L, wav.info.frames)
                assertTrue(wav.samples.any { it > .01f })
                assertTrue(wav.samples.filterIndexed { i, _ -> i % 2 == 1 }.any { it < -.01f })
                complete(host.studio, Action.Save(host.files.register(HostFile(savedPath, "created.choplab"))), Operation.SAVE)
                saved = host.studio.document.value.project
                host.flushAutosave()
                assertFalse(host.persistenceFailure.value)
                assertContentEquals(originalBytes, host.assets.read(asset))
            } finally { host.shutdown() }

            host = IosBackend.create("$directory/profile")
            try {
                inspectOutput(host, "restart")
                assertEquals(saved, host.studio.document.value.project)
                assertFalse(host.studio.document.value.canUndo)
                edit(host, Intent.Rename("Changed after restart"))
                assertTrue(host.studio.dispatch(Action.Undo).accepted)
                assertEquals(saved, host.studio.document.value.project)
                assertTrue(host.studio.dispatch(Action.Redo).accepted)
                assertEquals("Changed after restart", host.studio.document.value.project.title)
                complete(host.studio, Action.Open(host.files.register(HostFile(savedPath, "created.choplab"))), Operation.OPEN)
                assertEquals(saved, host.studio.document.value.project)
                assertFalse(host.studio.document.value.canUndo)
                assertContentEquals(originalBytes, host.assets.read(host.studio.document.value.project.assets.single()))
            } finally { host.shutdown() }
        }
    }

    private suspend fun inspectOutput(host: IosBackend, phase: String): Boolean {
        val status = withTimeout(15_000) { host.engine.status.first { it.phase != DriverPhase.STARTING } }
        val capability = assertNotNull(host.engine.lastOutputCapability())
        recordOutput(host, phase, "not-yet-exercised")
        assertNotEquals(AudioOutputStage.UNEXPECTED_FAILURE, capability.stage, "Unexpected driver error is not an unavailable route")
        assertNotEquals(AudioOutputStage.DISABLED, capability.stage, "Production backend must attempt the real output")
        return if (status.phase == DriverPhase.ATTACHED) {
            assertEquals(AudioOutputStage.READY, capability.stage)
            true
        } else {
            assertEquals(DriverPhase.EDITING_ONLY, status.phase)
            assertEquals(DriverFault.NO_OUTPUT, status.fault)
            assertNotEquals(AudioOutputStage.READY, capability.stage)
            false
        }
    }
    private fun recordOutput(host: IosBackend, phase: String, playback: String) {
        println("CHOPLAB_AUDIO_CAPABILITY " + buildJsonObject {
            put("context", "native-test-runner"); put("phase", phase); put("livePlayback", playback)
            put("driverPhase", host.engine.status.value.phase.name)
            put("fault", host.engine.status.value.fault.name)
            put("capability", assertNotNull(host.engine.lastOutputCapability()).json())
            host.engine.outputConsumedFrames()?.let { put("consumedFrames", it) }
            put("physicalSoundVerified", false)
        })
    }
    private suspend fun edit(host: IosBackend, intent: Intent) {
        assertTrue(host.studio.dispatch(Action.Edit(intent)).accepted)
    }
    private suspend fun complete(studio: Studio, action: Action, operation: Operation) = coroutineScope {
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            studio.notices.first { it == Notice.Completed(operation) || it == Notice.Failed(operation) || it == Notice.Cancelled(operation) }
        }
        assertTrue(studio.dispatch(action).accepted)
        assertEquals(Notice.Completed(operation), result.await())
    }
}
