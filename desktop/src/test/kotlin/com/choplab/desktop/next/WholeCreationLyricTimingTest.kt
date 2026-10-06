package com.choplab.desktop.next

import com.choplab.core.DocumentState
import com.choplab.core.edit.Intent
import com.choplab.core.edit.Reducer
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.engine.Tempo
import com.choplab.ui.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

/** Observed Windows start offsets and ranges, then the longer UI-guide-compatible capture; no recording bytes. */
class WholeCreationLyricTimingTest {
    @Test fun lateCapturedTakesRejectZeroBasedLyricsThenAcceptAnExplicitUnshortenedPlacement() = runBlocking<Unit> {
        for (sixSeconds in listOf(false, true)) {
            val before = project(sixSeconds)
            val document = MutableStateFlow(DocumentState(before, 0))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val controller = VocalTakeController(document, MutableStateFlow(VocalAvailability.EDITABLE), object : VocalTakePorts {
                override suspend fun render(project: Project, draft: VocalCompDraft, name: String): Asset? = null
                override suspend fun apply(intent: Intent, expectedRevision: Long) = false
                override suspend fun previewTake(project: Project, takeId: String) = false
                override suspend fun previewComp(asset: Asset) = false
                override fun stopPreview() = Unit
            }, scope)
            try {
                assertTrue(controller.dispatch(VocalAction.SelectTake("take-a")))
                assertFalse(controller.dispatch(VocalAction.FromLyrics))
                assertEquals(VocalProblem.TAKE_TOO_SHORT, controller.state.value.problem)
                assertNull(controller.state.value.draft)
                val placed = NextWholeCreationSelfTest.recordedLyricTiming(before, before.lyrics)
                val length = if (sixSeconds) 3840L else 960L
                assertEquals(listOf(960L to 960L + length, 960L + length to 960L + length * 2), placed.map { it.startTick to it.endTick })
                assertEquals(before.lyrics.map { it.endTick - it.startTick }, placed.map { it.endTick - it.startTick })
                assertEquals(before.lyrics.map { it.id to it.text }, placed.map { it.id to it.text })
                val after = Reducer.reduce(before, Intent.SetLyrics(placed)).project
                assertEquals(before.takes, after.takes)
                assertEquals(before.assets, after.assets)
                document.value = DocumentState(after, 1)
                assertTrue(controller.dispatch(VocalAction.Reload))
                assertTrue(controller.dispatch(VocalAction.FromLyrics))
                val draft = assertNotNull(controller.state.value.draft)
                assertEquals(2, draft.segments.size)
                assertTrue(controller.dispatch(VocalAction.Choose(draft.segments.last().id, "take-b")))
                VocalCompEdits.validate(after, assertNotNull(controller.state.value.draft))
            } finally { controller.close(); scope.cancel() }
        }
    }

    @Test fun insufficientCommonCoverageFailsWithoutTruncatingLyricsOrChangingRawTakes() {
        val before = project().let { p -> p.copy(takes = p.takes.map { it.copy(range = FrameRange(it.range.start, it.range.start + 30_000)) }.frozen()) }
        val error = assertFailsWith<IllegalStateException> { NextWholeCreationSelfTest.recordedLyricTiming(before, before.lyrics) }
        assertTrue(error.message.orEmpty().contains("TAKE_TOO_SHORT"))
        assertTrue(error.message.orEmpty().contains("takeFrames="))
        assertTrue(error.message.orEmpty().contains("lyricTicks="))
        assertEquals(listOf(0L to 3840L, 3840L to 7680L), before.lyrics.map { it.startTick to it.endTick })
        assertEquals(listOf(870L, 824L), before.takes.map { it.timelineStartFrame })
    }

    private fun project(sixSeconds: Boolean = true): Project {
        val firstEnd = if (sixSeconds) 287_370L else 95_370L
        val total = if (sixSeconds) 574_786L else 190_786L
        val ticks = if (sixSeconds) 3840L else 960L
        val asset = Asset("a".repeat(64), "wav", 44 + total * 8, 48_000, 2, total, "Synthetic voice")
        return Project(tempo = Tempo(98_000, 620), assets = frozenListOf(asset),
            tracks = frozenListOf(Track("voice", "VOICE", TrackKind.VOCAL)),
            takes = frozenListOf(Take("take-a", "voice", asset.hash, FrameRange(0, firstEnd), 870),
                Take("take-b", "voice", asset.hash, FrameRange(firstEnd, total), 824)),
            lyrics = frozenListOf(LyricLine("line-a", "First", 0, ticks), LyricLine("line-b", "Second", ticks, ticks * 2)))
    }
}
