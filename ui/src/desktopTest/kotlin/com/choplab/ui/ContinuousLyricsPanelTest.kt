@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.choplab.core.model.*
import com.choplab.core.lyrics.LrcFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class ContinuousLyricsPanelTest {
    @Test fun bothExportsResolveTextLineAndWordDraftsWithoutDiscardingOnKeepOrSavedExport() = runBlocking {
        for ((tag, value) in listOf("ce-lyric-text" to "draft", "ce-lyric-time-start" to "10", "ce-lyric-word-0-start" to "10"))
            for (format in LrcFormat.entries) {
                val line = LyricLine("one", "a b", 0, 1920, frozenListOf(LyricWord("a", 0, 960), LyricWord(" b", 960, 1920)))
                val state = ContinuousEditorState(documentRevision = 4, lyrics = ContinuousLyricsState(open = true, lines = listOf(line), selectedId = "one"),
                    capabilities = setOf(ContinuousCapability.LYRICS_EDIT, ContinuousCapability.LYRICS_FILES))
                val actions = mutableListOf<ContinuousEditorAction>()
                val scene = ImageComposeScene(width = 1200, height = 1000, coroutineContext = coroutineContext) {
                    CETheme { CELyricsPanel(state, actions::add, { ContinuousEditorReadout() }, 0) }
                }
                fun click(id: String) { scene.tag(id)!!.config[SemanticsActions.OnClick].action!!.invoke() }
                val export = if (format == LrcFormat.STANDARD) "ce-lyrics-export-standard" else "ce-lyrics-export"
                try {
                    scene.settle(); scene.tag(tag)!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)); scene.settle()
                    click(export); scene.settle(); assertTrue(actions.isEmpty())
                    click("ce-lyrics-export-keep"); scene.settle()
                    assertEquals(value, scene.tag(tag)!!.config[SemanticsProperties.EditableText].text)
                    click(export); scene.settle(); click("ce-lyrics-export-saved"); scene.settle()
                    assertEquals(ContinuousEditorAction.Lyrics(LyricAction.Export(format)), actions.single())
                    assertEquals(value, scene.tag(tag)!!.config[SemanticsProperties.EditableText].text)
                    actions.clear(); click(export); scene.settle(); click("ce-lyrics-apply-export"); scene.settle()
                    val edit = assertIs<LyricAction.ApplyDraftAndExport>(assertIs<ContinuousEditorAction.Lyrics>(actions.single()).action)
                    assertEquals(4L, edit.expectedRevision); assertEquals(format, edit.format); assertEquals(1, edit.edits.size)
                } finally { scene.close() }
            }
    }

    @Test fun unappliedTextAndWordTimesRequireExplicitDiscardBeforeLeaving() = runBlocking<Unit> {
        val line = LyricLine("one", "a b", 0, 1920, frozenListOf(LyricWord("a", 0, 960), LyricWord(" b", 960, 1920)))
        val state = mutableStateOf(ContinuousEditorState(lyrics = ContinuousLyricsState(open = true,
            lines = listOf(line, LyricLine("two", "next", 1920, 3840)), selectedId = "one"), capabilities = setOf(ContinuousCapability.LYRICS_EDIT)))
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 1200, height = 1000, coroutineContext = coroutineContext) {
            CETheme { CELyricsPanel(state.value, actions::add, { ContinuousEditorReadout() }, 0) }
        }
        fun click(tag: String) { scene.tag(tag)!!.config[SemanticsActions.OnClick].action!!.invoke() }
        try {
            scene.settle()
            scene.tag("ce-lyric-text")!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("draft")); scene.settle()
            click("ce-lyric-two"); scene.settle(); assertTrue(actions.isEmpty())
            click("ce-lyrics-keep-editing"); scene.settle()
            assertEquals("draft", scene.tag("ce-lyric-text")!!.config[SemanticsProperties.EditableText].text)
            click("ce-lyrics-close"); scene.settle(); assertTrue(actions.isEmpty())
            click("ce-lyrics-discard-draft"); assertEquals(ContinuousEditorAction.Lyrics(LyricAction.Close), actions.single())
            // Simulate a close/reopen and verify word drafts receive the same protection.
            state.value = state.value.copy(lyrics = state.value.lyrics.copy(open = false)); scene.settle()
            state.value = state.value.copy(lyrics = state.value.lyrics.copy(open = true)); scene.settle(); actions.clear()
            scene.tag("ce-lyric-word-0-start")!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("12")); scene.settle()
            click("ce-lyric-select-word-1"); scene.settle(); assertTrue(actions.isEmpty())
            assertNotNull(scene.tag("ce-lyrics-keep-editing"))
            click("ce-lyrics-keep-editing"); scene.settle()
            assertEquals("12", scene.tag("ce-lyric-word-0-start")!!.config[SemanticsProperties.EditableText].text)
        } finally { scene.close() }
    }

    @Test fun lyricDeleteConfirmationResetsWhenTheDisplayedRevisionChanges() = runBlocking<Unit> {
        val line = LyricLine("one", "first", 0, 1920)
        val state = mutableStateOf(ContinuousEditorState(documentRevision = 1, lyrics = ContinuousLyricsState(open = true,
            lines = listOf(line), selectedId = "one"), capabilities = setOf(ContinuousCapability.LYRICS_EDIT)))
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 1200, height = 1000, coroutineContext = coroutineContext) {
            CETheme { CELyricsPanel(state.value, actions::add, { ContinuousEditorReadout() }, 0) }
        }
        fun delete() { scene.tag("ce-lyric-delete")!!.config[SemanticsActions.OnClick].action!!.invoke() }
        try {
            scene.settle(); delete(); scene.settle(); assertTrue(actions.isEmpty())
            val changed = line.copy(text = "changed")
            state.value = state.value.copy(documentRevision = 2, lyrics = state.value.lyrics.copy(lines = listOf(changed)))
            scene.settle(); delete(); scene.settle(); assertTrue(actions.isEmpty(), "Old confirmation cannot delete changed content")
            delete(); scene.settle()
            assertEquals(ContinuousEditorAction.Lyrics(LyricAction.Delete("one", changed, 2)), actions.single())
        } finally { scene.close() }
    }


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
                state.value = state.value.copy(lyrics = state.value.lyrics.copy(lines = listOf(lines.first().copy(text = "新しい歌詞", words = frozenListOf()), lines.last())))
                scene.settle()
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
