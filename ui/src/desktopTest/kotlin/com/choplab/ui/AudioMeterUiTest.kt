@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlin.test.*

class AudioMeterUiTest {
    @Test fun stereoAndMonoMetersExposeTheirMeasuredChannelAndLevel() = runBlocking<Unit> {
        for (input in listOf(
            RecordingInputReadout(peakLevel = .5f, channels = 2, leftPeak = .5f, rightPeak = 0f),
            RecordingInputReadout(peakLevel = .5f, channels = 2, leftPeak = 0f, rightPeak = .5f),
            RecordingInputReadout(peakLevel = .25f, channels = 1, leftPeak = .25f))) {
            val scene = ImageComposeScene(1000, 300, density = Density(1f), coroutineContext = coroutineContext) {
                CETheme { CERecordingStatus(ContinuousEditorFixture.state().copy(recordingSource = true), {}, { ContinuousEditorReadout(input = input) }, 0) }
            }
            try {
                repeat(12) { scene.render(); delay(10) }
                val nodes = buildList {
                    fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
                    scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
                }
                fun level(tag: String) = assertNotNull(nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag })
                    .config[SemanticsProperties.ProgressBarRangeInfo].current
                if (input.channels == 2) {
                    assertEquals(input.leftPeak, level("ce-input-level-left"))
                    assertEquals(input.rightPeak, level("ce-input-level-right"))
                    assertFalse(nodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == "ce-input-level" })
                } else {
                    assertEquals(.25f, level("ce-input-level"))
                    assertFalse(nodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == "ce-input-level-right" })
                }
            } finally { scene.close() }
        }
    }
}
