package com.choplab.ui.ai

import com.choplab.core.DocumentState
import com.choplab.core.ai.*
import com.choplab.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LyricProposalControllerTest {
    @Test fun closeDuringValidationCannotPublishRetainAKeyOrStartALateProviderJob() = runBlocking<Unit> {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val fixture = Fixture(clock = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); 0L })
        val key = SessionApiKey("fake-key")
        try {
            val generating = async(Dispatchers.Default) { fixture.controller.generate(request(), key, 0, 4, true) }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            fixture.controller.close()
            release.countDown()
            assertFalse(generating.await())
            assertFailsWith<IllegalStateException> { key.useValue { it } }
            assertEquals(0, fixture.provider.calls.get())
            assertEquals(LyricProposalPhase.CLOSED, fixture.controller.state.value.phase)
            assertEquals(0, fixture.edits)
        } finally { release.countDown(); fixture.close() }
    }

    @Test fun previewNeverEditsAndOnlyOneExplicitApplyCommitsTheCapturedRevision() = runBlocking<Unit> {
        val fixture = Fixture()
        try {
            val before = fixture.document.value
            val key = SessionApiKey("fake-key")
            assertTrue(fixture.controller.generate(request(), key, 960, 2, true))
            await { fixture.controller.state.value.phase == LyricProposalPhase.PREVIEW }
            assertEquals(before, fixture.document.value); assertEquals(0, fixture.edits)
            assertEquals(before.project.lyrics, fixture.controller.state.value.before)
            assertFailsWith<IllegalStateException> { key.useValue { it } }
            assertTrue(fixture.controller.applyPreview())
            assertEquals(1, fixture.edits); assertEquals(8, fixture.document.value.revision)
            assertEquals("新しい歌", fixture.document.value.project.lyrics.single().text)
            assertEquals(960, fixture.document.value.project.lyrics.single().startTick)
            assertEquals(2880, fixture.document.value.project.lyrics.single().endTick)
            assertFalse(fixture.controller.applyPreview()); assertEquals(1, fixture.edits)
        } finally { fixture.close() }
    }

    @Test fun unverifiedProviderAndMissingConsentNeverCallTheProvider() = runBlocking<Unit> {
        for (availability in LyricProviderAvailability.entries) {
            val fixture = Fixture(availability = availability)
            try {
                val key = SessionApiKey("fake-key")
                assertFalse(fixture.controller.generate(request(), key, 0, 4, availability == LyricProviderAvailability.UNVERIFIED))
                assertEquals(if (availability == LyricProviderAvailability.UNVERIFIED) LyricAiProblem.PROVIDER_UNVERIFIED else LyricAiProblem.CONSENT_REQUIRED,
                    fixture.controller.state.value.failure?.problem)
                assertEquals(0, fixture.provider.calls.get())
                assertFailsWith<IllegalStateException> { key.useValue { it } }
            } finally { fixture.close() }
        }
    }

    @Test fun revisionChangesDuringRequestOrPreviewInvalidateTheProposal() = runBlocking<Unit> {
        for (duringRequest in listOf(true, false)) {
            val reply = CompletableDeferred<LyricProviderResult>()
            val provider = FakeProvider { withContext(NonCancellable) { reply.await() } }
            val fixture = Fixture(provider)
            try {
                assertTrue(fixture.generate())
                await { provider.calls.get() == 1 }
                if (!duringRequest) { reply.complete(success()); await { fixture.controller.state.value.phase == LyricProposalPhase.PREVIEW } }
                fixture.document.value = fixture.document.value.copy(revision = 8)
                await { fixture.controller.state.value.failure?.problem == LyricAiProblem.STALE_DOCUMENT }
                reply.complete(success())
                delay(30)
                assertNull(fixture.controller.state.value.proposal)
                assertFalse(fixture.controller.applyPreview()); assertEquals(0, fixture.edits)
                assertEquals("元の歌", fixture.document.value.project.lyrics.single().text)
            } finally { reply.complete(success()); fixture.close() }
        }
    }

    @Test fun actorHookRejectsAnEditAfterThePreviewCheckAndCloseCancelsAPendingApply() = runBlocking<Unit> {
        for (close in listOf(false, true)) {
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = Fixture(beforeApply = { reached.complete(Unit); release.await() })
            try {
                fixture.generate(); await { fixture.controller.state.value.phase == LyricProposalPhase.PREVIEW }
                val operation = async { fixture.controller.applyPreview() }
                reached.await()
                if (close) {
                    fixture.controller.close()
                    assertFailsWith<CancellationException> { operation.await() }
                    assertEquals(LyricProposalPhase.CLOSED, fixture.controller.state.value.phase)
                } else {
                    fixture.document.value = fixture.document.value.copy(revision = 8)
                    release.complete(Unit)
                    assertFalse(operation.await())
                    assertEquals(LyricAiProblem.APPLY_REJECTED, fixture.controller.state.value.failure?.problem)
                }
                assertEquals(0, fixture.edits)
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test fun cancelAndCloseFenceLateRepliesAndDiscardSecretsWithoutEditing() = runBlocking<Unit> {
        for (close in listOf(false, true)) {
            val reply = CompletableDeferred<LyricProviderResult>()
            val provider = FakeProvider { withContext(NonCancellable) { reply.await() } }
            val fixture = Fixture(provider)
            val key = SessionApiKey("fake-key")
            try {
                fixture.controller.generate(request(), key, 0, 4, true)
                await { provider.calls.get() == 1 }
                if (close) fixture.controller.close() else fixture.controller.cancel()
                assertFailsWith<IllegalStateException> { key.useValue { it } }
                reply.complete(success()); delay(30)
                assertNull(fixture.controller.state.value.proposal)
                assertFalse(fixture.controller.applyPreview()); assertEquals(0, fixture.edits)
                assertEquals(if (close) LyricProposalPhase.CLOSED else LyricProposalPhase.FAILED, fixture.controller.state.value.phase)
                if (close) assertTrue(provider.closed) else assertTrue(fixture.controller.state.value.failure!!.costUnknown)
            } finally { reply.complete(success()); fixture.close() }
        }
    }

    @Test fun rateLimitCountsDownButOnlyAnotherConsentedPressCanRetry() = runBlocking<Unit> {
        var clock = 0L
        val provider = FakeProvider { attempt -> if (attempt == 1) LyricProviderResult.Failure(
            LyricAiFailure(LyricAiProblem.RATE_LIMITED, 2, costUnknown = true)) else success() }
        val fixture = Fixture(provider, clock = { clock })
        try {
            fixture.generate(); await { fixture.controller.state.value.retryRemainingSeconds == 2L }
            assertFalse(fixture.generate()); assertEquals(1, provider.calls.get())
            fixture.controller.cancel()
            assertFalse(fixture.generate(), "Cancel cannot bypass the server cooldown")
            clock = 2_000
            await { fixture.controller.state.value.retryRemainingSeconds == 0L }
            assertEquals(1, provider.calls.get(), "There is no scheduled retry")
            assertFalse(fixture.controller.generate(request(), SessionApiKey("fake-key"), 0, 4, false))
            assertEquals(1, provider.calls.get())
            assertTrue(fixture.generate()); await { fixture.controller.state.value.phase == LyricProposalPhase.PREVIEW }
            assertEquals(2, provider.calls.get()); assertEquals(0, fixture.edits)
        } finally { fixture.close() }
    }

    private class FakeProvider(val respond: suspend (Int) -> LyricProviderResult = { success() }) : LlmProvider {
        val calls = AtomicInteger()
        @Volatile var closed = false
        override suspend fun lyrics(request: LyricRequest, key: SessionApiKey) = respond(calls.incrementAndGet())
        override fun close() { closed = true }
    }
    private class Fixture(val provider: FakeProvider = FakeProvider(), availability: LyricProviderAvailability = LyricProviderAvailability.AVAILABLE,
        clock: () -> Long = { System.nanoTime() / 1_000_000 }, beforeApply: suspend () -> Unit = {}) {
        val document = MutableStateFlow(DocumentState(Project(lyrics = frozenListOf(LyricLine("old", "元の歌", 0, 960))), 7))
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var edits = 0
        val controller = LyricProposalController(document, provider, LyricProposalApply { lines, expected ->
            beforeApply()
            if (document.value.revision != expected) false else {
                edits++; document.value = document.value.copy(project = document.value.project.copy(lyrics = lines), revision = expected + 1); true
            }
        }, scope, availability, clock)
        suspend fun generate() = controller.generate(request(), SessionApiKey("fake-key"), 0, 4, true)
        fun close() { controller.close(); scope.cancel() }
    }
    private companion object {
        fun request() = LyricRequest("gemini-test", "テーマ", "", LyricLanguage.JAPANESE, LyricStyle.SONG, "", "", "")
        fun success() = LyricProviderResult.Success(LyricProposal("題", LyricLanguage.JAPANESE,
            frozenListOf(ProposalSection("A", LyricSectionKind.VERSE, 4, frozenListOf(ProposalLine.create("新しい歌", "あたらしいうた", LyricLanguage.JAPANESE))))),
            LyricUsage(10, 20, 30), "gemini-test")
        suspend fun await(condition: () -> Boolean) = withTimeout(3_000) { while (!condition()) delay(5) }
    }
}
