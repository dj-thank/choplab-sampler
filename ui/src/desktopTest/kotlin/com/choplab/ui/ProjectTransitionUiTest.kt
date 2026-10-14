@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlin.test.*

class ProjectTransitionUiTest {
    @Test fun openConfirmationCarriesRevisionAndClosingStatusDoesNotOwnAnotherModalWindow() = runBlocking<Unit> {
        var state by mutableStateOf(ContinuousEditorFixture.state().copy(openProjectRevision = 9, documentRevision = 9))
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(1440, 1024, density = Density(1f), coroutineContext = coroutineContext) {
            ContinuousEditor(state, actions::add, ContinuousEditorFixture::readout)
        }
        try {
            scene.settle()
            scene.click("ce-open-save"); assertEquals(ContinuousEditorAction.ConfirmOpenProject(true, 9), actions.last())
            scene.click("ce-open-discard"); assertEquals(ContinuousEditorAction.ConfirmOpenProject(false, 9), actions.last())
            state = state.copy(documentRevision = 10); scene.settle()
            assertTrue(scene.tag("ce-open-save")!!.config.contains(SemanticsProperties.Disabled))
            assertTrue(scene.tag("ce-open-discard")!!.config.contains(SemanticsProperties.Disabled))
            scene.click("ce-open-cancel"); assertEquals(ContinuousEditorAction.CancelOpenProject, actions.last())
            state = state.copy(openProjectRevision = null, closing = true); scene.settle()
            assertEquals(1, scene.semanticsOwners.size, "Closing status leaves native failure confirmation free to open")
            assertEquals(LiveRegionMode.Polite, scene.tag("ce-closing-project")!!.config[SemanticsProperties.LiveRegion])
            state = state.copy(closing = false); scene.settle()
            assertNull(scene.tag("ce-closing-project"))
        } finally { scene.close() }
    }
    private fun ImageComposeScene.tag(tag: String): SemanticsNode? {
        fun find(node: SemanticsNode): SemanticsNode? = if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) node else node.children.firstNotNullOfOrNull(::find)
        return semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }
    }
    private suspend fun ImageComposeScene.settle() { repeat(8) { render().close(); delay(10) } }
    private suspend fun ImageComposeScene.click(tag: String) { assertNotNull(tag(tag)).config[SemanticsActions.OnClick].action!!.invoke(); settle() }
}
