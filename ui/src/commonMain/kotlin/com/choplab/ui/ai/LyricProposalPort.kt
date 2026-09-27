package com.choplab.ui.ai

import com.choplab.core.ai.LlmProvider
import com.choplab.core.ai.LyricProviderAvailability

/** One new provider per dialog, owned by its controller. Opening the editor sends no request. */
interface LyricProposalPort {
    /** Only a host with verified provider eligibility may enable sending; entering a key is not verification. */
    val availability: LyricProviderAvailability get() = LyricProviderAvailability.UNVERIFIED
    fun createProvider(): LlmProvider
}
