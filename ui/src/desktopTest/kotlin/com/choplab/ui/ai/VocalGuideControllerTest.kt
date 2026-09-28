package com.choplab.ui.ai

import com.choplab.core.DocumentState
import com.choplab.core.ai.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class VocalGuideControllerTest {
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
        val document = MutableStateFlow(DocumentState(project, 8))
        val availability = MutableStateFlow(VocalGuideAvailability.EDITABLE)
        val closes = AtomicInteger(); val applies = AtomicInteger()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var applyGate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>(); val applyEntered = CompletableDeferred<Unit>()
        @Volatile var applied: Intent.ApplyVocalGuide? = null
        @Volatile var regenerated = false
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preview = object : VocalPreviewPort {
            override val state = MutableStateFlow(VocalPreviewState())
            override suspend fun start(asset: Asset, expectedRevision: Long) = TtsResult.Success(Unit)
            override suspend fun stop() = TtsResult.Success(Unit)
            override fun requestStop() = Unit
            override fun frame() = 0L
        }
        val controller = VocalGuideController(document, availability, object : VocalSynthesisPort {
            override suspend fun voices() = TtsResult.Success(frozenListOf(TtsVoice(TtsEngine("device", "1", "system", "1"), "voice", "Voice", "ja-JP", "1", LyricLanguage.JAPANESE)))
            override suspend fun prepare(row: FlowRow, tempo: Tempo, voice: TtsVoice, settings: TtsSettings, regenerate: Boolean): TtsResult<PreparedVocalLine> {
                regenerated = regenerate
                gate?.let { withContext(NonCancellable) { entered.complete(Unit); it.await(); returned.complete(Unit) } }
                return TtsResult.Success(PreparedVocalLine(row.line,
                    Asset("a".repeat(64), "wav", 100, 48_000, 2, 96_000, "Guide", AssetRole.RENDERED), 1.0, 0, false, false))
            }
            override fun close() { closes.incrementAndGet() }
        }, preview, object : VocalGuideActions {
            override suspend fun prepareAllowed(expectedRevision: Long) = TtsResult.Success(Unit)
            override suspend fun preview(asset: Asset, expectedRevision: Long) = TtsResult.Success(Unit)
            override suspend fun apply(intent: Intent.ApplyVocalGuide, expectedRevision: Long): Boolean {
                applyGate?.let { applyEntered.complete(Unit); it.await() }
                if (document.value.revision != expectedRevision) return false
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
