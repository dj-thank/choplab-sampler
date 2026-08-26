package com.choplab.sampler.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FinishWorkspacePresentationTest {
    @Test
    fun readyFinishCopySeparatesProjectSafetyFromAudioExport() {
        assertEquals(
            FinishReadinessPresentation(
                title = "制作を保存・書き出し",
                guidance = "制作は端末内へ自動保存。必要なら制作ファイルを保存し、再生で確認して4小節WAVを書き出せます。",
            ),
            finishReadinessPresentation(readyForWav = true),
        )
    }

    @Test
    fun clearCopyNamesOnlyTheBeatPlacementsThatWillBeRemoved() {
        assertEquals(
            FinishClearActionPresentation(
                label = "ビート配置を消す\nCLEAR STEPS",
                confirmLabel = "もう一度で配置を削除",
            ),
            finishClearActionPresentation(),
        )
    }
}
