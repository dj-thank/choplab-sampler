@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.desktop.next

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*

import com.choplab.core.*
import com.choplab.core.model.Project
import com.choplab.jvm.WavCodec
import com.choplab.sampler.source.*
import com.choplab.sampler.source.newpipe.DetailedYoutubeBackend
import com.choplab.ui.*
import com.choplab.ui.source.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.test.*

class NextOnlineSourcePortTest {
    @Test fun actualChooserControllerSavesOnlyThenExplicitImportUsesOneUndoEngineExportAndArchiveRestart() = runBlocking<Unit> {
        val root = Files.createTempDirectory("online-chooser-production-")
        try {
            val profile = root.resolve("profile")
            val input = root.resolve("Original.wav")
            Files.newOutputStream(input).use { out -> WavCodec.writePcm(out, FloatArray(48_000 * 2) {
                (kotlin.math.sin(it / 31.0) * if (it % 2 == 0) .2 else -.12).toFloat()
            }, bits = 24, dither = false) }
            val originalBytes = Files.readAllBytes(input)
            val format = YoutubeAudioFormat("wave", "wav", "pcm_s24le", 48_000, 2, 2_304_000, false,
                originalBytes.size.toLong(), "ja", null, null, false)
            val candidate = YoutubeSource("abcdefghijk", "Synthetic source", "Synthetic uploader", 1.0,
                YoutubeMetadata(formats = listOf(format)))
            var downloads = 0
            val closes = AtomicInteger()
            val provider = object : DetailedYoutubeBackend, AutoCloseable {
                override fun search(query: String, jobId: String) = listOf(candidate)
                override fun search(query: String, jobId: String, kind: YoutubeSearchKind) = listOf(candidate)
                override fun info(url: String, jobId: String) = candidate
                override fun download(source: YoutubeSource, folder: File, jobId: String, progress: (Float) -> Unit): File {
                    assertEquals(format.id, source.selectedFormat); downloads++
                    return folder.resolve("audio.wav").also { Files.copy(input, it.toPath()); progress(100f) }
                }
                override fun cancel(jobId: String) {}
                override fun close() { closes.incrementAndGet() }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val backend = NextBackend.create(profile, sinkFactory = { error("No native output") }, microphone = { null })
            val ports = DesktopEditorPorts(backend, onlineDirectory = { root.resolve("library") }, onlineBackend = { provider }) { null }
            val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
            suspend fun ready() = withTimeout(10_000) {
                while (backend.studio.work.value.jobId != null || backend.studio.work.value.preparationId != null) delay(2)
            }
            val before = backend.studio.document.value
            val scene = ImageComposeScene(width = 900, height = 838, coroutineContext = coroutineContext) {
                val state by presenter.state.collectAsState()
                val online by presenter.onlineSource.collectAsState()
                ContinuousEditor(state, presenter::onAction, onlineSource = online)
            }
            scene.until { tag("ce-online")?.let { it.size.height >= 48 && !it.config.contains(SemanticsProperties.Disabled) } == true }
            scene.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }.forEach { it(0f, 10_000f) }
            scene.until { tag("ce-online")!!.boundsInRoot.let { it.height >= 48 && it.top >= 0 && it.bottom <= 838 } }
            scene.click("ce-online")
            scene.until { tag("online-query") != null }
            val controller = requireNotNull(presenter.onlineSource.value)
            assertTrue(presenter.dispatch(ContinuousEditorAction.ImportOnline))
            assertSame(controller, presenter.onlineSource.value)
            suspend fun phase(expected: OnlinePhase) = withTimeout(10_000) {
                while (controller.state.value.worker.phase != expected || controller.state.value.worker.busy) delay(2)
            }
            val saved: Project
            try {
                assertTrue(controller.dispatch(OnlineSourceAction.Query(candidate.url)))
                assertTrue(controller.dispatch(OnlineSourceAction.Search)); phase(OnlinePhase.CANDIDATES)
                assertEquals(0, downloads)
                assertTrue(controller.dispatch(OnlineSourceAction.Inspect(candidate.id))); phase(OnlinePhase.DETAILS)
                assertFalse(controller.dispatch(OnlineSourceAction.Save))
                assertTrue(controller.dispatch(OnlineSourceAction.Format(format.id)))
                assertTrue(controller.dispatch(OnlineSourceAction.Save)); phase(OnlinePhase.SAVED)
                assertEquals(1, downloads)
                assertEquals(before, backend.studio.document.value)
                val receipt = requireNotNull(controller.state.value.worker.saved)
                val stored = LocalAudioLibrary(root.resolve("library").toFile(), backend::validateLibraryFile).resolve(receipt.id)
                assertContentEquals(originalBytes, stored.readBytes())
                assertNull(controller.state.value.worker.details!!.artist)
                assertNull(controller.state.value.worker.details!!.album)
                assertTrue(controller.dispatch(OnlineSourceAction.UseOriginal))
                withTimeout(10_000) { while (!controller.state.value.applied) delay(2) }
                assertEquals(before.revision + 1, backend.studio.document.value.revision)
                assertFalse(controller.dispatch(OnlineSourceAction.UseOriginal))
                val imported = backend.studio.document.value.project
                assertEquals(candidate.title, imported.assets.single().name)
                assertTrue(presenter.dispatch(ContinuousEditorAction.Undo))
                assertEquals(before.project, backend.studio.document.value.project)
                assertTrue(presenter.dispatch(ContinuousEditorAction.Redo))
                assertEquals(imported, backend.studio.document.value.project)
                assertTrue(presenter.dispatch(ContinuousEditorAction.AssignSourceRange(0)))
                assertTrue(presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
                val output = root.resolve("song.wav")
                assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(output), 48_000, bits = 24), PlaybackTarget.Arrangement())).accepted)
                ready()
                val audio = Files.newInputStream(output).use { WavCodec.read(it) }
                assertEquals(24, audio.info.bits); assertEquals(48_000L, audio.info.frames)
                assertTrue(audio.samples.any { kotlin.math.abs(it) > .05f })
                val archive = root.resolve("song.choplab")
                assertTrue(backend.saveProject(archive).accepted); ready()
                ZipFile(archive.toFile()).use { zip ->
                    assertContentEquals(originalBytes, zip.getInputStream(requireNotNull(zip.getEntry("assets/${receipt.id}.wav"))).use { it.readBytes() })
                }
                saved = backend.studio.document.value.project
                scene.until { tag("online-close")?.config?.contains(SemanticsProperties.Disabled) == false }
                scene.click("online-stop")
                scene.click("online-close")
                scene.until { presenter.onlineSource.value == null && semanticsOwners.size == 1 }
                assertNull(presenter.onlineSource.value)
            } finally { scene.close(); controller.close(); presenter.close(); ports.close(); backend.shutdown(); scope.cancel() }
            NextBackend.create(profile, sinkFactory = { error("No native output") }, microphone = { null }).use { reopened ->
                assertEquals(saved, reopened.studio.document.value.project)
                assertContentEquals(originalBytes, reopened.assets.read(saved.assets.single()))
            }
            withTimeout(5_000) { while (closes.get() != 1) delay(2) }
        } finally { root.toFile().deleteRecursively() }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private suspend fun ImageComposeScene.until(condition: ImageComposeScene.() -> Boolean) = withTimeout(10_000) {
        do { render(System.nanoTime()).close(); if (condition()) return@withTimeout; delay(8) } while (true)
    }
    private suspend fun ImageComposeScene.click(value: String) {
        val bounds = requireNotNull(tag(value)).boundsInRoot
        assertTrue(bounds.height >= 48 && bounds.top >= 0 && bounds.bottom <= 838, "$value: $bounds")
        sendPointerEvent(PointerEventType.Press, bounds.center, type = PointerType.Mouse,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close(); delay(8)
        sendPointerEvent(PointerEventType.Release, bounds.center, type = PointerType.Mouse,
            buttons = PointerButtons(), button = PointerButton.Primary)
        render(System.nanoTime()).close(); yield()
    }
}
