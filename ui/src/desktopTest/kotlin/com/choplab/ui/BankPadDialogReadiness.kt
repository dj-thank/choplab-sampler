@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui

import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** A removed draft can leave the dialog's fading input layer above the editor. Render until it is released. */
internal suspend fun ImageComposeScene.awaitBankPadDialogClosed(
    renderFrame: () -> Unit = { render(System.nanoTime()).close() },
) = withTimeout(10_000) {
    do {
        renderFrame()
        delay(1)
    } while (semanticsOwners.size != 1)
}
