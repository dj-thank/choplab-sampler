package com.choplab.sampler.ui

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadTrimBoundary
import com.choplab.sampler.model.PadTrimPrecision
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SliceRange
import com.choplab.sampler.model.stepPadTrimBoundary
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrecisionTrimControlsDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startNumberWheelScrollsThroughBoundedNumericFrames() {
        val audio = PcmAudio(20L, "wheel.wav", ShortArray(10_000), 1_000)
        var observedStart = 2_000
        composeRule.setContent {
            var pad by remember {
                mutableStateOf(PadModel(0, audio, startFrame = 2_000, endFrame = 8_000))
            }
            observedStart = pad.startFrame
            MaterialTheme {
                PrecisionTrimControls(
                    pad = pad,
                    activeBoundary = PadTrimBoundary.START,
                    precision = PadTrimPrecision.MILLISECOND,
                    focusWindow = SliceRange(1_500, 2_500),
                    onBoundarySelected = {},
                    onPrecisionSelected = {},
                    onBoundaryTicks = { boundary, ticks ->
                        pad = stepPadTrimBoundary(pad, boundary, ticks, PadTrimPrecision.MILLISECOND)
                    },
                    modifier = Modifier.height(160.dp),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithContentDescription("ここから / START 数値ホイール。上下にスクロールして調整")
            .performTouchInput { swipeUp(durationMillis = 600) }
        composeRule.waitForIdle()

        assertTrue("An upward number-wheel swipe should move START later", observedStart > 2_000)
    }
}
