@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlin.test.*

class ExactPositionUiTest {
    @Test fun frameAndSecondsInputCancelInvalidBusyAndTargetChangesDoNotApply() = runBlocking<Unit> {
        var identity by mutableStateOf("first")
        var enabled by mutableStateOf(true)
        val applied = mutableListOf<Long>()
        val scene = ImageComposeScene(1000, 750, density = Density(1f), coroutineContext = coroutineContext) {
            CETheme { key(identity) { CEExactPosition(0, 86_399_760, enabled, applied::add) } }
        }
        try {
            scene.settle(); scene.click("ce-clip-position-exact")
            scene.text("-1"); assertTrue(scene.tag("ce-clip-position-exact-apply")!!.config.contains(SemanticsProperties.Disabled))
            scene.click("ce-clip-position-exact-cancel"); assertTrue(applied.isEmpty())
            scene.click("ce-clip-position-exact"); scene.text("1")
            enabled = false; scene.settle()
            assertTrue(scene.tag("ce-clip-position-exact-apply")!!.config.contains(SemanticsProperties.Disabled))
            enabled = true; scene.settle(); scene.click("ce-clip-position-exact-apply")
            assertEquals(listOf(1L), applied)
            scene.click("ce-clip-position-exact"); scene.click("ce-clip-position-exact-seconds"); scene.text("0.010")
            scene.click("ce-clip-position-exact-apply"); assertEquals(listOf(1L, 480L), applied)
            scene.click("ce-clip-position-exact"); scene.text("80000001")
            identity = "second"; scene.settle()
            assertNull(scene.tag("ce-clip-position-exact-input"))
            assertEquals(listOf(1L, 480L), applied)
        } finally { scene.close() }
    }
    private fun ImageComposeScene.tag(tag: String): SemanticsNode? {
        fun find(node: SemanticsNode): SemanticsNode? = if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) node else node.children.firstNotNullOfOrNull(::find)
        return semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }
    }
    private suspend fun ImageComposeScene.settle() { repeat(6) { render().close(); delay(10) } }
    private suspend fun ImageComposeScene.click(tag: String) { assertNotNull(tag(tag)).config[SemanticsActions.OnClick].action!!.invoke(); settle() }
    private suspend fun ImageComposeScene.text(value: String) { assertNotNull(tag("ce-clip-position-exact-input")).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)); settle() }
}
