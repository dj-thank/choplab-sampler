package com.choplab.core.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GoogleAccountTier { PAID, UNPAID }
enum class GoogleUseEligibility { UNVERIFIED, REVIEWED_FOR_THIS_SESSION }
enum class GoogleAdmissionProblem {
    UNVERIFIED, UNKNOWN_PRICE, UNKNOWN_BOUNDS, UNKNOWN_BUDGET, CURRENCY_MISMATCH,
    MODEL_OR_TIER_MISMATCH, EXPIRED, COST_OVERFLOW, BUDGET_EXCEEDED, INPUT_CHANGED, ATTEMPT_USED, CLOSED,
}

/** Fixed precision in the stated currency; no exchange-rate conversion or floating-point estimate. */
class GoogleMoney(val currency: String, val nanoUnits: Long) {
    override fun toString() = "GoogleMoney([session value])"
}

/** Supplied by a reviewed owner session, never inferred from a key or a UI checkbox. */
class GoogleTokenPrice(
    val model: String,
    val tier: GoogleAccountTier,
    val currency: String,
    val tokensPerUnit: Long,
    val inputNanoUnitsPerUnit: Long,
    val outputIncludingThinkingNanoUnitsPerUnit: Long,
    val maximumInputTokensAtThisPrice: Long,
    val reviewedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    override fun toString() = "GoogleTokenPrice([reviewed session value])"
}

/**
 * Authoritative upper bounds for this model/configuration, including system/schema input and
 * every billable output/thinking token. maxOutputTokens alone is not proof of that ceiling.
 * Leave this absent unless the selected model's applicable limits have actually been reviewed.
 */
class GoogleTokenBounds(
    val maximumInputTokens: Long,
    val maximumBillableOutputTokens: Long,
    val maxOutputTokens: Int,
) {
    override fun toString() = "GoogleTokenBounds([reviewed session value])"
}

/** No key, owner/account ID, prompt, stable fingerprint or serializable persistence format. */
class ReviewedGoogleUse(
    val model: String,
    val tier: GoogleAccountTier?,
    val eligibility: GoogleUseEligibility,
    val price: GoogleTokenPrice?,
    val bounds: GoogleTokenBounds?,
    val budget: GoogleMoney?,
    val reviewedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    override fun toString() = "ReviewedGoogleUse([private session conditions])"
}

/** Only the current decision's upper bound is displayed; it is never reported as an actual charge. */
class GoogleAdmissionSummary(val model: String, val tier: GoogleAccountTier, val maximumCost: GoogleMoney) {
    override fun toString() = "GoogleAdmissionSummary([session conditions])"
}

sealed interface GoogleCostDecision {
    class Allowed(val summary: GoogleAdmissionSummary, val maxOutputTokens: Int) : GoogleCostDecision
    class Refused(val problem: GoogleAdmissionProblem) : GoogleCostDecision
}

object GoogleLyricCost {
    fun evaluate(review: ReviewedGoogleUse, model: String, nowEpochMillis: Long): GoogleCostDecision {
        fun no(problem: GoogleAdmissionProblem) = GoogleCostDecision.Refused(problem)
        if (review.eligibility != GoogleUseEligibility.REVIEWED_FOR_THIS_SESSION) return no(GoogleAdmissionProblem.UNVERIFIED)
        if (review.tier == null || review.model != model || !model.matches(Regex("gemini-[a-z0-9][a-z0-9._-]{0,79}")))
            return no(GoogleAdmissionProblem.MODEL_OR_TIER_MISMATCH)
        if (nowEpochMillis < 0 || review.reviewedAtEpochMillis !in 0..nowEpochMillis ||
            review.expiresAtEpochMillis <= nowEpochMillis) return no(GoogleAdmissionProblem.EXPIRED)
        val price = review.price ?: return no(GoogleAdmissionProblem.UNKNOWN_PRICE)
        if (price.model != model || price.tier != review.tier) return no(GoogleAdmissionProblem.MODEL_OR_TIER_MISMATCH)
        if (price.reviewedAtEpochMillis !in 0..nowEpochMillis || price.expiresAtEpochMillis <= nowEpochMillis)
            return no(GoogleAdmissionProblem.EXPIRED)
        if (!price.currency.matches(Regex("[A-Z]{3}")) || price.tokensPerUnit <= 0 ||
            price.inputNanoUnitsPerUnit < 0 || price.outputIncludingThinkingNanoUnitsPerUnit < 0 ||
            price.maximumInputTokensAtThisPrice <= 0) return no(GoogleAdmissionProblem.UNKNOWN_PRICE)
        val bounds = review.bounds ?: return no(GoogleAdmissionProblem.UNKNOWN_BOUNDS)
        if (bounds.maximumInputTokens <= 0 || bounds.maximumBillableOutputTokens <= 0 ||
            bounds.maxOutputTokens <= 0 || bounds.maximumBillableOutputTokens < bounds.maxOutputTokens ||
            bounds.maximumInputTokens > price.maximumInputTokensAtThisPrice) return no(GoogleAdmissionProblem.UNKNOWN_BOUNDS)
        val budget = review.budget ?: return no(GoogleAdmissionProblem.UNKNOWN_BUDGET)
        if (!budget.currency.matches(Regex("[A-Z]{3}")) || budget.nanoUnits < 0) return no(GoogleAdmissionProblem.UNKNOWN_BUDGET)
        if (budget.currency != price.currency) return no(GoogleAdmissionProblem.CURRENCY_MISMATCH)
        val input = roundedCharge(bounds.maximumInputTokens, price.inputNanoUnitsPerUnit, price.tokensPerUnit)
            ?: return no(GoogleAdmissionProblem.COST_OVERFLOW)
        val output = roundedCharge(bounds.maximumBillableOutputTokens, price.outputIncludingThinkingNanoUnitsPerUnit, price.tokensPerUnit)
            ?: return no(GoogleAdmissionProblem.COST_OVERFLOW)
        if (input > Long.MAX_VALUE - output) return no(GoogleAdmissionProblem.COST_OVERFLOW)
        val total = input + output
        if (total > budget.nanoUnits) return no(GoogleAdmissionProblem.BUDGET_EXCEEDED)
        return GoogleCostDecision.Allowed(GoogleAdmissionSummary(model, review.tier, GoogleMoney(price.currency, total)), bounds.maxOutputTokens)
    }

