package com.choplab.core.ai

import com.choplab.core.model.*
import kotlin.test.*

class LyricProposalTest {
    @Test fun readingsHaveLocalMoraAndRhymeRatherThanGuessedPronunciation() {
        assertEquals(KanaAnalysis(3, "aqu"), KanaMetrics.analyze("きゃっぷ"))
        assertEquals(KanaAnalysis(4, "ooii"), KanaMetrics.analyze("コーヒー"))
        assertEquals(KanaAnalysis(3, "ann"), KanaMetrics.analyze("あんん。"))
        assertNull(KanaMetrics.analyze("歌う"))
        assertNull(KanaMetrics.analyze("hello"))
        assertNull(KanaMetrics.analyze("ゃ"))
        assertNull(KanaMetrics.analyze("ー"))
        assertNull(KanaMetrics.analyze("　。"))
        assertFailsWith<IllegalArgumentException> { ProposalLine.create("歌う", "歌う", LyricLanguage.JAPANESE) }
        val english = ProposalLine.create("Sing", "sing", LyricLanguage.ENGLISH)
        assertNull(english.mora)
        assertNull(english.rhymeVowels)
    }

    @Test fun manualPlacementUsesEndExclusiveTicksAndBoundsWithoutInventedWordAlignment() {
        val line = ProposalLine.create("音", "おと", LyricLanguage.JAPANESE)
        val proposal = LyricProposal("題", LyricLanguage.JAPANESE,
            frozenListOf(ProposalSection("A", LyricSectionKind.VERSE, 8, frozenListOf(line, line))))
        val placed = proposal.place(960, 2, "ai-0-1")
        assertEquals(listOf(960L, 2880L), placed.map { it.startTick })
        assertEquals(listOf(2880L, 4800L), placed.map { it.endTick })
        assertTrue(placed.all { it.words.isEmpty() })
        assertEquals(2, placed.map { it.id }.toSet().size)
        assertFailsWith<IllegalArgumentException> { proposal.place(ProjectLimits.MAX_TIMELINE_TICKS - 1, 1, "id") }
        assertFailsWith<IllegalArgumentException> { proposal.place(0, 0, "id") }
        assertFailsWith<IllegalArgumentException> { proposal.place(0, 17, "id") }
        assertFailsWith<IllegalArgumentException> { proposal.place(0, 1, "unsafe/id") }
    }

    @Test fun providerInputsAndOutputsAreBoundedAndDoNotRevealPrivateTextInDiagnostics() {
        val request = LyricRequest("gemini-test", "private-theme", "", LyricLanguage.JAPANESE, LyricStyle.RAP, "", "", "")
        assertFalse(request.toString().contains("private-theme"))
        assertFailsWith<IllegalArgumentException> { LyricRequest("../other", "x", "", LyricLanguage.JAPANESE, LyricStyle.RAP, "", "", "") }
        assertFailsWith<IllegalArgumentException> { LyricRequest("gemini-test", "x".repeat(1_025), "", LyricLanguage.JAPANESE, LyricStyle.RAP, "", "", "") }
        assertFailsWith<IllegalArgumentException> { ProposalLine.create("x\nnext", "え", LyricLanguage.JAPANESE) }
        assertFailsWith<IllegalArgumentException> { ProposalLine.create("x".repeat(513), "え", LyricLanguage.JAPANESE) }
        val line = ProposalLine.create("private-text", "おと", LyricLanguage.JAPANESE)
        assertFalse(line.toString().contains("private-text"))
        val section = ProposalSection("A", LyricSectionKind.VERSE, 4, List(16) { line }.frozen())
        assertFailsWith<IllegalArgumentException> { LyricProposal("title", LyricLanguage.JAPANESE, List(5) { section }.frozen()) }
        assertFailsWith<IllegalArgumentException> { LyricUsage(-1, null, null) }
    }

    @Test fun closingSessionKeyClearsItsUsableCopyAndNeverPrintsIt() {
        val key = SessionApiKey("test-key_private")
        assertEquals("test-key_private", key.useValue { it })
        assertFalse(key.toString().contains("test-key_private"))
        key.close(); key.close()
        assertFailsWith<IllegalStateException> { key.useValue { it } }
        assertFailsWith<IllegalArgumentException> { SessionApiKey("key\nheader") }
        assertFailsWith<IllegalArgumentException> { SessionApiKey("鍵") }
    }
}
