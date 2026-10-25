package com.choplab.apple

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

/** Runs the production composition root, actor and AVAudioEngine on the simulator. No fake output. */
class AppleStudioFlowTest {
    @Test fun productionBackendImportPlayExportArchiveAndAutosaveResume() = runBlocking {
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
                requireOutput(host)
                complete(host.studio, Action.Import(host.files.register(HostFile(sourcePath, "source.wav"))), Operation.IMPORT)
                val asset = host.studio.document.value.project.assets.single()
                assertEquals(sha256(originalBytes), asset.hash)
                edit(host, Intent.EqualChop(4))
                edit(host, Intent.AssignSlice(0, 0))
                val track = Track("song", "Song", TrackKind.BANK)
                val clip = Clip("part", track.id, asset.hash, FrameRange(0, asset.frames))
                edit(host, Intent.SetArrangement(frozenListOf(track), frozenListOf(clip), frozenListOf()))
                assertTrue(host.studio.dispatch(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement())).accepted)
                assertTrue(host.studio.dispatch(Action.Play).accepted)
                delay(60)
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
                requireOutput(host)
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

    private suspend fun requireOutput(host: IosBackend) {
        val status = withTimeout(15_000) { host.engine.status.first { it.phase != DriverPhase.STARTING } }
        assertEquals(DriverPhase.ATTACHED, status.phase, "The simulator must provide a real AVAudioEngine output route")
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
