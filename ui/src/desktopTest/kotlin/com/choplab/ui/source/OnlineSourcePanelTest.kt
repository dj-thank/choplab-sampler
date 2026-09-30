@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui.source

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import kotlin.test.*

class OnlineSourcePanelTest {
    @Test fun jaAndEnCompactPanelsConfirmFormatThenSaveWithoutApplyingAndKeepStopCloseReachable() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPAN, Locale.US)) for ((width, height, font) in listOf(Triple(780, 800, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                val source = OnlineCandidate("one", "Synthetic source 音源", "Uploader", 61.0,
                    formats = listOf(OnlineAudioFormat("raw", "webm", "opus", 48_000, 2, null, false, null, "ja", null, null, null)))
                var saves = 0; var applies = 0; var stops = 0; var closes = 0; var dismissed = false
                val port = object : OnlineSourcePort {
                    override val state = MutableStateFlow(OnlineWorkerState())
                    override fun search(query: String, catalog: OnlineCatalog): Boolean {
                        state.value = OnlineWorkerState(OnlinePhase.CANDIDATES, candidates = listOf(source)); return true
                    }
                    override fun inspect(id: String): Boolean { state.value = state.value.copy(phase = OnlinePhase.DETAILS, details = source); return true }
                    override fun selectFormat(id: String): Boolean { state.value = state.value.copy(details = source.copy(selectedFormat = id)); return true }
                    override fun save(id: String): Boolean {
                        saves++; state.value = state.value.copy(phase = OnlinePhase.SAVED, saved = OnlineSaved("receipt", source.title)); return true
                    }
                    override fun cancel() { state.value = state.value.copy(phase = OnlinePhase.CANCELLED, busy = false) }
                    override fun stopAll() { stops++ }
                    override fun close() { closes++ }
                }
                val applying = CompletableDeferred<Unit>()
                val controller = OnlineSourceController(port, OnlineSourceApply { _, _ -> applies++; applying.await(); OnlineUseResult.APPLIED }, 10, this)
                val scene = ImageComposeScene(width, height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    MaterialTheme { OnlineSourcePanel(controller, { dismissed = true }) }
                }
                try {
                    scene.until { tag("online-query") != null }
                    scene.fixed("online-stop", width, height); scene.fixed("online-close", width, height)
                    scene.tag("online-query")!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("synthetic"))
                    scene.until { tag("online-search")?.config?.contains(SemanticsProperties.Disabled) == false }
                    scene.reach("online-search", width, height); scene.click("online-search")
                    scene.until { tag("online-candidate-0") != null }
                    scene.reach("online-candidate-0", width, height); scene.click("online-candidate-0")
                    scene.until { tag("online-format-0") != null }
                    assertTrue(scene.tag("online-save")!!.config.contains(SemanticsProperties.Disabled))
                    assertEquals(0, saves); assertEquals(0, applies)
                    val unknown = if (locale.language == "ja") "情報なし" else "not reported"
                    assertTrue(scene.nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                        .any { it.text.contains(unknown, ignoreCase = true) } })
                    scene.reach("online-format-0", width, height); scene.click("online-format-0")
                    scene.until { tag("online-save")?.config?.contains(SemanticsProperties.Disabled) == false }
                    scene.reach("online-save", width, height); scene.click("online-save")
                    scene.until { tag("online-use") != null }
                    assertEquals(1, saves); assertEquals(0, applies); assertFalse(dismissed)
                    scene.fixed("online-stop", width, height); scene.fixed("online-close", width, height)
                    scene.reach("online-use", width, height)
                    scene.capture("${locale.language}-${width}-${(font * 100).toInt()}.png")
                    scene.click("online-use")
                    scene.until { controller.state.value.applying }
                    assertTrue(scene.tag("online-close")!!.config.contains(SemanticsProperties.Disabled))
                    scene.click("online-stop"); scene.until { stops == 1 }
                    assertEquals(1, applies)
                    applying.complete(Unit); scene.until { controller.state.value.applied && tag("online-close")?.config?.contains(SemanticsProperties.Disabled) == false }
                    scene.click("online-close"); scene.until { dismissed }
                    assertEquals(1, closes)
                } finally { applying.complete(Unit); scene.close(); controller.close() }
                assertEquals(1, closes)
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun artworkDecoderChecksEncodedAndDecodedBoundsBeforeDisplayingPixels() = runBlocking<Unit> {
        val image = java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = java.io.ByteArrayOutputStream().use { output -> javax.imageio.ImageIO.write(image, "png", output); output.toByteArray() }
        val decoded = decodeOnlineArtwork(bytes)
        assertNotNull(decoded); assertEquals(40, decoded.width); assertEquals(30, decoded.height)
        assertNull(decodeOnlineArtwork(byteArrayOf(1, 2, 3)))
        assertNull(decodeOnlineArtwork(ByteArray(1_048_577)))
        val large = java.awt.image.BufferedImage(2049, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val largeBytes = java.io.ByteArrayOutputStream().use { output -> javax.imageio.ImageIO.write(large, "png", output); output.toByteArray() }
        assertNull(decodeOnlineArtwork(largeBytes))
    }

    @Test fun cancellingAWorkerStillKeepsOutputStopAndCloseImmediatelyReachable() = runBlocking<Unit> {
        var cancels = 0; var stops = 0; var closes = 0; var dismissed = false
        val port = object : OnlineSourcePort {
            override val state = MutableStateFlow(OnlineWorkerState(OnlinePhase.DOWNLOADING, busy = true, progress = 42))
            override fun search(query: String, catalog: OnlineCatalog) = false
            override fun inspect(id: String) = false
            override fun selectFormat(id: String) = false
            override fun save(id: String) = false
            override fun cancel() { cancels++; state.value = state.value.copy(phase = OnlinePhase.CANCELLED, busy = true) }
            override fun stopAll() { stops++ }
            override fun close() { closes++ }
        }
        val controller = OnlineSourceController(port, OnlineSourceApply { _, _ -> error("No import") }, 0, this)
        val scene = ImageComposeScene(390, 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
            MaterialTheme { OnlineSourcePanel(controller, { dismissed = true }) }
        }
        try {
            scene.until { tag("online-cancel") != null }
            for (tag in listOf("online-cancel", "online-stop", "online-close")) scene.fixed(tag, 390, 844)
            scene.click("online-cancel"); scene.until { cancels == 1 && tag("online-cancel")?.config?.contains(SemanticsProperties.Disabled) == true }
            assertTrue(controller.state.value.worker.busy)
            scene.click("online-stop"); scene.until { stops == 1 }
            scene.click("online-close"); scene.until { dismissed }
            assertEquals(1, closes)
        } finally { scene.close(); controller.close() }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private suspend fun ImageComposeScene.until(predicate: ImageComposeScene.() -> Boolean) = withTimeout(10_000) {
        do { render(System.nanoTime()).close(); if (predicate()) return@withTimeout; delay(8) } while (true)
    }
    private fun ImageComposeScene.fixed(value: String, width: Int, height: Int) {
        val bounds = requireNotNull(tag(value)).boundsInRoot
        assertTrue(bounds.height >= 48 && bounds.top >= 0 && bounds.bottom <= height && bounds.left >= 0 && bounds.right <= width, "$value: $bounds")
    }
    private suspend fun ImageComposeScene.reach(value: String, width: Int, height: Int) {
        val scroll = requireNotNull(tag("online-scroll"))
        val node = requireNotNull(tag(value))
        scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, node.positionInRoot.y - scroll.boundsInRoot.top - 12)
        until {
            val bounds = requireNotNull(tag(value)).boundsInRoot
            val visible = requireNotNull(tag("online-scroll")).boundsInRoot
            bounds.top >= visible.top && bounds.top < visible.bottom && bounds.bottom > visible.top
        }
        // A long format description may exceed the viewport; its visible upper section is still a complete button target.
        val bounds = requireNotNull(tag(value)).boundsInRoot
        assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top in 0f..height.toFloat())
    }
    private suspend fun ImageComposeScene.click(value: String) {
        val bounds = requireNotNull(tag(value)).boundsInRoot
        val viewport = requireNotNull(tag("online-scroll")).boundsInRoot
        val center = if (value in setOf("online-stop", "online-close", "online-cancel")) bounds.center else
            androidx.compose.ui.geometry.Offset(bounds.center.x, (bounds.top + minOf(bounds.bottom, viewport.bottom)) / 2)
        sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close(); delay(8)
        sendPointerEvent(PointerEventType.Release, center, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        render(System.nanoTime()).close(); yield()
    }
    private fun ImageComposeScene.capture(name: String) {
        val output = File(System.getProperty("choplab.ui.evidenceDir"), "online-source").apply { mkdirs() }
        render(System.nanoTime()).use { image -> image.encodeToData()!!.use { File(output, name).writeBytes(it.bytes) } }
    }
}
