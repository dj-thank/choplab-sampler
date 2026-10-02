package com.choplab.core.ai

import kotlinx.coroutines.*
import kotlin.test.*

/** Synthetic rates and limits, not a price/model recommendation or provider acceptance. */
class GoogleLyricAdmissionTest {
    @Test fun integerCostRoundsEachComponentUpAndOnlyKnownFreeIsZero() {
        val result = assertIs<GoogleCostDecision.Allowed>(GoogleLyricCost.evaluate(review(), "gemini-test", 100))
        assertEquals(436L, result.summary.maximumCost.nanoUnits)
        assertEquals(64, result.maxOutputTokens)
        val free = review(price = price(input = 0, output = 0), budget = GoogleMoney("USD", 0))
        assertEquals(0L, assertIs<GoogleCostDecision.Allowed>(GoogleLyricCost.evaluate(free, "gemini-test", 100)).summary.maximumCost.nanoUnits)
        assertEquals(GoogleAdmissionProblem.UNKNOWN_PRICE, refused(review(price = null)))
    }

    @Test fun missingInconsistentExpiredOverBudgetAndOverflowConditionsAreRefused() {
        val cases = listOf(
            review(eligibility = GoogleUseEligibility.UNVERIFIED) to GoogleAdmissionProblem.UNVERIFIED,
            review(tier = null) to GoogleAdmissionProblem.MODEL_OR_TIER_MISMATCH,
            review(price = null) to GoogleAdmissionProblem.UNKNOWN_PRICE,
            review(price = price(tier = GoogleAccountTier.UNPAID)) to GoogleAdmissionProblem.MODEL_OR_TIER_MISMATCH,
            review(price = price(model = "gemini-other")) to GoogleAdmissionProblem.MODEL_OR_TIER_MISMATCH,
            review(price = price(input = -1)) to GoogleAdmissionProblem.UNKNOWN_PRICE,
            review(price = price(expires = 100)) to GoogleAdmissionProblem.EXPIRED,
            review(bounds = null) to GoogleAdmissionProblem.UNKNOWN_BOUNDS,
            review(bounds = GoogleTokenBounds(10_001, 128, 64)) to GoogleAdmissionProblem.UNKNOWN_BOUNDS,
            review(bounds = GoogleTokenBounds(4096, 63, 64)) to GoogleAdmissionProblem.UNKNOWN_BOUNDS,
            review(budget = null) to GoogleAdmissionProblem.UNKNOWN_BUDGET,
            review(budget = GoogleMoney("JPY", 2_000)) to GoogleAdmissionProblem.CURRENCY_MISMATCH,
            review(budget = GoogleMoney("USD", 435)) to GoogleAdmissionProblem.BUDGET_EXCEEDED,
            review(price = price(input = Long.MAX_VALUE)) to GoogleAdmissionProblem.COST_OVERFLOW,
            review(price = GoogleTokenPrice("gemini-test", GoogleAccountTier.PAID, "USD", 1,
                Long.MAX_VALUE, Long.MAX_VALUE, 10_000, 0, 1_000), bounds = GoogleTokenBounds(1, 1, 1)) to GoogleAdmissionProblem.COST_OVERFLOW,
            review(expires = 100) to GoogleAdmissionProblem.EXPIRED,
        )
        for ((input, expected) in cases) assertEquals(expected, refused(input))
    }

