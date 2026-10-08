package com.choplab.ui.ai

import com.choplab.core.DocumentState
import com.choplab.core.ai.*
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class VocalGuideControllerTest {
    @Test fun localDraftChangesRetainIndependentPreparedRowsAndRetimeWithoutSynthesis() = runBlocking {
        val one = structured()
        val line = one.lyrics.single()
        val section = one.lyricStructure!!.sections.single()
        val project = one.copy(lyrics = frozenListOf(line, line.copy(id = "second", startTick = 3840, endTick = 7680)),
            lyricStructure = one.lyricStructure!!.copy(sections = frozenListOf(section.copy(lines =
                (section.lines + section.lines.single().copy(lineId = "second")).frozen()))))
        val f = Fixture(project)
        try {
            waitUntil { !f.controller.state.value.loadingVoices }
            assertTrue(f.controller.prepare()); waitUntil { f.controller.state.value.phase == VocalGuidePhase.READY }
            val original = f.controller.state.value.rows.map { assertNotNull(it.prepared) }
            val calls = f.preparations.get()
            assertTrue(f.controller.placement(title = "New title"))
            assertTrue(f.controller.mode(null, FlowMode.ONE_BAR))
            assertTrue(f.controller.voice(f.controller.state.value.voice!!))
            assertTrue(f.controller.settings(f.controller.state.value.settings))
            assertTrue(f.controller.reading(line.id, f.controller.state.value.rows.first().reading))
            assertEquals(original, f.controller.state.value.rows.map { it.prepared })
            assertTrue(f.controller.placement(startBeat = 9))
            assertEquals(calls, f.preparations.get())
            f.controller.state.value.rows.forEachIndexed { index, row ->
                assertEquals(original[index].asset, row.prepared!!.asset)
                assertEquals(original[index].line.startTick + 7680, row.prepared!!.line.startTick)
                assertEquals(original[index].line.words.single().startTick + 7680, row.prepared!!.line.words.single().startTick)
            }
            val kept = f.controller.state.value.rows.last().prepared
            assertTrue(f.controller.reading(line.id, "かわのながれ", true))
            assertNull(f.controller.state.value.rows.first().prepared)
            assertEquals(kept, f.controller.state.value.rows.last().prepared)
            assertTrue(f.controller.prepare()); waitUntil { f.controller.state.value.phase == VocalGuidePhase.READY }
            assertEquals(calls + 1, f.preparations.get())
            f.controller.cancel(); waitUntil { f.controller.state.value.failure?.problem == TtsProblem.CANCELLED }
            assertEquals(VocalGuidePhase.READY, f.controller.state.value.phase)
            assertTrue(f.controller.state.value.rows.all { it.prepared != null })
            assertTrue(f.controller.settings(TtsSettings(ratePermille = 1100)))
            assertTrue(f.controller.state.value.rows.all { it.prepared == null })
            assertEquals(0, f.session.undoCount)
        } finally { f.close() }
    }
    @Test fun transientStopAndApplyFailuresRetryPreparedAudioWithOneUndoAndStaleResultsRemainBlocked() = runBlocking {
        for (stop in listOf(false, true)) {
            val f = Fixture(structured())
            try {
                waitUntil { !f.controller.state.value.loadingVoices }
                assertTrue(f.controller.prepare()); waitUntil { f.controller.state.value.phase == VocalGuidePhase.READY }
                f.controller.confirmDensity(true)
                val prepared = f.controller.state.value.rows.single().prepared
                val before = f.document.value
                if (stop) f.stopFailures = 1 else f.applyFailures = 1
                assertFalse(f.controller.apply())
                assertEquals(before, f.document.value); assertEquals(0, f.session.undoCount)
                assertEquals(VocalGuidePhase.READY, f.controller.state.value.phase)
                assertNotNull(f.controller.state.value.failure)
                assertEquals(prepared, f.controller.state.value.rows.single().prepared)
                assertTrue(f.controller.apply())
                assertEquals(1, f.preparations.get()); assertEquals(1, f.session.undoCount)
                val undo = f.session.planUndo()!!; undo.effects.indices.forEach { f.session.acknowledge(undo, it) }; f.session.commit(undo)
                assertEquals(before.project, f.session.project)
            } finally { f.close() }
        }
        val stale = Fixture(structured())
        try {
            waitUntil { !stale.controller.state.value.loadingVoices }
            assertTrue(stale.controller.prepare()); waitUntil { stale.controller.state.value.phase == VocalGuidePhase.READY }
            stale.document.value = stale.document.value.copy(revision = 9)
            waitUntil { stale.controller.state.value.failure?.problem == TtsProblem.STALE_DOCUMENT }
            assertFalse(stale.controller.apply()); assertEquals(0, stale.session.undoCount)
        } finally { stale.close() }
    }

    @Test fun manualAndChangedReadingsRequireConfirmationAndDensityNeedsAnExplicitChoice() = runBlocking<Unit> {
        val f = Fixture(Project(lyrics = frozenListOf(LyricLine("line", "川の歌", 0, 960))))
        try {
            waitUntil { !f.controller.state.value.loadingVoices }
            assertNull(f.controller.state.value.plan)
            assertFalse(f.controller.prepare())
            assertTrue(f.controller.placement(title = "川"))
            assertFalse(f.controller.reading("line", "川の歌", true), "Kanji is not silently treated as a kana reading")
            assertTrue(f.controller.reading("line", "かわのうた", true))
            assertNotNull(f.controller.state.value.plan)
            assertTrue(f.controller.mode(null, FlowMode.TWO_BARS))
            assertEquals(7_680L, f.controller.state.value.plan!!.rows.single().line.endTick)
            assertTrue(f.controller.state.value.plan!!.hasDensityAdvice)
            assertTrue(f.controller.prepare()); waitUntil { f.controller.state.value.phase == VocalGuidePhase.READY }
            assertEquals(0, f.applies.get())
            assertFalse(f.controller.apply())
            assertEquals(TtsProblem.DENSITY_CONFIRMATION, f.controller.state.value.failure?.problem)
            f.controller.confirmDensity(true)
            assertTrue(f.controller.apply())
            assertEquals(1, f.applies.get())
            assertEquals("川", f.applied!!.structure.title)
            assertEquals("かわのうた", f.applied!!.structure.sections.single().lines.single().reading)
        } finally { f.close() }

        val p = structured()
        val stale = Fixture(p.copy(lyrics = p.lyrics.map { it.copy(text = "別の本文") }.frozen()))
        try {
            waitUntil { !stale.controller.state.value.loadingVoices }
            assertFalse(stale.controller.state.value.rows.single().confirmed)
            assertFalse(stale.controller.prepare())
            assertTrue(stale.controller.reading(p.lyrics.single().id, "べつのほんぶん", true))
            assertEquals("別の本文", stale.controller.state.value.plan!!.structure.sections.single().lines.single().text)
        } finally { stale.close() }
    }

    @Test fun cancellationLateGenerationRevisionChangesAndRecordingNeverPublishOrApply() = runBlocking<Unit> {
        val f = Fixture(structured())
        try {
            waitUntil { !f.controller.state.value.loadingVoices }
            f.gate = CompletableDeferred()
            assertTrue(f.controller.prepare())
            withTimeout(3_000) { f.entered.await() }
            f.controller.cancel()
            f.gate!!.complete(Unit)
            withTimeout(3_000) { f.returned.await() }
            waitUntil { f.controller.state.value.failure?.problem == TtsProblem.CANCELLED }
            assertNull(f.controller.state.value.rows.single().prepared)
            assertFalse(f.controller.apply())
            f.availability.value = VocalGuideAvailability.RECORDING
            assertFalse(f.controller.prepare())
            assertEquals(TtsProblem.RECORDING, f.controller.state.value.failure?.problem)
            f.availability.value = VocalGuideAvailability.EDITABLE
            f.document.value = f.document.value.copy(revision = 9)
            waitUntil { f.controller.state.value.failure?.problem == TtsProblem.STALE_DOCUMENT }
            assertFalse(f.controller.prepare()); assertFalse(f.controller.apply())
            assertEquals(0, f.applies.get())
        } finally { f.close() }
        assertEquals(1, f.closes.get())
    }

    @Test fun closeCancelsAPendingApplyAndModeChangesDiscardPreparedAudio() = runBlocking<Unit> {
        val f = Fixture(structured())
        try {
            waitUntil { !f.controller.state.value.loadingVoices }
            assertTrue(f.controller.prepare()); waitUntil { f.controller.state.value.phase == VocalGuidePhase.READY }
            assertTrue(f.controller.mode(null, FlowMode.DOUBLE_TIME))
            assertNull(f.controller.state.value.rows.single().prepared)
            assertEquals(1920L, f.controller.state.value.plan!!.rows.single().line.endTick)
            assertTrue(f.controller.prepare(regenerate = true)); waitUntil { f.controller.state.value.phase == VocalGuidePhase.READY }
            assertTrue(f.regenerated)
            f.applyGate = CompletableDeferred()
            val applying = async { runCatching { f.controller.apply() } }
            withTimeout(3_000) { f.applyEntered.await() }
            f.controller.close(); f.controller.close()
            assertTrue(withTimeout(3_000) { applying.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, f.applies.get())
            assertEquals(VocalGuidePhase.CLOSED, f.controller.state.value.phase)
        } finally { f.applyGate?.complete(Unit); f.close() }
        assertEquals(1, f.closes.get())
    }

    private class Fixture(project: Project) {
        val session = EditSession(project)
        val document = MutableStateFlow(DocumentState(project, 8))
        val availability = MutableStateFlow(VocalGuideAvailability.EDITABLE)
        val closes = AtomicInteger(); val applies = AtomicInteger(); val preparations = AtomicInteger()
        var stopFailures = 0; var applyFailures = 0
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var applyGate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>(); val applyEntered = CompletableDeferred<Unit>()
        @Volatile var applied: Intent.ApplyVocalGuide? = null
        @Volatile var regenerated = false
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preview = object : VocalPreviewPort {
            override val state = MutableStateFlow(VocalPreviewState())
            override suspend fun start(asset: Asset, expectedRevision: Long) = TtsResult.Success(Unit)
            override suspend fun stop(): TtsResult<Unit> = if (stopFailures-- > 0) ttsFailure(TtsProblem.FAILED) else TtsResult.Success(Unit)
            override fun requestStop() = Unit
            override fun frame() = 0L
        }
        val controller = VocalGuideController(document, availability, object : VocalSynthesisPort {
            override suspend fun voices() = TtsResult.Success(frozenListOf(TtsVoice(TtsEngine("device", "1", "system", "1"), "voice", "Voice", "ja-JP", "1", LyricLanguage.JAPANESE)))
            override suspend fun prepare(row: FlowRow, tempo: Tempo, voice: TtsVoice, settings: TtsSettings, regenerate: Boolean): TtsResult<PreparedVocalLine> {
                preparations.incrementAndGet(); regenerated = regenerate
                gate?.let { withContext(NonCancellable) { entered.complete(Unit); it.await(); returned.complete(Unit) } }
                return TtsResult.Success(PreparedVocalLine(row.line.copy(words = frozenListOf(LyricWord(row.line.text, row.line.startTick, row.line.endTick))),
                    Asset("a".repeat(64), "wav", 100, 48_000, 2, 96_000, "Guide", AssetRole.RENDERED), 1.0, 0, false, false))
            }
            override fun close() { closes.incrementAndGet() }
        }, preview, object : VocalGuideActions {
            override suspend fun prepareAllowed(expectedRevision: Long) = TtsResult.Success(Unit)
            override suspend fun preview(asset: Asset, expectedRevision: Long) = TtsResult.Success(Unit)
            override suspend fun apply(intent: Intent.ApplyVocalGuide, expectedRevision: Long): Boolean {
                applyGate?.let { applyEntered.complete(Unit); it.await() }
                if (document.value.revision != expectedRevision) return false
                if (applyFailures-- > 0) return false
                val plan = session.plan(intent); plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan)
                document.value = DocumentState(session.project, expectedRevision + 1, canUndo = true)
                applied = intent; applies.incrementAndGet(); return true
            }
        }, scope)
        fun close() { controller.close(); scope.cancel() }
    }
    private companion object {
        fun structured(): Project {
            val placement = LyricProposal("川", LyricLanguage.JAPANESE, frozenListOf(ProposalSection("A", LyricSectionKind.VERSE, 1,
                frozenListOf(ProposalLine.create("川の歌", "かわのうた", LyricLanguage.JAPANESE))))).placeStructured(0, 4, "line")
            return Project(lyrics = placement.lines, lyricStructure = placement.structure)
        }
        suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }
    }
}
