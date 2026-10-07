package com.choplab.desktop.next

import com.choplab.jvm.InputInterruption
import com.choplab.jvm.VoiceTakes
import com.choplab.ui.RecordingInputReadout
import com.choplab.ui.RecordingInterruption

internal fun VoiceTakes.inputReadout() = RecordingInputReadout(
    recordedMillis = recordedMillis,
    limitMillis = limitMillis,
    peakLevel = peakLevel,
    pendingSave = pendingSave,
    interruption = when (interruption) {
        InputInterruption.DEVICE_LOST -> RecordingInterruption.DEVICE_LOST
        InputInterruption.READ_FAILED -> RecordingInterruption.READ_FAILED
        InputInterruption.STORAGE_FAILED -> RecordingInterruption.STORAGE_FAILED
        null -> null
    },
)