    @Test fun oneConcurrentReserveWinsAndUsedAllowanceCannotBeInstalledOrStartedAgain() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val binding = requireNotNull(f.session.pendingReview())
            assertNull(f.session.install(binding, review()))
            val attempts = coroutineScope { List(32) { async(Dispatchers.Default) { f.dialog.reserve(request(), f.key, f.session.state.value.version) } }.awaitAll() }
            val only = attempts.filterIsInstance<GoogleAttemptDecision.Allowed>().single().attempt
            assertEquals(31, attempts.filterIsInstance<GoogleAttemptDecision.Refused>().size)
            assertEquals(GoogleAdmissionProblem.ATTEMPT_USED, f.session.install(binding, review()))
            assertNull(only.beginHttp("gemini-test", f.key))
            assertEquals(GoogleAdmissionProblem.ATTEMPT_USED, only.beginHttp("gemini-test", f.key))
            assertEquals(LyricProviderAvailability.UNVERIFIED, f.session.state.value.availability)
        } finally { f.close() }
    }

    @Test fun sameTextNewCredentialOrModelAndNewDialogCannotReuseAReviewBinding() {
        val f = Fixture()
        val replacement = SessionApiKey("fake-key")
        try {
            val original = requireNotNull(f.session.pendingReview())
            assertNull(f.session.install(original, review()))
            f.dialog.bindInputs("gemini-test", replacement)
            assertEquals(GoogleAdmissionProblem.INPUT_CHANGED, f.session.install(original, review()))
            assertIs<GoogleAttemptDecision.Refused>(f.dialog.reserve(request(), replacement, f.session.state.value.version))
            val newBinding = requireNotNull(f.session.pendingReview())
            f.dialog.bindInputs("gemini-other", replacement)
            assertEquals(GoogleAdmissionProblem.INPUT_CHANGED, f.session.install(newBinding, review()))
            val next = f.session.openDialog()
            next.bindInputs("gemini-test", replacement)
            f.dialog.close()
            assertNotNull(f.session.pendingReview(), "Closing an old dialog cannot close the new owner")
            assertEquals(GoogleAdmissionProblem.INPUT_CHANGED, f.session.install(original, review()))
        } finally { replacement.close(); f.close() }
    }

    @Test fun reservedButNotStartedAttemptRemainsSpentAndRechecksCurrentConditions() {
        for (change in listOf("key", "model", "dialog", "host", "expires")) {
            val f = Fixture()
            val other = SessionApiKey("other-key")
            try {
                assertNull(f.session.install(requireNotNull(f.session.pendingReview()), review()))
                val attempt = assertIs<GoogleAttemptDecision.Allowed>(f.dialog.reserve(request(), f.key, f.session.state.value.version)).attempt
                when (change) {
                    "key" -> f.dialog.bindInputs("gemini-test", other)
                    "model" -> f.dialog.bindInputs("gemini-other", f.key)
                    "dialog" -> f.dialog.close()
                    "host" -> f.session.close()
                    "expires" -> f.now = 1_000
                }
                assertNotNull(attempt.beginHttp("gemini-test", f.key), change)
                assertEquals(GoogleAdmissionProblem.ATTEMPT_USED, attempt.beginHttp("gemini-test", f.key), change)
            } finally { other.close(); f.close() }
        }
    }

    @Test fun expiredOrInitiallyRejectedConditionsNeverBecomeAnImplicitGrant() {
        val f = Fixture()
        try {
            val binding = requireNotNull(f.session.pendingReview())
            assertEquals(GoogleAdmissionProblem.EXPIRED, f.session.install(binding, review(expires = 100)))
            f.now = 99
            assertIs<GoogleAttemptDecision.Refused>(f.dialog.reserve(request(), f.key, f.session.state.value.version))
            assertNull(f.session.install(binding, review()))
            f.now = 1_000
            assertEquals(GoogleAdmissionProblem.EXPIRED, assertIs<GoogleAttemptDecision.Refused>(f.dialog.reserve(request(), f.key, f.session.state.value.version)).problem)
            f.now = 100
            assertIs<GoogleAttemptDecision.Refused>(f.dialog.reserve(request(), f.key, f.session.state.value.version))
        } finally { f.close() }
    }

    @Test fun newReviewedConditionsInvalidateConsentCapturedBeforeTheyChanged() {
        val f = Fixture()
        try {
            val binding = requireNotNull(f.session.pendingReview())
            assertNull(f.session.install(binding, review()))
            val earlierConsent = f.session.state.value.version
            assertNull(f.session.install(binding, review(budget = GoogleMoney("USD", 3_000))))
            assertEquals(GoogleAdmissionProblem.INPUT_CHANGED,
                assertIs<GoogleAttemptDecision.Refused>(f.dialog.reserve(request(), f.key, earlierConsent)).problem)
            assertIs<GoogleAttemptDecision.Allowed>(f.dialog.reserve(request(), f.key, f.session.state.value.version))
        } finally { f.close() }
    }

    private class Fixture {
        var now = 100L
        val session = GoogleLyricSession { now }
        val dialog = session.openDialog()
        val key = SessionApiKey("fake-key")
        init { dialog.bindInputs("gemini-test", key) }
        fun close() { key.close(); session.close() }
    }
    private fun refused(value: ReviewedGoogleUse) = assertIs<GoogleCostDecision.Refused>(GoogleLyricCost.evaluate(value, "gemini-test", 100)).problem
    private fun request() = LyricRequest("gemini-test", "theme", "", LyricLanguage.JAPANESE, LyricStyle.SONG, "", "", "")
    private fun price(model: String = "gemini-test", tier: GoogleAccountTier = GoogleAccountTier.PAID,
        input: Long = 100, output: Long = 200, expires: Long = 1_000) =
        GoogleTokenPrice(model, tier, "USD", 1_000, input, output, 10_000, 0, expires)
    private fun review(price: GoogleTokenPrice? = price(), bounds: GoogleTokenBounds? = GoogleTokenBounds(4096, 128, 64),
        budget: GoogleMoney? = GoogleMoney("USD", 2_000), tier: GoogleAccountTier? = GoogleAccountTier.PAID,
        eligibility: GoogleUseEligibility = GoogleUseEligibility.REVIEWED_FOR_THIS_SESSION, expires: Long = 1_000) =
        ReviewedGoogleUse("gemini-test", tier, eligibility, price, bounds, budget, 0, expires)
}
