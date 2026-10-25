package com.choplab.jvm.inception

import com.choplab.core.ai.*
import com.choplab.core.inception.*
import com.choplab.core.model.*
import com.choplab.jvm.ai.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class GeminiInceptionProviderTest {
    @Test fun oneReviewedRequestContainsOnlySelectedMaterialAndParsesZeroConcepts() = runBlocking<Unit> {
        val f = Fixture()
        var captured = ""
        val transport = object : GeminiHttpTransport {
            override suspend fun post(request: GeminiHttpRequest): GeminiHttpResponse { f.calls++; captured = request.body; return f.response() }
        }
        val provider = GeminiInceptionProvider(transport)
        assertIs<InceptionProviderResult.Success>(provider.propose(f.request, "gemini-test", f.key, f.attempt))
        assertTrue(captured.contains("current-only")); assertFalse(captured.contains("original-private"))
        assertFalse(captured.contains("audioRef")); assertEquals(1, f.calls)
        assertIs<InceptionProviderResult.Failure>(provider.propose(f.request, "gemini-test", f.key, f.attempt))
        assertEquals(1, f.calls); provider.close(); f.close()
    }
    @Test fun expiredAdmissionSendsNoPost() = runBlocking<Unit> {
        val f = Fixture(); f.now = 1001
        val provider = GeminiInceptionProvider(f.transport())
        assertIs<InceptionProviderResult.Failure>(provider.propose(f.request, "gemini-test", f.key, f.attempt))
        assertEquals(0, f.calls); provider.close(); f.close()
    }
    @Test fun rateLimitedAndTimeoutAreSingleAttemptAndCostIsUnknown() = runBlocking<Unit> {
        for (timeout in listOf(false, true)) {
            val f = Fixture()
            val provider = GeminiInceptionProvider(object : GeminiHttpTransport {
                override suspend fun post(request: GeminiHttpRequest): GeminiHttpResponse { f.calls++; if (timeout) delay(1000); return GeminiHttpResponse(429) }
            }, timeoutMillis = 20)
            val failure = assertIs<InceptionProviderResult.Failure>(provider.propose(f.request, "gemini-test", f.key, f.attempt))
            assertEquals(if (timeout) InceptionProblem.TIMEOUT else InceptionProblem.RATE_LIMITED, failure.problem)
            assertTrue(failure.costUnknown); assertEquals(1, f.calls); provider.close(); f.close()
        }
    }
    @Test fun duplicateCandidateWrongRequestAndUnknownFieldAreRejected() = runBlocking<Unit> {
        for (payload in listOf(
            """{"requestId":"old","revision":1,"concepts":[],"relations":[],"questions":[]}""",
            """{"requestId":"r","revision":1,"concepts":[],"relations":[],"questions":[],"adopted":true}""",
            """{"requestId":"r","revision":1,"revision":1,"concepts":[],"relations":[],"questions":[]}""",
        )) {
            val f = Fixture()
            val provider = GeminiInceptionProvider(f.transport(payload))
            assertIs<InceptionProviderResult.Failure>(provider.propose(f.request, "gemini-test", f.key, f.attempt))
            assertEquals(1, f.calls); provider.close(); f.close()
        }
        val f = Fixture()
        val provider = GeminiInceptionProvider(object : GeminiHttpTransport {
            override suspend fun post(request: GeminiHttpRequest): GeminiHttpResponse = GeminiHttpResponse(200, body = """{"candidates":[{},{}]}""")
        })
        assertIs<InceptionProviderResult.Failure>(provider.propose(f.request, "gemini-test", f.key, f.attempt)); provider.close(); f.close()
    }
    private class Fixture {
        var now = 100L
        var calls = 0
        val session = GoogleLyricSession { now }
        val dialog = session.openDialog()
        val key = SessionApiKey("fixture-key")
        val request = InceptionRequest("r", 1, frozenListOf(InceptionMaterial("m", "original-private", "current-only")))
        val attempt: GoogleLyricAttempt
        init {
            dialog.bindInputs("gemini-test", key)
            session.install(session.pendingReview()!!, ReviewedGoogleUse("gemini-test", GoogleAccountTier.PAID,
                GoogleUseEligibility.REVIEWED_FOR_THIS_SESSION,
                GoogleTokenPrice("gemini-test", GoogleAccountTier.PAID, "USD", 1000, 1, 1, 10000, 0, 1000),
                GoogleTokenBounds(5000, 1000, 1000), GoogleMoney("USD", 1000), 0, 1000))
            attempt = (dialog.reserve(LyricRequest("gemini-test", "fixture", "", LyricLanguage.JAPANESE, LyricStyle.SONG, "", "", ""), key, session.state.value.version) as GoogleAttemptDecision.Allowed).attempt
        }
        fun response(payload: String = """{"requestId":"r","revision":1,"concepts":[],"relations":[],"questions":[]}""") = GeminiHttpResponse(200, body = buildJsonObject {
            put("candidates", buildJsonArray { add(buildJsonObject { put("finishReason", "STOP"); put("content", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", payload) }) })
            }) }) })
        }.toString())
        fun transport(payload: String = """{"requestId":"r","revision":1,"concepts":[],"relations":[],"questions":[]}""") = object : GeminiHttpTransport {
            override suspend fun post(request: GeminiHttpRequest): GeminiHttpResponse { calls++; return response(payload) }
        }
        fun close() { key.close(); session.close() }
    }
}
