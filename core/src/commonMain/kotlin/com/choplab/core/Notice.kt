package com.choplab.core

/** Hosts localize these values. Exceptions, provider text and private paths are not UI copy. */
sealed interface Notice {
    data class Rejected(val reason: Rejection) : Notice
    data class Failed(val operation: Operation) : Notice
    data class Completed(val operation: Operation) : Notice
    data class Cancelled(val operation: Operation) : Notice
    data class CacheMiss(val assetHash: String) : Notice
    data object StaleCompletion : Notice
}
enum class Rejection { INVALID_INPUT, BUSY, NO_SOURCE, EMPTY_PAD, NO_HISTORY, CLOSED, ENGINE_REFUSED }
enum class Operation { IMPORT, OPEN, SAVE, EXPORT, EDIT, PLAYBACK, RECOVERY }
