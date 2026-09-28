package com.choplab.ui.pattern

import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.core.pattern.*
import com.choplab.ui.pattern.PatternTestFixture.Companion.waitUntil
import kotlinx.coroutines.*
import kotlin.test.*

class StepPatternControllerTest {
    @Test fun viewColumnsAreIndependentFromLengthAndEditsKeepTheExplicitlySelectedPad() = runBlocking<Unit> {
        val fixture = PatternTestFixture(selectedPad = 1)
        try {
            val before = fixture.document.value
            assertTrue(fixture.action(PatternAction.Resize(8)))
            for (columns in listOf(16, 32, 64)) {
                assertTrue(fixture.action(PatternAction.Columns(columns)))
                assertEquals(8, fixture.controller.state.value.draft.bars)
                assertEquals(128 / columns, fixture.controller.state.value.pages)
                assertEquals(before, fixture.document.value)
            }
            assertTrue(fixture.action(PatternAction.Page(1)))
            assertTrue(fixture.action(PatternAction.Velocity(.5f)))
            assertTrue(fixture.action(PatternAction.Toggle(127)))
            assertEquals(listOf(Note(30_480, 1, .5f)), fixture.controller.state.value.draft.notes)
            assertEquals(1, fixture.selection.value.padId)
            assertTrue(fixture.action(PatternAction.Save))
            assertEquals(1, fixture.edits.size); assertIs<Intent.PutPattern>(fixture.edits.single())
            assertEquals(8, fixture.document.value.project.patterns.first().bars)
            assertFalse(fixture.action(PatternAction.Save), "A repeated Apply has no edit to commit")
            assertTrue(fixture.action(PatternAction.SelectPad(16)))
            waitUntil { fixture.controller.state.value.selectedPadId == 16 }
            assertFalse(fixture.action(PatternAction.Toggle(0)))
            assertEquals(PatternProblem.EMPTY_PAD, fixture.controller.state.value.problem)
            assertEquals(1, fixture.edits.size)
        } finally { fixture.close() }
    }

    @Test fun multiplePatternsAndQueueRemainDraftsUntilTheirSeparateSingleApply() = runBlocking<Unit> {
        val fixture = PatternTestFixture()
        try {
            fixture.action(PatternAction.Toggle(0))
            assertFalse(fixture.action(PatternAction.New("B")), "Do not silently replace an unsaved draft")
            fixture.action(PatternAction.Save)
            fixture.action(PatternAction.Repeats(2)); fixture.action(PatternAction.Queue)
            fixture.action(PatternAction.New("B", copy = true))
            fixture.action(PatternAction.Toggle(4))
            assertFalse(fixture.action(PatternAction.Queue))
            fixture.action(PatternAction.Save)
            assertEquals(2, fixture.document.value.project.patterns.size)
            fixture.action(PatternAction.Repeats(1)); fixture.action(PatternAction.Queue)
            assertEquals(listOf(2, 1), fixture.controller.state.value.sequence.map { it.repeats })
            val beforePlace = fixture.document.value.project
            assertTrue(fixture.action(PatternAction.Place("Patterns")))
            assertEquals(3, fixture.edits.size)
            val arrangement = assertIs<Intent.SetArrangement>(fixture.edits.last())
            assertEquals(4, arrangement.clips.size)
            assertEquals(listOf(0L, 3840L, 7680L, 8640L), arrangement.clips.map { it.startTick })
            assertEquals(beforePlace.patterns, fixture.document.value.project.patterns)
            assertTrue(fixture.controller.state.value.sequence.isEmpty(), "A second click cannot repeat the same queued transaction")
            assertFalse(fixture.action(PatternAction.Place("Patterns")))
            assertEquals(3, fixture.edits.size)
        } finally { fixture.close() }
    }

    @Test fun discardShortenConfirmationAndBusyRecordingDoNotChangeTheDocument() = runBlocking<Unit> {
        val fixture = PatternTestFixture()
        try {
            val before = fixture.document.value
            fixture.action(PatternAction.Resize(8)); fixture.action(PatternAction.Toggle(127))
            assertFalse(fixture.action(PatternAction.Resize(1)))
            assertEquals(1, fixture.controller.state.value.trimBars)
            assertEquals(8, fixture.controller.state.value.draft.bars)
            assertFalse(fixture.action(PatternAction.Save))
            fixture.action(PatternAction.Discard)
            assertEquals(before.project.patterns.first(), fixture.controller.state.value.draft)
            for (availability in listOf(PatternAvailability.BUSY, PatternAvailability.RECORDING)) {
                fixture.availability.value = availability
                assertFalse(fixture.action(PatternAction.Toggle(0)))
                assertFalse(fixture.action(PatternAction.New("B")))
                assertEquals(before, fixture.document.value)
                assertEquals(if (availability == PatternAvailability.BUSY) PatternProblem.BUSY else PatternProblem.RECORDING, fixture.controller.state.value.problem)
            }
            fixture.availability.value = PatternAvailability.EDITABLE
            fixture.document.value = before.copy(revision = 1)
            assertFalse(fixture.action(PatternAction.Toggle(0)))
            assertEquals(PatternProblem.STALE_DOCUMENT, fixture.controller.state.value.problem)
            assertTrue(fixture.action(PatternAction.Reload))
            assertEquals(1, fixture.controller.state.value.revision)
            assertTrue(fixture.edits.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun partialRenderFailureAndCancellationStaleRecordingOrCloseNeverPlacePartialAudio() = runBlocking<Unit> {
        for (mode in listOf("failure", "cancel", "stale", "recording", "close")) {
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val initial = PatternTestFixture.project().copy(patterns = frozenListOf(Pattern("pattern-1", notes = frozenListOf(Note(0, 0), Note(240, 1)))))
            val fixture = PatternTestFixture(initial, render = { number, pad, source, request ->
                if (number == 2) {
                    reached.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    if (mode == "failure") null else PatternTestFixture.rendered(pad, source, request)
                } else PatternTestFixture.rendered(pad, source, request)
            })
            try {
                fixture.action(PatternAction.Queue)
                val placing = async { runCatching { fixture.action(PatternAction.Place("P")) }.getOrDefault(false) }
                withTimeout(3_000) { reached.await() }
                when (mode) {
                    "cancel" -> assertTrue(fixture.action(PatternAction.Cancel))
                    "stale" -> fixture.document.value = fixture.document.value.copy(revision = 1)
                    "recording" -> fixture.availability.value = PatternAvailability.RECORDING
                    "close" -> fixture.controller.close()
                }
                release.complete(Unit)
                assertFalse(placing.await(), mode)
                assertTrue(fixture.edits.isEmpty(), mode)
                assertEquals(initial, fixture.document.value.project, mode)
                if (mode == "failure") assertEquals(PatternProblem.RENDER_FAILED, fixture.controller.state.value.problem)
                if (mode == "close") assertEquals(PatternPhase.CLOSED, fixture.controller.state.value.phase)
            } finally { release.complete(Unit); fixture.close() }
        }
    }
}
