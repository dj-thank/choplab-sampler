package com.choplab.core

import com.choplab.core.model.FrozenList
import com.choplab.core.model.frozenListOf
import com.choplab.core.model.requireId

/** Session choice, never inferred from track count and never serialized as document content. */
sealed interface PlaybackTarget {
    data class Pattern(val id: String) : PlaybackTarget { init { requireId(id) } }
    /** All document clips, plus only explicitly chosen takes. An empty arrangement stays silent. */
    data class Arrangement(val takeIds: FrozenList<String> = frozenListOf(), val minimumFrames: Long = 0) : PlaybackTarget {
        // A silent recording clock is session state, never document content or export padding.
        init { require(minimumFrames in 0..MAX_RECORDING_FRAMES) }
        init { require(takeIds.size <= 1024 && takeIds.distinct().size == takeIds.size); takeIds.forEach(::requireId) }
        companion object { const val MAX_RECORDING_FRAMES = 48_000L * 60 * 5 }
    }
}
