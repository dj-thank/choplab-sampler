@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui.vocal

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineCore
import com.choplab.jvm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.test.*

/** Synthetic stereo microphone -> real take files/Studio -> comp preview -> one Undo -> same export graph/archive. */
class VocalTakeProductionTest {
    @Test fun studioRevisionFenceRejectsACompAfterItsFloatAssetWasPrepared() = runBlocking<Unit> {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Harness { reached.complete(Unit); release.await() }
        try {
            fixture.record("a", .25f, -.15f)
            assertTrue(fixture.controller.dispatch(VocalAction.WholeTake))
            val applying = async { fixture.controller.dispatch(VocalAction.Apply("Comp")) }
            withTimeout(5000) { reached.await() }
            assertTrue(fixture.backend.studio.dispatch(Action.Edit(Intent.Rename("Later song"))).accepted)
            val current = fixture.backend.studio.document.value
            release.complete(Unit)
            assertFalse(applying.await())
            assertEquals(Notice.StaleCompletion, fixture.lastApplyNotice)
            assertEquals(VocalProblem.STALE, fixture.controller.state.value.problem)
            assertEquals(current, fixture.backend.studio.document.value)
            assertTrue(current.project.vocalComps.isEmpty())
        } finally { release.complete(Unit); fixture.close() }
    }
    @Test fun jaEnWideAndFontTwoPanelsKeepChoicesOriginalsUndoAndRestoredArchive() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPAN, Locale.US)) for ((width, height, font) in listOf(Triple(1440, 838, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                val fixture = Harness()
                try {
                    fixture.record("a", .25f, -.15f)
                    fixture.record("b", .75f, -.45f)
                    val studio = fixture.backend.studio
                    assertTrue(studio.dispatch(Action.Edit(Intent.SetLyrics(frozenListOf(LyricLine("one", "First line", 0, 960),
                        LyricLine("two", "Second line", 960, 1920))))).accepted)
                    val before = studio.document.value
                    var closed = false
                    val scene = ImageComposeScene(width, height, density = Density(1f, font), coroutineContext = coroutineContext) {
                        MaterialTheme { VocalTakePanel(fixture.controller, { closed = true }) }
                    }
                    try {
                        // Controller is opened after the recorded document is ready.
                        fixture.controller.dispatch(VocalAction.Reload)
                        scene.until { scene.tag("vocal-from-lyrics") != null }
                        scene.reach("vocal-from-lyrics", height)
                        scene.click("vocal-from-lyrics")
                        scene.reach("vocal-line-two", height)
                        scene.click("vocal-line-two")
                        scene.click("vocal-line-two-b")
                        scene.until { scene.semanticsOwners.size == 1 }
                        scene.reach("vocal-replace-clip-a", height)
                        scene.click("vocal-replace-clip-a")
                        scene.reach("vocal-replace-clip-b", height)
                        scene.click("vocal-replace-clip-b")
                        assertEquals(listOf("a", "b"), fixture.controller.state.value.draft!!.segments.map { it.takeId })
                        for (tag in listOf("vocal-preview-comp", "vocal-apply", "vocal-close")) {
                            val bounds = scene.tag(tag)!!.boundsInRoot
                            assertTrue(bounds.height >= 48 && bounds.left >= 0 && bounds.right <= width && bounds.bottom <= height, "$tag $bounds")
                        }
                        scene.click("vocal-preview-comp")
                        scene.until { fixture.controller.state.value.previewing }
                        assertEquals(before, studio.document.value)
                        assertTrue(fixture.previewEnergy > 0.0)
                        scene.click("vocal-apply")
                        scene.until { fixture.controller.state.value.applied }
                        val saved = studio.document.value.project
                        assertEquals(before.revision + 1, studio.document.value.revision)
                        assertEquals(1, saved.clips.size)
                        assertEquals(before.project.takes, saved.takes)
                        assertEquals(48000, saved.asset(saved.vocalComps.single().renderedAssetHash).frames)
                        assertTrue(studio.dispatch(Action.Undo).accepted); assertEquals(before.project, studio.document.value.project)
                        assertTrue(studio.dispatch(Action.Redo).accepted); assertEquals(saved, studio.document.value.project)
                        fixture.verifyExportAndArchive(saved)
                        fixture.controller.dispatch(VocalAction.Reload)
                        assertTrue(fixture.controller.dispatch(VocalAction.SelectComp(saved.vocalComps.single().id)))
                        assertEquals(saved.vocalComps.single().segments, fixture.controller.state.value.draft!!.segments)
                        scene.until { scene.tag("vocal-close") != null }
                        scene.click("vocal-close")
                        assertTrue(closed)
                    } finally { scene.close() }
                } finally { fixture.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    private class Harness(val beforeApply: suspend () -> Unit = {}) {
        val directory = Files.createTempDirectory("vocal-production-")
        val paths = ConcurrentHashMap<String, Path>()
        fun location(path: Path) = Location("file-${paths.size}").also { paths[it.handle] = path }
        val backend = EditorBackend.create(directory.resolve("profile"), { StreamingEnginePort(it, { error("No native audio in tests") }) },
            { assets, compiler -> HostFileServices(WavImportPort(assets, { paths.getValue(it.handle) }),
                FileProjectPort(assets, { paths.getValue(it.handle) }), WavExportPort(compiler) { paths.getValue(it.handle) }) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pcm = WavPcmPort(backend.assets)
        val compiler = ProgramCompiler(pcm)
        val renderer = VocalCompRenderer(backend.assets, pcm, directory.resolve("comp-scratch"))
        val originals = linkedMapOf<Asset, ByteArray>()
        var previewEnergy = 0.0
        var lastApplyNotice: Notice? = null
        val controller by lazy { VocalTakeController(backend.studio.document, MutableStateFlow(VocalAvailability.EDITABLE), object : VocalTakePorts {
            override suspend fun render(project: Project, draft: VocalCompDraft, name: String) = renderer.render(project, draft, name)
            override suspend fun apply(intent: Intent, expectedRevision: Long): Boolean {
                beforeApply()
                return backend.studio.dispatch(Action.Edit(intent, expectedRevision)).also { lastApplyNotice = it.notice }.accepted
            }
            override suspend fun previewTake(project: Project, takeId: String): Boolean {
                val (preview, target) = VocalCompEdits.audition(project, takeId)
                return previewProgram(preview, target)
            }
            override suspend fun previewComp(asset: Asset): Boolean = previewProgram(Project(assets = frozenListOf(asset),
                tracks = frozenListOf(Track("v", "Preview", TrackKind.VOCAL)), clips = frozenListOf(Clip("p", "v", asset.hash, FrameRange(0, asset.frames)))), PlaybackTarget.Arrangement())
            override fun stopPreview() = Unit
        }, scope) }

        suspend fun previewProgram(project: Project, target: PlaybackTarget): Boolean {
            val program = compiler.compile(project, target, 1)
            val engine = EngineCore()
            engine.controls.offer(EngineCommand.SwapProgram(0, 1, program))
            engine.controls.offer(EngineCommand.StartSequence(0, 2))
            val output = FloatArray(4096)
            engine.render(output)
            previewEnergy = output.sumOf { it.toDouble() * it }
            return true
        }
        suspend fun record(id: String, left: Float, right: Float) {
            val mic = object : MicInput {
                override val sampleRate = 48_000
                override val channels = 2
                var at = 0
                override fun read(buffer: FloatArray): Int {
                    if (at >= 96000) return -1
                    val count = minOf(buffer.size, 96000 - at)
                    repeat(count) { buffer[it] = if (it % 2 == 0) left else right }; at += count; return count
                }
                override fun stop() = Unit
                override fun close() = Unit
            }
            val recorder = VoiceTakes(backend.assets, directory.resolve("recording"), captureChannels = 2) { mic }
            try {
                assertEquals(VoiceTakes.Start.STARTED, recorder.start(2))
                withTimeout(5000) { while (recorder.recordedMillis < 1000) delay(2) }
                val recorded = assertNotNull(recorder.stop("Take $id"))
                originals[recorded.asset] = backend.assets.read(recorded.asset)
                val project = backend.studio.document.value.project
                val track = project.tracks.firstOrNull() ?: Track("voice", "Voice", TrackKind.VOCAL)
                val take = Take(id, track.id, recorded.asset.hash, FrameRange(0, recorded.asset.frames), 0)
                val clip = Clip("clip-$id", track.id, recorded.asset.hash, take.range)
                assertTrue(backend.studio.dispatch(Action.Edit(VocalCompEdits.retain(project, recorded.asset, take,
                    track.takeIf { project.tracks.isEmpty() }, clip = clip))).accepted)
            } finally { recorder.close() }
        }
        suspend fun verifyExportAndArchive(saved: Project) {
            val output = directory.resolve("comp.wav")
            assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(location(output), 48000, bits = 24), PlaybackTarget.Arrangement())).accepted)
            idle()
            val audio = Files.newInputStream(output).use { WavCodec.read(it) }
            assertEquals(48000, audio.info.frames); assertEquals(24, audio.info.bits)
            assertEquals(.25f, audio.samples[5000 * 2], .000001f); assertEquals(-.15f, audio.samples[5000 * 2 + 1], .000001f)
            assertEquals(.75f, audio.samples[35000 * 2], .000001f); assertEquals(-.45f, audio.samples[35000 * 2 + 1], .000001f)
            val archive = directory.resolve("song.choplab")
            assertTrue(backend.studio.dispatch(Action.Save(location(archive))).accepted); idle()
            val fresh = FileAssetStore(directory.resolve("fresh"))
            val reopened = Files.newInputStream(archive).use { ArchiveCodec().read(it, fresh) }
            assertEquals(saved, reopened)
            originals.forEach { (asset, bytes) -> assertContentEquals(bytes, fresh.read(asset)) }
            backend.flushAutosave()
            assertEquals(saved, AutosaveStore(directory.resolve("profile/autosave"), fresh).recover()!!.project)
        }
        suspend fun idle() = withTimeout(10000) { while (backend.studio.work.value.jobId != null || backend.studio.work.value.preparationId != null) delay(2) }
        suspend fun close() { controller.close(); scope.cancel(); pcm.close(); backend.shutdown() }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private suspend fun ImageComposeScene.until(condition: () -> Boolean) = withTimeout(10000) {
        do { render(System.nanoTime()).close(); delay(5) } while (!condition())
        repeat(4) { render(System.nanoTime()).close(); delay(5) }
    }
    private suspend fun ImageComposeScene.click(value: String) {
        until { tag(value)?.config?.getOrNull(SemanticsActions.OnClick)?.action != null && tag(value)?.config?.contains(SemanticsProperties.Disabled) == false }
        val bounds = tag(value)!!.boundsInRoot
        sendPointerEvent(PointerEventType.Press, bounds.center)
        sendPointerEvent(PointerEventType.Release, bounds.center)
        until { true }
    }
    private suspend fun ImageComposeScene.reach(value: String, height: Int) {
        repeat(30) {
            val node = tag(value)
            if (node != null && node.boundsInRoot.height >= 48 && node.boundsInRoot.top >= 0 && node.boundsInRoot.bottom < height - 180) return
            val scroll = tag("vocal-take-fields")!!.config[SemanticsActions.ScrollBy].action!!
            scroll.invoke(0f, if (node != null) node.positionInRoot.y - 100f else 180f)
            until { true }
        }
        error("Control not reachable: $value, bounds=${tag(value)?.boundsInRoot}, fields=${tag("vocal-take-fields")?.boundsInRoot}, tags=${nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }}")
    }
}
