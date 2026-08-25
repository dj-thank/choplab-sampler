package com.choplab.sampler.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureEntryActionPresentationTest {
    @Test
    fun stackedAndWideFirstEntryShareOneActionVocabulary() {
        assertEquals(
            CaptureEntryActionPresentation(
                loadAudioLabel = "曲を読み込む\nLOAD AUDIO",
                openProjectLabel = "制作を開く\nOPEN PROJECT",
                recordingSectionTitle = "録音から始める",
                starterDemoTitle = "すぐ試す  DUSTY JAZZデモ",
                starterDemoGuidance = "PAD、ビート、保存を音入りで試せます",
                starterDemoActionLabel = "デモを試す\nTRY BEAT",
                starterDemoCompactActionLabel = "デモを試す",
            ),
            captureEntryActionPresentation(),
        )
    }
}
