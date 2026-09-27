package com.choplab.core

/** Hosts localize these values. Exceptions, provider text and private paths are not UI copy. */
sealed interface Notice {
    data class Rejected(val reason: Rejection) : Notice
    data class Failed(val operation: Operation) : Notice
    data class Completed(val operation: Operation) : Notice
    data class Cancelled(val operation: Operation) : Notice
    data class CacheMiss(val assetHash: String) : Notice
    /**
     * A project file of the earlier app gave a new document with only its audio: [audio] sounds, of which [unplaced]
     * are neither the original nor on a PAD and stay in the document only. Its PADs' cuts and patterns are not carried over.
     */
    data class Rescued(val audio: Int, val unplaced: Int) : Notice {
        init { require(audio >= 0 && unplaced in 0..audio) }
    }
    data object StaleCompletion : Notice
}
enum class Rejection { INVALID_INPUT, BUSY, NO_SOURCE, EMPTY_PAD, NO_HISTORY, CLOSED, ENGINE_REFUSED }
enum class Operation { IMPORT, OPEN, SAVE, EXPORT, EDIT, PLAYBACK, RECOVERY }
