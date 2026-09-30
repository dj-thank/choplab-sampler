package com.choplab.ui.source

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only encoded artwork reaches this boundary. Headers are checked before allocating decoded pixels. */
suspend fun decodeOnlineArtwork(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    if (bytes.size !in 1..1_048_576) null else runCatching { decodeBoundedOnlineArtwork(bytes) }.getOrNull()
}
internal fun onlineArtworkDimensionsAllowed(width: Int, height: Int): Boolean =
    width in 1..2048 && height in 1..2048 && width.toLong() * height <= 4_194_304
internal expect fun decodeBoundedOnlineArtwork(bytes: ByteArray): ImageBitmap?
