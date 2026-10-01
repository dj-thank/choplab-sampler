package com.choplab.ui.vocal

import com.choplab.core.ai.*
import com.choplab.core.model.Asset
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The host shares this preview with TTS; neither feature creates an additional render voice. */
interface VocalPracticePort {
    val renderer: VocalPracticeRenderer
    val preview: VocalPreviewPort
}

class SourcePracticeAdapter(private val port: VocalPracticePort, scope: CoroutineScope,
    private val guard: suspend (Long) -> PracticeResult<Unit>,
    /** The presenter serializes this immediate claim with recording starts and document changes. */
    private val claim: suspend (Asset, Boolean, Long) -> TtsResult<Unit>,
) : VocalPracticePorts {
    private val mutable = MutableStateFlow(current())
    override val previewState = mutable.asStateFlow()
    override val renderer get() = port.renderer
    private val observer = scope.launch { port.preview.state.collect { mutable.value = current() } }
    override fun release() { observer.cancel() }
    private fun current() = port.preview.state.value.let {
        PracticePreviewState(it.ownsSource && it.owner == VocalPreviewOwner.PRACTICE,
            it.owner == VocalPreviewOwner.PRACTICE && it.phase == VocalPreviewPhase.PLAYING,
            if (it.phase == VocalPreviewPhase.FAILED && it.owner == VocalPreviewOwner.PRACTICE) PracticeProblem.RESTORE_FAILED else null)
    }
    override suspend fun prepareAllowed(expectedRevision: Long) = guard(expectedRevision)
    override suspend fun preview(asset: Asset, loop: Boolean, expectedRevision: Long): PracticeResult<Unit> {
        when (val result = claim(asset, loop, expectedRevision)) {
            is TtsResult.Failure -> return PracticeResult.Failure(problem(result.failure.problem))
            is TtsResult.Success -> Unit
        }
        val state = port.preview.state.first { it.owner != VocalPreviewOwner.PRACTICE || it.phase != VocalPreviewPhase.LOADING }
        mutable.value = current()
        return if (state.owner == VocalPreviewOwner.PRACTICE && state.phase == VocalPreviewPhase.PLAYING && state.ownsSource)
            PracticeResult.Success(Unit) else PracticeResult.Failure(problem(state.failure?.problem))
    }
    override fun requestStopPreview() = port.preview.requestStop(VocalPreviewOwner.PRACTICE)
    override suspend fun stopPreview(): PracticeResult<Unit> {
        val result = port.preview.stop(VocalPreviewOwner.PRACTICE)
        mutable.value = current()
        return if (result is TtsResult.Success && !mutable.value.ownsSource) PracticeResult.Success(Unit)
            else PracticeResult.Failure(PracticeProblem.RESTORE_FAILED)
    }
    private fun problem(problem: TtsProblem?) = when (problem) {
        TtsProblem.BUSY -> PracticeProblem.BUSY
        TtsProblem.RECORDING -> PracticeProblem.RECORDING
        TtsProblem.STALE_DOCUMENT -> PracticeProblem.STALE
        TtsProblem.MEMORY_LIMIT, TtsProblem.TOO_LARGE -> PracticeProblem.LIMIT
        TtsProblem.CANCELLED, TtsProblem.CLOSED -> PracticeProblem.CANCELLED
        else -> PracticeProblem.NO_OUTPUT
    }
}