    private fun roundedCharge(tokens: Long, rate: Long, unit: Long): Long? {
        if (rate != 0L && tokens > Long.MAX_VALUE / rate) return null
        val product = tokens * rate
        val whole = product / unit
        return if (product % unit == 0L) whole else if (whole == Long.MAX_VALUE) null else whole + 1
    }
}

/** Opaque, short-lived binding to one dialog's current credential/model input. */
class GoogleReviewBinding internal constructor(internal val dialog: Any, internal val input: Any) {
    override fun toString() = "GoogleReviewBinding([session only])"
}

class GoogleAdmissionState internal constructor(
    val version: Long,
    val problem: GoogleAdmissionProblem?,
    val summary: GoogleAdmissionSummary? = null,
    internal val dialog: Any? = null,
    internal val input: Any? = null,
    internal val credential: Any? = null,
    internal val model: String = "",
    internal val review: ReviewedGoogleUse? = null,
    internal val attempt: GoogleLyricAttempt? = null,
    internal val hostClosed: Boolean = false,
) {
    val availability get() = if (summary != null && attempt == null && !hostClosed)
        LyricProviderAvailability.AVAILABLE else LyricProviderAvailability.UNVERIFIED
    override fun toString() = "GoogleAdmissionState(availability=$availability)"
}

sealed interface GoogleAttemptDecision {
    class Allowed(val attempt: GoogleLyricAttempt) : GoogleAttemptDecision
    class Refused(val problem: GoogleAdmissionProblem) : GoogleAttemptDecision
}

/**
 * Owned by the normal host. Opening/input never grants eligibility. Only explicit install of
 * reviewed conditions for pendingReview() may admit one attempt. No settings, files or network.
 */
class GoogleLyricSession(private val nowEpochMillis: () -> Long) {
    private val mutable = MutableStateFlow(GoogleAdmissionState(0, GoogleAdmissionProblem.UNVERIFIED))
    val state: StateFlow<GoogleAdmissionState> = mutable.asStateFlow()

    fun openDialog(): GoogleLyricDialog {
        val identity = Any()
        while (true) {
            val old = mutable.value
            if (old.hostClosed) return GoogleLyricDialog(this, identity)
            if (mutable.compareAndSet(old, GoogleAdmissionState(old.version + 1, GoogleAdmissionProblem.UNVERIFIED, dialog = identity)))
                return GoogleLyricDialog(this, identity)
        }
    }

    fun pendingReview(): GoogleReviewBinding? = mutable.value.let {
        if (it.hostClosed || it.dialog == null || it.input == null || it.credential == null || it.model.isBlank()) null
        else GoogleReviewBinding(it.dialog, it.input)
    }

    fun install(binding: GoogleReviewBinding, review: ReviewedGoogleUse): GoogleAdmissionProblem? {
        while (true) {
            val old = mutable.value
            if (old.hostClosed) return GoogleAdmissionProblem.CLOSED
            if (old.dialog !== binding.dialog || old.input !== binding.input) return GoogleAdmissionProblem.INPUT_CHANGED
            if (old.attempt != null) return GoogleAdmissionProblem.ATTEMPT_USED
            val decision = GoogleLyricCost.evaluate(review, old.model, nowEpochMillis())
            val summary = (decision as? GoogleCostDecision.Allowed)?.summary
            val problem = (decision as? GoogleCostDecision.Refused)?.problem
            val next = GoogleAdmissionState(old.version + 1, problem, summary, old.dialog, old.input,
                old.credential, old.model, review)
            if (mutable.compareAndSet(old, next)) return (decision as? GoogleCostDecision.Refused)?.problem
        }
    }

