@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.ui

import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.choplab.engine.PcmReadStatus
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class ContinuousPcmIdleTest {
    @Test fun healthyHeaderReleasesTheFrameClockAndStillObservesLaterPcmFailure() = runBlocking<Unit> {
        val latest = AtomicReference(ContinuousPcmReadout())
        var reads = 0
        val scene = ImageComposeScene(500, 100, coroutineContext = coroutineContext) {
            CETheme { CEPcmHealth(ContinuousEditorFixture.state(), {}, { reads++; ContinuousEditorReadout(pcm = latest.get()) }, 0) { Text("Ready") } }
        }
        var closed = false
        try {
            assertTrue(scene.becomesIdle(), "A healthy stopped editor keeps requesting frames")
            val before = reads
            // A native readout is not Compose state. Only the production health observer can expose this change.
            latest.set(ContinuousPcmReadout(PcmReadStatus.FAILED, 192, 1))
            withTimeout(2_000) {
                while (!scene.hasTag("ce-pcm-status")) { scene.render(System.nanoTime()).close(); delay(5) }
            }
            assertTrue(reads > before)
            assertTrue(scene.becomesIdle(), "A stable error must also release the frame clock")
            val after = reads
            scene.close(); closed = true
            delay(300)
            assertEquals(after, reads, "Disposal stops health polling")
        } finally { if (!closed) scene.close() }
    }
    private suspend fun ImageComposeScene.becomesIdle(): Boolean = withTimeoutOrNull(1_000) {
        while (true) {
            render(System.nanoTime()).close(); delay(1)
            if (!hasInvalidations()) return@withTimeoutOrNull true
        }
        @Suppress("UNREACHABLE_CODE") false
    } ?: false
    private fun ImageComposeScene.hasTag(tag: String): Boolean {
        fun visit(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.TestTag) == tag || node.children.any(::visit)
        return semanticsOwners.any { visit(it.unmergedRootSemanticsNode) }
    }
}
