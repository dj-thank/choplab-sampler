@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.choplab.core.DocumentState
import com.choplab.core.ai.*
import com.choplab.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import kotlin.test.*

class LyricProposalPanelTest {
    @Test fun jaAndEnFormRequireConsentThenPreviewAndExplicitApplyWithReachableLargeTextControls() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPAN, Locale.US)) for ((width, height, font) in listOf(Triple(900, 900, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                val document = MutableStateFlow(DocumentState(Project(), 0))
                var requests = 0
                var edits = 0
                var closed = false
                val provider = object : LlmProvider {
                    override suspend fun lyrics(request: LyricRequest, key: SessionApiKey): LyricProviderResult {
                        requests++
                        return LyricProviderResult.Success(LyricProposal("提案", LyricLanguage.JAPANESE,
                            frozenListOf(ProposalSection("A", LyricSectionKind.VERSE, 4,
                                frozenListOf(ProposalLine.create("風の音", "かぜのおと", LyricLanguage.JAPANESE))))), LyricUsage(2, 3, 5), "gemini-test")
                    }
                }
                val session = GoogleLyricSession { 100L }
                val controller = LyricProposalController(document, provider, LyricProposalApply { _, _ -> edits++; true }, this, session.openDialog())
                // Keep scroll animation enabled and slow it down on the compact, large-text surface.
                val motion = object : MotionDurationScale { override val scaleFactor = if (font == 2f) 4f else 1f }
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext + motion) {
                    CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) = Unit }) {
                        MaterialTheme { LyricProposalPanel(controller, { closed = true }) }
                    }
                }
                try {
                    scene.settle()
                    for ((tag, value) in listOf("ai-model" to "gemini-test", "ai-key" to "fake-key", "ai-theme" to "風")) {
                        scene.tag(tag)!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))
                    }
                    scene.settle()
                    assertEquals(0, requests)
                    assertTrue(scene.tag("ai-key")!!.config.contains(SemanticsProperties.Password))
                    assertTrue(scene.tag("ai-generate")!!.config.contains(SemanticsProperties.Disabled))
                    scene.reach("ai-consent", width, height)
                    scene.click("ai-consent")
                    scene.settle()
                    assertTrue(scene.tag("ai-generate")!!.config.contains(SemanticsProperties.Disabled), "Consent alone is not a reviewed owner session")
                    val review = ReviewedGoogleUse("gemini-test", GoogleAccountTier.PAID,
                        GoogleUseEligibility.REVIEWED_FOR_THIS_SESSION,
                        GoogleTokenPrice("gemini-test", GoogleAccountTier.PAID, "USD", 1_000, 100, 200, 10_000, 0, 1_000),
                        GoogleTokenBounds(4096, 128, 64), GoogleMoney("USD", 2_000), 0, 1_000)
                    val binding = requireNotNull(session.pendingReview())
                    assertNull(session.install(binding, review))
                    scene.settle()
                    assertNotNull(scene.tag("ai-attempt-maximum"))
                    assertTrue(scene.tag("ai-generate")!!.config.contains(SemanticsProperties.Disabled), "Review changes clear the previous consent")
                    scene.reach("ai-consent", width, height)
                    scene.click("ai-consent")
                    scene.settle()
                    scene.tag("ai-key")!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("replacement-key"))
                    scene.settle()
                    assertTrue(scene.tag("ai-generate")!!.config.contains(SemanticsProperties.Disabled))
                    assertEquals(GoogleAdmissionProblem.INPUT_CHANGED, session.install(binding, review))
                    assertEquals(0, requests)
                    assertNull(session.install(requireNotNull(session.pendingReview()), review))
                    scene.settle()
                    assertTrue(scene.tag("ai-generate")!!.config.contains(SemanticsProperties.Disabled))
                    scene.reach("ai-consent", width, height)
                    scene.click("ai-consent")
                    scene.settle()
                    scene.reach("ai-generate", width, height)
                    assertFalse(scene.tag("ai-generate")!!.config.contains(SemanticsProperties.Disabled))
                    scene.click("ai-generate")
                    scene.settle()
                    assertEquals(1, requests); assertEquals(0, edits)
                    assertEquals(LyricProposalPhase.PREVIEW, controller.state.value.phase)
                    assertFalse(scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any { '%' in it.text })
                    scene.reach("ai-apply", width, height)
                    scene.capture("${locale.language}-${width}-font${(font * 100).toInt()}.png")
                    scene.click("ai-apply")
                    scene.settle()
                    assertEquals(1, edits); assertEquals(1, requests)
                    assertNotNull(scene.tag("ai-applied")); assertNull(scene.tag("ai-apply"))
                    scene.reach("ai-close", width, height)
                    scene.click("ai-close")
                    assertTrue(closed)
                    assertEquals(LyricProposalPhase.CLOSED, controller.state.value.phase)
                } finally { scene.close(); controller.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private suspend fun ImageComposeScene.settle() { repeat(8) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.reach(value: String, width: Int, height: Int) {
        val node = requireNotNull(tag(value))
        val scroll = nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
        val area = scroll.boundsInRoot
        if (node.positionInRoot.y < area.top || node.positionInRoot.y + node.size.height > area.bottom) {
            val distance = node.positionInRoot.y - area.top - area.height / 3f
            assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action)(0f, distance))
            // ScrollBy animates. Wait for the real frame clock and layout/draw work, not a fixed frame count.
            // A guessed integer destination can also be transient because ScrollState keeps subpixel remainder.
            try { withTimeout(5_000) { do { settle() } while (hasInvalidations()) } }
            catch (failure: TimeoutCancellationException) {
                fail("Scroll to $value did not finish: ${tag(value)?.boundsInRoot}; viewport=${width}x$height", failure)
            }
        }
        val reached = requireNotNull(tag(value))
        val bounds = reached.boundsInRoot
        val target = reached.touchBoundsInRoot
        // A Checkbox draws at 24dp; its actual interactive target must still be at least 48dp.
        assertTrue(target.width >= 48 && target.height >= 48, "$value touch target: $target")
        assertTrue(target.left >= 0 && target.right <= width && target.top >= 0 && target.bottom <= height,
            "$value touch target must be fully reachable: $target; viewport=${width}x$height")
        assertEquals(reached.size.width.toFloat(), bounds.width, .5f, "$value clipped width: $bounds")
        assertEquals(reached.size.height.toFloat(), bounds.height, .5f, "$value clipped height: $bounds")
        if (value != "ai-consent") assertTrue(bounds.height >= 48, "$value button height: $bounds")
        assertTrue(bounds.top >= 0 && bounds.bottom <= height,
            "$value must be reachable: $bounds / ${reached.size}; viewport=${width}x$height; pending=${hasInvalidations()}")
        assertTrue(bounds.left >= 0 && bounds.right <= width)
    }
    private suspend fun ImageComposeScene.click(value: String) {
        val point = requireNotNull(tag(value)).boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        assertEquals(point, requireNotNull(tag(value)).boundsInRoot.center, "$value moved during the pointer press")
        sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse,
            buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private fun ImageComposeScene.capture(name: String) {
        val output = File(System.getProperty("choplab.ui.evidenceDir"), "ai-lyrics").apply { mkdirs() }
        render(System.nanoTime()).use { bitmap -> bitmap.encodeToData()!!.use { File(output, name).writeBytes(it.bytes) } }
    }
}
