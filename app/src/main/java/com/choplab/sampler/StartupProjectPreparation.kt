package com.choplab.sampler

import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.persistence.RecoveredProjectState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface PreparedStartupProject {
    data class Restored(val project: RecoveredProjectState) : PreparedStartupProject
    data class Fresh(val kit: BuiltInDrumKits.PreparedStarterKit) : PreparedStartupProject
}

/** Prepares data only. The caller still owns revision/epoch admission and UI publication. */
internal suspend fun prepareStartupProject(
    load: () -> RecoveredProjectState?,
    createStarter: () -> BuiltInDrumKits.PreparedStarterKit = BuiltInDrumKits::prepareStarterKit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    synthesisDispatcher: CoroutineDispatcher = Dispatchers.Default,
): Result<PreparedStartupProject> = try {
    val recovered = withContext(ioDispatcher) { load() }
    val prepared = if (recovered != null) {
        PreparedStartupProject.Restored(recovered)
    } else {
        // Prepare only audio. UI/navigation state is read later, when the caller commits.
        val starter = withContext(synthesisDispatcher) { createStarter() }
        PreparedStartupProject.Fresh(starter)
    }
    Result.success(prepared)
} catch (canceled: CancellationException) {
    // A canceled ViewModel must never publish a late starter or convert cancellation to failure UI.
    throw canceled
} catch (failure: Exception) {
    Result.failure(failure)
}
