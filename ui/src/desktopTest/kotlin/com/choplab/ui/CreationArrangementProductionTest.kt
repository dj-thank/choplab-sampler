package com.choplab.ui

import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.engine.*
import com.choplab.jvm.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

/** Uses the actual Studio prepare/publish transaction and archive ports, without opening an audio device. */
class CreationArrangementProductionTest {
    @Test fun sixtyFourSoundingTracksCanBeOrganizedWithOneUndoAndReopenedWithoutLosingAudio() = runBlocking {
        val directory = Files.createTempDirectory("creation-arrangement-")
        val paths = ConcurrentHashMap<String, Path>()
        fun location(path: Path) = Location("file-${paths.size}").also { paths[it.handle] = path }
        val backend = EditorBackend.create(directory.resolve("profile"),
            { StreamingEnginePort(it, { error("No audio device may be opened in this test") }) },
            { assets, compiler -> HostFileServices(WavImportPort(assets, { paths.getValue(it.handle) }),
                FileProjectPort(assets, { paths.getValue(it.handle) }), WavExportPort(compiler) { paths.getValue(it.handle) }) })
        val pcm = WavPcmPort(backend.assets)
        val compiler = ProgramCompiler(pcm)
        val studio = backend.studio
        var serial = 0
        suspend fun idle() = withTimeout(10_000) {
            while (studio.work.value.jobId != null || studio.work.value.preparationId != null) delay(2)
        }
        val samples = FloatArray(512 * 2) { if (it % 2 == 0) 1f / 128 else -1f / 256 }
        val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, samples) }.toByteArray()
        val asset = Asset(sha256(bytes), "wav", bytes.size.toLong(), 48_000, 2, 512, "Synthetic stereo")
        try {
            backend.assets.publish(asset, ByteArrayInputStream(bytes))
            assertEquals(64, ProjectLimits.MAX_TRACKS)
            assertEquals(ProjectLimits.MAX_TRACKS, Arrangement.MAX_TRACKS)
            for (simultaneous in listOf(1, 32)) {
                val original = Project(assets = frozenListOf(asset),
                    tracks = List(64) { Track("track-$it", "Track ${it + 1}", TrackKind.BANK) }.frozen(),
                    clips = List(64) { Clip("clip-$it", "track-$it", asset.hash, FrameRange(0, 512),
                        timelineStartFrame = (it / simultaneous) * 512L) }.frozen())
                val initialProgram = compiler.compile(original, PlaybackTarget.Arrangement(), 0)
                try {
                    assertEquals(64, initialProgram.arrangement!!.clipCount)
                    assertEquals(simultaneous, initialProgram.arrangement!!.maximumOverlap)
                    assertEquals(64, (0 until MixerProgram.MAX_BUSES).count { initialProgram.mixer.busId(it) != null })
                    assertEquals(512 * 8L, initialProgram.residentBytes, "All placements share the original PCM")
                    verifyAudio(initialProgram, original)
                } finally { initialProgram.releasePreparation() }
                assertFailsWith<IllegalArgumentException> { original.copy(tracks = (original.tracks + Track("extra", "65", TrackKind.BANK)).frozen()) }
                val tooManyAtOnce = original.copy(clips = original.clips.mapIndexed { i, clip ->
                    clip.copy(timelineStartFrame = if (i < 33) 0 else (i + 1) * 512L)
                }.frozen())
                assertFailsWith<IllegalArgumentException> { compiler.compile(tooManyAtOnce, PlaybackTarget.Arrangement(), 0) }

                for (action in listOf(ContinuousEditorAction.DeleteClip("clip-0"),
                    ContinuousEditorAction.MoveClip("clip-0", "track-63", 40_123),
                    ContinuousEditorAction.SetClipGain("clip-0", .5f))) {
                    assertTrue(studio.dispatch(Action.New(original)).accepted)
                    assertTrue(studio.dispatch(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement())).accepted)
                    val before = studio.document.value
                    val intent = ContinuousClipEdits.intent(before.project, action, { "new-${++serial}" })
                    assertTrue(studio.dispatch(Action.Edit(intent, before.revision)).accepted)
                    val edited = studio.document.value
                    assertEquals(before.revision + 1, edited.revision)
                    assertTrue(edited.canUndo)
                    assertEquals(edited.revision, backend.engine.snapshot().programRevision,
                        "Studio must successfully publish the prepared arrangement even with no output device")
                    assertNotEquals(before.project, edited.project)
                    when (action) {
                        is ContinuousEditorAction.DeleteClip -> assertFalse(edited.project.clips.any { it.id == "clip-0" })
                        is ContinuousEditorAction.MoveClip -> edited.project.clips.first { it.id == "clip-0" }.let {
                            assertEquals("track-63", it.trackId); assertEquals(40_123L, it.timelineStartFrame)
                        }
                        is ContinuousEditorAction.SetClipGain -> assertEquals(.5f, edited.project.clips.first { it.id == "clip-0" }.gain)
                        else -> error("Unexpected edit")
                    }
                    val prepared = compiler.compile(edited.project, PlaybackTarget.Arrangement(), edited.revision)
                    try { verifyAudio(prepared, edited.project) } finally { prepared.releasePreparation() }
                    assertTrue(studio.dispatch(Action.Undo).accepted)
                    assertEquals(before.project, studio.document.value.project)
                    assertFalse(studio.document.value.canUndo, "Exactly one Undo reverses the complete edit")
                    assertTrue(studio.dispatch(Action.Redo).accepted)
                    assertEquals(edited.project, studio.document.value.project)

                    val archive = directory.resolve("song.choplab")
                    val handle = location(archive)
                    assertTrue(studio.dispatch(Action.Save(handle)).accepted); idle()
                    val reopenedStore = FileAssetStore(directory.resolve("reopened-${++serial}"))
                    val reopened = Files.newInputStream(archive).use { ArchiveCodec().read(it, reopenedStore) }
                    assertEquals(edited.project, reopened)
                    assertContentEquals(bytes, reopenedStore.read(asset))
                    assertTrue(studio.dispatch(Action.New()).accepted)
                    assertTrue(studio.dispatch(Action.Open(handle)).accepted); idle()
                    assertEquals(edited.project, studio.document.value.project)
                    assertTrue(studio.dispatch(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement())).accepted)
                    val resumed = compiler.compile(studio.document.value.project, PlaybackTarget.Arrangement(), studio.document.value.revision)
                    try { verifyAudio(resumed, reopened) } finally { resumed.releasePreparation() }
                    backend.flushAutosave()
                    assertEquals(reopened, AutosaveStore(directory.resolve("profile/autosave"), backend.assets).recover()!!.project)
                }
            }
            val stats = PcmMemoryBudget.shared.statistics()
            assertEquals(128L * 1024 * 1024, stats.limitBytes)
            assertTrue(stats.usedBytes >= MixerDsp.PCM_BYTES)
            assertTrue(stats.peakBytes <= stats.limitBytes)
            println("CREATION_64_STUDIO nonoverlap=64 overlap=32 edits=delete,move,gain transactions=6 undoRedo=6 archiveOpen=6 " +
                "mixerPcmBytes=${MixerDsp.PCM_BYTES} globalPcmPeak=${stats.peakBytes} globalPcmLimit=${stats.limitBytes}; synthetic local only")
        } finally { pcm.close(); backend.shutdown(); directory.toFile().deleteRecursively() }
    }

    private fun verifyAudio(program: EngineProgram, project: Project) {
        val frames = program.arrangement!!.durationFrames.toInt()
        val output = OfflineRender.render(program, listOf(EngineCommand.StartSequence(0, 1)), frames, blockFrames = 192)
        val expected = FloatArray(frames * 2)
        for (clip in project.clips) {
            val start = clip.timelineStartFrame!!.toInt()
            for (at in start until start + clip.range.length.toInt()) {
                expected[at * 2] += clip.gain / 128
                expected[at * 2 + 1] -= clip.gain / 256
            }
        }
        assertContentEquals(expected, output, "Every retained track must render at its exact frame with separate stereo channels")
    }
}
