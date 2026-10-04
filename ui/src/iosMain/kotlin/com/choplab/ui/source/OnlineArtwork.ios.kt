package com.choplab.ui.source

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.use

internal actual fun decodeBoundedOnlineArtwork(bytes: ByteArray): ImageBitmap? {
    if (bytes.size !in 1..1_048_576) return null
    return Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec ->
            val width = codec.width
            val height = codec.height
            if (!onlineArtworkDimensionsAllowed(width, height) || codec.frameCount != 1) return null

            // Check headers before allocating at most 2048 * 2048 * 4 bytes of decoded pixels.
            val bitmap = Bitmap()
            var transferred = false
            try {
                if (!bitmap.allocPixels(ImageInfo.makeN32Premul(width, height, ColorSpace.sRGB))) return null
                // Skiko rejects incomplete or invalid input instead of returning a partial image.
                codec.readPixels(bitmap)
                bitmap.asComposeImageBitmap().also { transferred = true }
            } finally {
                // Compose retains the successful bitmap without copying its pixels.
                if (!transferred) bitmap.close()
            }
        }
    }
}
