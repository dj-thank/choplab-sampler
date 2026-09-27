@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.choplab.core.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class ContinuousLyricsPanelTest {
    @Test fun beatEntryOpensAndClosesTheEditorAndReflectsEditingAvailability() = runBlocking<Unit> {
        val state = mutableStateOf(ContinuousEditorFixture.state().copy(capabilities = ContinuousCapability.entries.toSet()))
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
            ContinuousEditor(state.value, { action ->
                actions += action
                if (action is ContinuousEditorAction.Lyrics) state.value = state.value.copy(
                    lyrics = state.value.lyrics.copy(open = action.action == LyricAction.Open))
            }, ContinuousEditorFixture::readout)
        }
        try {
            scene.settle()
            assertNull(scene.tag("ce-lyrics-panel"))
            scene.tag("ce-lyrics-open")!!.config[SemanticsActions.OnClick].action!!.invoke()
            scene.settle()
            assertEquals(ContinuousEditorAction.Lyrics(LyricAction.Open), actions.single())
            assertNotNull(scene.tag("ce-lyrics-panel"))
            scene.tag("ce-lyrics-close")!!.config[SemanticsActions.OnClick].action!!.invoke()
            scene.settle()
            assertNull(scene.tag("ce-lyrics-panel"))
            state.value = state.value.copy(recordingVoice = true, capabilities = emptySet())
            scene.settle()
            assertTrue(scene.tag("ce-lyrics-open")!!.config.contains(SemanticsProperties.Disabled))
        } finally { scene.close() }
    }

    @Test fun currentNextTapEditingAndImportConfirmationRemainReachableAtLargeText() = runBlocking<Unit> {
        val lines = listOf(LyricLine("one", "音楽", 0, 1920, frozenListOf(LyricWord("音", 0, 960), LyricWord("楽", 960, 1920))),
            LyricLine("two", "歌おう", 1920, 3840))
        for ((width, height, font) in listOf(Triple(1200, 1000, 1f), Triple(390, 844, 2f))) {
            val actions = mutableListOf<ContinuousEditorAction>()
            val state = mutableStateOf(ContinuousEditorState(lyrics = ContinuousLyricsState(open = true, lines = lines, selectedId = "one"),
                capabilities = setOf(ContinuousCapability.LYRICS_EDIT, ContinuousCapability.LYRICS_FILES, ContinuousCapability.SONG_PLAYBACK)))
            val clock = mutableStateOf(ContinuousEditorReadout(songFrame = 0))
            val refresh = mutableStateOf(0L)
            val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                CETheme { CELyricsPanel(state.value, actions::add, { clock.value }, refresh.value) }
            }
            try {
                scene.settle()
                assertFalse(scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any { '%' in it.text }, "Resource placeholders must be resolved")
                assertNotNull(scene.tag("ce-lyric-current-one"))
                assertNotNull(scene.tag("ce-lyric-next-two"))
                clock.value = ContinuousEditorReadout(songFrame = 48_000)
                refresh.value++
                scene.settle()
                assertNull(scene.tag("ce-lyric-current-one"), "Line ends are exclusive")
                assertNotNull(scene.tag("ce-lyric-current-two"))
                scene.tag("ce-lyric-text")!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("新しい歌詞"))
                scene.settle()
                assertTrue(actions.isEmpty(), "Typing is a draft until Apply")
                scene.tag("ce-lyric-apply-text")!!.config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(ContinuousEditorAction.Lyrics(LyricAction.Text("one", "新しい歌詞")), actions.single())
                actions.clear()
                scene.tag("ce-lyric-tap")!!.config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(ContinuousEditorAction.Lyrics(LyricAction.Tap("one", 48_000)), actions.single())
                actions.clear()
                state.value = state.value.copy(lyrics = state.value.lyrics.copy(preview = LyricImportPreview(listOf(lines.last()), 120_000)))
                scene.settle()
                assertTrue(actions.isEmpty())
                assertNotNull(scene.tag("ce-lyrics-preview"))
                assertFalse(scene.tag("ce-lyrics-preview")!!.config[SemanticsProperties.Text].any { '%' in it.text })
                scene.tag("ce-lyrics-apply-import")!!.config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(ContinuousEditorAction.Lyrics(LyricAction.ApplyImport), actions.single())
                scene.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }.forEach { it(0f, 100_000f) }
                scene.settle()
                val close = scene.tag("ce-lyrics-close")!!.boundsInRoot
                assertTrue(close.height >= 48f && close.bottom <= height && close.top >= 0)
                val output = File(System.getProperty("choplab.ui.evidenceDir"), "lyrics").apply { mkdirs() }
                scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { File(output, "lyrics-${width}-font${(font * 100).toInt()}.png").writeBytes(it.bytes) } }
            } finally { scene.close() }
        }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private suspend fun ImageComposeScene.settle() { repeat(8) { render(System.nanoTime()).close(); delay(12) } }
}
