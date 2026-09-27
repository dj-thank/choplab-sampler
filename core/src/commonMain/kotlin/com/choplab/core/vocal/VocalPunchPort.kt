package com.choplab.core.vocal

import com.choplab.core.model.Project
import kotlinx.coroutines.flow.StateFlow

enum class PunchPhase { IDLE, OPENING, PRE_ROLL, CAPTURING, SAVING }
enum class PunchProblem { INVALID, BUSY, PERMISSION, NO_INPUT, NO_OUTPUT, NO_ROOM, STALE, INTERRUPTED, CUE, EMPTY, SAVE_FAILED, CANCELLED }
data class VocalPunchProgress(val phase: PunchPhase = PunchPhase.IDLE, val pass: Int = 0, val total: Int = 1,
                              val alignment: RecordingAlignment = RecordingAlignment.unmeasured())
data class VocalPunchResult(val captured: VocalCapturedSession? = null, val alignment: RecordingAlignment = RecordingAlignment.unmeasured(),
                            val problem: PunchProblem? = null)

/** Owns a single platform input session. The caller separately commits the returned candidates in one edit. */
interface VocalPunchPort {
    val progress: StateFlow<VocalPunchProgress>
    suspend fun capture(project: Project, expectedRevision: Long, request: VocalPunchRequest, stopped: () -> Boolean = { false }): VocalPunchResult
    /** Ends the session and retains audio already captured; cancellation before the first gate creates no candidate. */
    fun requestStop()
    /** Route loss/host interruption also invalidates the session's correction. */
    fun interrupt()
}
