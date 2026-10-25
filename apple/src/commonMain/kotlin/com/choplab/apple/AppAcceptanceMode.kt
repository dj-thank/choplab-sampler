package com.choplab.apple

/** Only explicit host-controlled acceptance runs may open an isolated fixture profile. */
internal data class AppAcceptanceMode(val profile: String, val phase: String, val token: String) {
    companion object {
        fun parse(profile: String?, phase: String?, token: String?): AppAcceptanceMode? {
            if (profile == null && phase == null) return null
            require(profile != null && Regex("[0-9a-f]{32}").matches(profile))
            require(phase == "first" || phase == "relaunch")
            require(token != null && Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(token))
            return AppAcceptanceMode(profile, phase, token)
        }
    }
}
