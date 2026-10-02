package com.choplab.ui.ai

import com.choplab.core.ai.LlmProvider
import com.choplab.core.ai.LyricProviderAvailability
import com.choplab.core.ai.GoogleLyricDialog
import com.choplab.core.ai.GoogleLyricSession

/** One new provider per dialog, owned by its controller. Opening the editor sends no request. */
interface LyricProposalPort {
    /** Only a host with verified provider eligibility may enable sending; entering a key is not verification. */
    val availability: LyricProviderAvailability get() = LyricProviderAvailability.UNVERIFIED
    /** Availability alone never bypasses the current session's cost/credential admission. */
    fun openAdmission(): GoogleLyricDialog = GoogleLyricSession.unverified().openDialog()
    fun createProvider(): LlmProvider
}

/** Both normal hosts use this bridge. Opening never installs a review or sends a request. */
class SessionLyricProposalPort(private val session: GoogleLyricSession, private val provider: () -> LlmProvider) : LyricProposalPort {
    override val availability get() = session.state.value.availability
    override fun openAdmission() = session.openDialog()
    override fun createProvider() = provider()
}