    internal fun bind(dialog: Any, model: String, key: SessionApiKey?) {
        while (true) {
            val old = mutable.value
            if (old.hostClosed || old.dialog !== dialog) return
            val credential = key?.identity
            if (old.model == model && old.credential === credential) return
            val reason = if (old.review == null && old.attempt == null) GoogleAdmissionProblem.UNVERIFIED else GoogleAdmissionProblem.INPUT_CHANGED
            val next = GoogleAdmissionState(old.version + 1, reason, dialog = dialog,
                input = Any(), credential = credential, model = model)
            if (mutable.compareAndSet(old, next)) return
        }
    }

    internal fun reserve(dialog: Any, request: LyricRequest, key: SessionApiKey, consentVersion: Long): GoogleAttemptDecision {
        fun no(problem: GoogleAdmissionProblem) = GoogleAttemptDecision.Refused(problem)
        while (true) {
            val old = mutable.value
            if (old.hostClosed || old.dialog !== dialog) return no(GoogleAdmissionProblem.CLOSED)
            if (old.credential !== key.identity || old.model != request.model) return no(GoogleAdmissionProblem.INPUT_CHANGED)
            if (old.attempt != null) return no(GoogleAdmissionProblem.ATTEMPT_USED)
            if (old.version != consentVersion) return no(GoogleAdmissionProblem.INPUT_CHANGED)
            if (old.summary == null) return no(old.problem ?: GoogleAdmissionProblem.UNVERIFIED)
            val review = old.review ?: return no(GoogleAdmissionProblem.UNVERIFIED)
            val cost = GoogleLyricCost.evaluate(review, request.model, nowEpochMillis())
            if (cost is GoogleCostDecision.Refused) {
                if (!mutable.compareAndSet(old, GoogleAdmissionState(old.version + 1, cost.problem,
                    dialog = old.dialog, input = old.input, credential = old.credential, model = old.model))) continue
                return no(cost.problem)
            }
            cost as GoogleCostDecision.Allowed
            val attempt = GoogleLyricAttempt(this, dialog, requireNotNull(old.input), key.identity, review, cost.maxOutputTokens)
            val next = GoogleAdmissionState(old.version + 1, GoogleAdmissionProblem.ATTEMPT_USED, cost.summary,
                dialog, old.input, old.credential, old.model, review, attempt)
            if (mutable.compareAndSet(old, next)) return GoogleAttemptDecision.Allowed(attempt)
        }
    }

    internal fun validate(attempt: GoogleLyricAttempt, model: String, key: SessionApiKey): GoogleAdmissionProblem? {
        val now = mutable.value
        if (now.hostClosed || now.dialog !== attempt.dialog) return GoogleAdmissionProblem.CLOSED
        if (now.input !== attempt.input || now.credential !== key.identity || now.model != model || now.attempt !== attempt)
            return GoogleAdmissionProblem.INPUT_CHANGED
        val result = (GoogleLyricCost.evaluate(attempt.review, model, nowEpochMillis()) as? GoogleCostDecision.Refused)?.problem
        return if (mutable.value !== now) GoogleAdmissionProblem.INPUT_CHANGED else result
    }

    internal fun owns(attempt: GoogleLyricAttempt): Boolean = mutable.value.let {
        !it.hostClosed && it.dialog === attempt.dialog && it.input === attempt.input && it.attempt === attempt
    }

    internal fun closeDialog(dialog: Any) {
        while (true) {
            val old = mutable.value
            if (old.hostClosed || old.dialog !== dialog) return
            if (mutable.compareAndSet(old, GoogleAdmissionState(old.version + 1, GoogleAdmissionProblem.CLOSED))) return
        }
    }

    fun close() {
        while (true) {
            val old = mutable.value
            if (old.hostClosed) return
            if (mutable.compareAndSet(old, GoogleAdmissionState(old.version + 1, GoogleAdmissionProblem.CLOSED, hostClosed = true))) return
        }
    }

    companion object {
        /** No reviewed record can be installed without the host's real clock and explicit ownership. */
        fun unverified() = GoogleLyricSession { -1L }
    }
}

class GoogleLyricDialog internal constructor(private val session: GoogleLyricSession, private val identity: Any) {
    val state get() = session.state
    fun bindInputs(model: String, key: SessionApiKey?) = session.bind(identity, model, key)
    fun reserve(request: LyricRequest, key: SessionApiKey, consentVersion: Long) = session.reserve(identity, request, key, consentVersion)
    fun close() = session.closeDialog(identity)
    override fun toString() = "GoogleLyricDialog([session only])"
}

/** Reserved before launching the job, spent forever even if execution is cancelled before HTTP. */
class GoogleLyricAttempt internal constructor(
    private val session: GoogleLyricSession,
    internal val dialog: Any,
    internal val input: Any,
    private val credential: Any,
    internal val review: ReviewedGoogleUse,
    val maxOutputTokens: Int,
) {
    private val started = MutableStateFlow(false)
    fun isCurrent(): Boolean = session.owns(this)
    fun beginHttp(model: String, key: SessionApiKey): GoogleAdmissionProblem? {
        if (!started.compareAndSet(false, true)) return GoogleAdmissionProblem.ATTEMPT_USED
        if (credential !== key.identity) return GoogleAdmissionProblem.INPUT_CHANGED
        return session.validate(this, model, key)
    }
    override fun toString() = "GoogleLyricAttempt([one session attempt])"
}
