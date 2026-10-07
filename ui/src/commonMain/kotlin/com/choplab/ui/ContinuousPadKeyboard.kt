package com.choplab.ui

import androidx.compose.ui.input.key.*

/** One keyboard press owns one capture/hit. Losing focus or changing its mode cancels that ownership. */
internal class CEPadKeyGesture(
    private val padId: Int,
    private val capture: Boolean,
    private val gate: Boolean,
    private val noteRepeat: Boolean,
    private val captureAt: () -> ContinuousChopGesture?,
    private val hitAt: () -> Long?,
    private val action: (ContinuousEditorAction) -> Unit,
) {
    private var pressed: Key? = null
    private var cut: ContinuousChopGesture? = null
    private var hit: ContinuousHitGesture? = null
    private var holding = false

    fun key(event: KeyEvent): Boolean {
        if (event.key != Key.Spacebar && event.key != Key.Enter && event.key != Key.NumPadEnter) return false
        if (event.type == KeyEventType.KeyDown) {
            if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return false
            if (pressed != null) return true // OS autorepeat must not retrigger or recapture.
            pressed = event.key
            if (capture) cut = captureAt()
            else if (!noteRepeat) {
                hit = hitAt()?.let { ContinuousHitGesture(padId, it) }.also { it?.let { action(ContinuousEditorAction.BeginHit(it)) } }
                holding = gate
                action(if (gate) ContinuousEditorAction.HoldPad(padId) else ContinuousEditorAction.TapPad(padId))
            }
        } else if (event.type == KeyEventType.KeyUp && pressed == event.key) finish(cancelled = false)
        return true
    }

    fun cancel() = finish(cancelled = true)

    private fun finish(cancelled: Boolean) {
        if (pressed == null) return
        pressed = null
        val recorded = hit; hit = null
        val captured = cut; cut = null
        if (capture) {
            if (!cancelled && captured != null) action(ContinuousEditorAction.CapturePad(padId, captured))
        } else if (noteRepeat) {
            // Accessible/key activation retains the existing finite, engine-timed one-beat behavior.
            if (!cancelled) {
                hitAt()?.let { action(ContinuousEditorAction.BeginHit(ContinuousHitGesture(padId, it))) }
                action(ContinuousEditorAction.TapPad(padId))
            }
        } else {
            recorded?.let { action(ContinuousEditorAction.EndHit(it, cancelled, hitAt())) }
            if (holding) { holding = false; action(ContinuousEditorAction.ReleasePad(padId)) }
        }
    }
}
