@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.desktop.next

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.choplab.core.*
import com.choplab.jvm.*
import com.choplab.ui.*
import com.choplab.ui.onboarding.QuickStartController
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** First-run UI -> real Presenter/file import -> Undo/save/restart. Only endpoint and file picker are fake. */
class NextQuickStartHostTest {
    @Test fun guideNeverEditsOrStartsInputAndExplicitImportSurvivesGuideReopenUndoSaveAndRestart() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("next-guide-host-")
        val profile = directory.resolve("profile")
        val input = directory.resolve("source.wav")
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, FloatArray(9_600) { i -> if (i % 2 == 0) .2f else -.1f }) }
        val bytes = Files.readAllBytes(input)
        val archive = directory.resolve("song.choplab")
        var microphoneCalls = 0; var pickerCalls = 0
        val backend = NextBackend.create(profile, sinkFactory = { CountingTestSink() }, microphone = { microphoneCalls++; null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend) { null }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, object : ContinuousEditorPorts by host {
            override suspend fun chooseAudio() = backend.files.register(input).also { pickerCalls++ }
            override suspend fun chooseSave() = backend.files.register(archive)
        })
        val store = FileQuickStartStore(profile.resolve("ui"))
        val guide = QuickStartController(scope, backend.studio.document.value.revision == 0L, store::completed, store::complete)
        val scene = ImageComposeScene(1440, 838, coroutineContext = coroutineContext) {
            val state by presenter.state.collectAsState()
            ContinuousEditor(state, presenter::onAction, presenter::readout, quickStart = guide)
        }
        var saved: com.choplab.core.model.Project? = null
        try {
            until { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            val before = backend.studio.document.value
            scene.until { scene.tag("next-help-close")?.config?.getOrNull(SemanticsProperties.Focused) == true }
            assertEquals(before, backend.studio.document.value); assertFalse(before.canUndo)
            assertEquals(0, pickerCalls); assertEquals(0, microphoneCalls)
            assertFalse(backend.engine.snapshot().playing); assertFalse(backend.engine.originalPlayback().playing)
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            scene.closed(); withTimeout(3_000) { while (!store.completed()) delay(5) }
            assertEquals(before, backend.studio.document.value)
            scene.click("next-help-open"); scene.until { scene.tag("next-help-import") != null }
            scene.click("next-help-import"); scene.closed()
            until { backend.studio.work.value.jobId == null && !backend.studio.document.value.audiblePending && backend.studio.document.value.project.assets.size == 1 }
            until { presenter.state.value.original != null }
            val imported = backend.studio.document.value
            assertTrue(imported.canUndo); assertEquals(1, pickerCalls); assertEquals(0, microphoneCalls)
            assertFalse(backend.engine.snapshot().playing); assertFalse(backend.engine.originalPlayback().playing)
            scene.click("next-help-open"); scene.until { scene.tag("next-help-close") != null }
            scene.click("next-help-close"); scene.closed()
            assertEquals(imported, backend.studio.document.value, "Help never inserts an Undo step")
            assertTrue(presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(before.project, backend.studio.document.value.project)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(imported.project, backend.studio.document.value.project)
            assertTrue(presenter.dispatch(ContinuousEditorAction.SaveProject)); until { backend.studio.work.value.jobId == null && Files.exists(archive) }
            saved = backend.studio.document.value.project
            val archiveStore = FileAssetStore(directory.resolve("archive-assets"))
            assertEquals(saved, Files.newInputStream(archive).use { ArchiveCodec().read(it, archiveStore) })
            assertContentEquals(bytes, archiveStore.read(saved.assets.single()))
        } finally {
            scene.close(); guide.close(); presenter.close(); host.close(); backend.shutdown(); scope.cancel()
        }
        try {
            val fresh = NextBackend.create(profile, sinkFactory = { CountingTestSink() }, microphone = { microphoneCalls++; null })
            val freshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val reopened = QuickStartController(freshScope, fresh.studio.document.value.revision == 0L, store::completed, store::complete)
            try {
                until { !reopened.state.value.loading }
                assertFalse(reopened.state.value.open)
                assertEquals(saved, fresh.studio.document.value.project)
                assertContentEquals(bytes, fresh.assets.read(fresh.studio.document.value.project.assets.single()))
                reopened.open(); assertTrue(reopened.state.value.open); reopened.dismiss()
                assertEquals(saved, fresh.studio.document.value.project)
                assertEquals(0, microphoneCalls); assertEquals(1, pickerCalls)
            } finally { reopened.close(); fresh.shutdown(); freshScope.cancel() }
        } finally { directory.toFile().deleteRecursively() }
    }
    private fun ImageComposeScene.tag(value: String): SemanticsNode? {
        fun find(node: SemanticsNode): SemanticsNode? = if (node.config.getOrNull(SemanticsProperties.TestTag) == value) node else node.children.firstNotNullOfOrNull(::find)
        return semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }
    }
    private suspend fun ImageComposeScene.settle() { repeat(4) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.until(condition: () -> Boolean) { withTimeout(5_000) { do { settle() } while (!condition()) } }
    private suspend fun ImageComposeScene.closed() = until { tag("next-quick-start") == null && semanticsOwners.size == 1 }
    private suspend fun ImageComposeScene.click(value: String) {
        settle(); val point = requireNotNull(tag(value)).boundsInWindow.center
        sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        assertEquals(point, requireNotNull(tag(value)).boundsInWindow.center)
        sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private suspend fun until(condition: () -> Boolean) { withTimeout(5_000) { while (!condition()) delay(10) } }
}
