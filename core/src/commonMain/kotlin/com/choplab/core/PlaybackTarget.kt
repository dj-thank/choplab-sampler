package com.choplab.core

import com.choplab.core.model.FrozenList
import com.choplab.core.model.frozenListOf
import com.choplab.core.model.requireId

/** Session choice, never inferred from track count and never serialized as document content. */
sealed interface PlaybackTarget {
    data class Pattern(val id: String) : PlaybackTarget { init { requireId(id) } }
    /** All document clips, plus only explicitly chosen takes. An empty arrangement stays silent. */
    data class Arrangement(val takeIds: FrozenList<String> = frozenListOf()) : PlaybackTarget {
        init { require(takeIds.size <= 1024 && takeIds.distinct().size == takeIds.size); takeIds.forEach(::requireId) }
    }
}
