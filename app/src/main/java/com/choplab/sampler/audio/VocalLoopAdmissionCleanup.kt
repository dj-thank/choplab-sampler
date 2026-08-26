package com.choplab.sampler.audio

import java.io.File

/**
 * Stops a rejected vocal recording and deletes every app-owned take it can identify.
 * This path intentionally has no decoder or project-save callback.
 */
internal fun discardVocalTakeAfterLoopAdmissionFailure(
    requestedFile: File,
    stopRecorder: () -> Result<File>,
    deleteOwned: (File) -> Boolean,
): Result<Unit> {
    val stopped = runCatching { stopRecorder().getOrThrow() }
    var failure = stopped.exceptionOrNull()
    val files = buildList {
        stopped.getOrNull()?.let(::add)
        add(requestedFile)
    }.distinctBy { file ->
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
    }

    files.forEach { file ->
        val cleanupFailure = runCatching {
            check(deleteOwned(file)) { "録音テイクを破棄できませんでした" }
        }.exceptionOrNull()
        if (cleanupFailure != null) {
            failure = failure?.also { it.addSuppressed(cleanupFailure) } ?: cleanupFailure
        }
    }

    return failure?.let { Result.failure(it) } ?: Result.success(Unit)
}
