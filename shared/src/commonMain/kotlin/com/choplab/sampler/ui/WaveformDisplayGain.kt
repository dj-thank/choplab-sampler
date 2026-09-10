package com.choplab.sampler.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.choplab.sampler.model.PcmAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Scan once per immutable PCM source, not once per viewport or on the UI thread. */
@Composable
internal fun rememberWaveformDisplayGain(audio: PcmAudio): Float {
    var gain by remember(audio.id, audio.samples) { mutableFloatStateOf(1f) }
    LaunchedEffect(audio.id, audio.samples) {
        gain = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            waveformDisplayGain(audio.samples) { context.ensureActive() }
        }
    }
    return gain
}
