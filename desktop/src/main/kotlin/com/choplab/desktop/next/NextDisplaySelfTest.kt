@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.desktop.next

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.choplab.ui.*
import com.choplab.desktop.provider.*
import com.choplab.desktop.spotify.*
import java.awt.*
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.Timer
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Packaged Java entry: owned synthetic windows, zero audio/provider calls, isolated preference files. */
object NextDisplaySelfTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2 && args[1] in listOf("ja", "en"))
        val output = Path.of(args[0]); Files.createDirectories(output)
        val privateSettings = Files.createTempDirectory(output, ".display-profile-")
        val originalLocale = Locale.getDefault()
        val language = if (args[1] == "ja") NextDisplayLanguage.JAPANESE else NextDisplayLanguage.ENGLISH
        NextDisplaySettings.write(privateSettings.resolve("display.properties"), NextDisplayPreferences(language, 2f))
        val settings = NextDisplaySettings(privateSettings.resolve("display.properties"), originalLocale)
        val problems = AtomicReference<Throwable?>()
        val measured = AtomicInteger()
        val minimumFont = UIManager.getFont("Button.font").size2D * 2
        fun measure(dialog: JDialog, id: String) {
                    fun controls(component: Component): List<JButton> = (if (component is JButton) listOf(component) else emptyList()) +
                        (component as? Container)?.components.orEmpty().flatMap(::controls)
                    val config = dialog.graphicsConfiguration
                    val inset = Toolkit.getDefaultToolkit().getScreenInsets(config)
                    val work = Rectangle(config.bounds).apply { x += inset.left; y += inset.top; width -= inset.left + inset.right; height -= inset.top + inset.bottom }
                    check(work.contains(dialog.bounds)) { "Dialog exceeds the usable screen" }
                    for (name in listOf("next-dialog-stop-all", "next-dialog-close")) {
                        val button = controls(dialog).single { it.name == name }
                        check(button.isShowing && button.height >= 48 && button.font.size2D >= minimumFont) {
                            "$name: showing=${button.isShowing}, height=${button.height}, font=${button.font.size2D}, requiredFont=$minimumFont"
                        }
                        val bounds = SwingUtilities.convertRectangle(button.parent, button.bounds, dialog.contentPane)
                        check(Rectangle(0, 0, dialog.contentPane.width, dialog.contentPane.height).contains(bounds)) { "Fixed controls clipped" }
                        check(button.width >= button.getFontMetrics(button.font).stringWidth(button.text) + 32)
                    }
                    fun panels(component: Component): List<androidx.compose.ui.awt.ComposePanel> =
                        if (component is androidx.compose.ui.awt.ComposePanel) listOf(component)
                        else (component as? Container)?.components.orEmpty().flatMap(::panels)
                    fun textLayouts(node: SemanticsNode): List<TextLayoutResult> {
                        val values = mutableListOf<TextLayoutResult>()
                        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(values)
                        return values + node.children.flatMap(::textLayouts)
                    }
                    val layouts = panels(dialog).flatMap { panel ->
                        panel.renderImmediately()
                        panel.semanticsOwners.flatMap { textLayouts(it.unmergedRootSemanticsNode) }
                    }
                    check(layouts.isNotEmpty() && layouts.all { it.layoutInput.density.fontScale == 2f }) { "Native Compose content must use exactly 200% text" }
                    println("{\"window\":\"$id\",\"outerBoundsInsideWorkArea\":true,\"fixedControlsVisible\":true,\"textLayoutsAt200Percent\":${layouts.size}}")
                    measured.incrementAndGet()
                    // Swing printAll cannot capture a GPU ComposePanel. Export the native controls it
                    // actually paints; the Compose editor above has its own rendered image evidence.
                    val strip = controls(dialog).single { it.name == "next-dialog-close" }.parent
                    val pixels = java.awt.image.BufferedImage(strip.width, strip.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    val graphics = pixels.createGraphics()
                    try { strip.printAll(graphics) } finally { graphics.dispose() }
                    ImageIO.write(pixels, "png", output.resolve("mac-native-${args[1]}-$id-controls-font200.png").toFile())
        }
        suspend fun window(id: String, show: suspend (() -> Unit) -> Unit) {
            val composed = java.util.concurrent.atomic.AtomicBoolean()
            val deadline = System.nanoTime() + 15_000_000_000L
            val timer = Timer(50) {
                val owned = Window.getWindows().filterIsInstance<JDialog>().filter { it.isShowing }
                if (composed.get() || System.nanoTime() > deadline) owned.forEach { dialog ->
                    try {
                        check(composed.get()) { "Owned window did not render" }
                        measure(dialog, id)
                    } catch (failure: Throwable) { problems.compareAndSet(null, failure) }
                    val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    val focused = manager.focusOwner?.takeIf { SwingUtilities.getWindowAncestor(it) == dialog } ?: dialog
                    manager.dispatchEvent(KeyEvent(focused, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                        0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED))
                }
            }
            SwingUtilities.invokeAndWait { timer.start() }
            try { withTimeout(20_000) { show { composed.set(true) } } }
            finally { SwingUtilities.invokeAndWait { timer.stop() } }
            problems.get()?.let { throw it }
            check(Window.getWindows().none { it.isShowing })
        }
        try {
            check(NextDisplaySettings.read(privateSettings.resolve("display.properties")) == settings.preferences.value)
            val source = ContinuousSource("synthetic", if (language == NextDisplayLanguage.JAPANESE) "確認用の原曲" else "Example source", 480_000, peaks = List(80) { .2f })
            val state = ContinuousEditorState(stage = ContinuousStage.BEAT, original = source,
                pads = (0..127).map { id -> ContinuousPad(id, if (id < 4) "Sound ${id + 1}" else "",
                    if (id < 4) ContinuousPadKind.SAMPLE else ContinuousPadKind.EMPTY, sourceEndFrame = if (id < 4) 48_000 else 0) },
                capabilities = ContinuousCapability.entries.toSet(),
                tracks = (0..3).map { ContinuousTrack("track-$it", "Track ${it + 1}", 0xFF94B750) })
            val scene = ImageComposeScene(width = 1440, height = 1024, coroutineContext = coroutineContext) {
                NextDisplayEnvironment { ContinuousEditor(state, {}) }
            }
            try {
                repeat(12) { scene.render(System.nanoTime()).close(); delay(12) }
                fun find(node: SemanticsNode, tag: String): SemanticsNode? = if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) node
                    else node.children.firstNotNullOfOrNull { find(it, tag) }
                val stop = requireNotNull(scene.semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode, "ce-stop-all") })
                check(stop.boundsInWindow.top >= 0 && stop.boundsInWindow.bottom <= 1024)
                val gain = requireNotNull(scene.semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode, "ce-song-monitor") })
                check(gain.boundsInWindow.width >= 100 && gain.boundsInWindow.bottom <= 1024) { "Large text must leave usable room for the song volume slider" }
                val image = output.resolve("mac-next-${args[1]}-font200.png")
                scene.render(System.nanoTime()).use { pixels -> pixels.encodeToData()!!.use { Files.write(image, it.bytes) } }
            } finally { scene.close() }
            window("library") { rendered -> check(NextLibraryDialog.choose(null, output.resolve("browser-${args[1]}/library"), {}, rendered) == null) }
            val calls = AtomicInteger()
            val callback = object : SpotifyAuthorizationCallback {
                override val redirectUri = URI("http://127.0.0.1:8877/callback")
                var callbackState = ""
                override fun expectState(state: String) { callbackState = state }
                override fun await(timeout: Duration) = SpotifyCallbackResult("synthetic-code", callbackState)
                override fun cancel() = Unit
                override fun close() = Unit
            }
            val api = SpotifyApi(SpotifyApiTransport { calls.incrementAndGet(); error("Opening must not fetch provider data") })
            SpotifyDesktopSession({}, "0123456789abcdef0123456789abcdef", api = api,
                callbackFactory = SpotifyAuthorizationCallbackFactory { callback }, browser = SpotifyBrowser {},
                purpose = SpotifySessionPurpose.METADATA_ONLY, tokenClient = object : SpotifyTokenClient {
                    override fun exchangeCode(clientId: String, code: String, redirectUri: URI, verifier: String) =
                        SpotifyTokens("synthetic-token", "Bearer", 3600, null, "user-library-read")
                    override fun refresh(clientId: String, refreshToken: String): SpotifyTokens = error("Unused")
                }).use { session ->
                session.login()
                withTimeout(5_000) { while (session.state.value.busy) delay(5) }
                check(session.connected)
                window("spotify") { rendered -> NextSpotifyDialog.show(null, session, rendered) }
                check(session.connected && !session.state.value.busy && calls.get() == 0)
            }
            problems.get()?.let { throw it }
            check(measured.get() == 2) { "Expected both owned browser dialogs" }
            println("{\"status\":\"LOCAL_PASS\",\"scope\":\"display-settings-and-owned-native-windows\",\"language\":\"${args[1]}\",\"fontScale\":2,\"nativeWindows\":${measured.get()},\"audioStarted\":false}")
        } finally {
            settings.close(); Locale.setDefault(originalLocale); privateSettings.toFile().deleteRecursively()
        }
    }
}
