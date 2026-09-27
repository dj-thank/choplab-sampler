package com.choplab.ui.source

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

internal actual fun decodeBoundedOnlineArtwork(bytes: ByteArray): ImageBitmap? = Data.makeFromBytes(bytes).use { data ->
    Codec.makeFromData(data).use { codec ->
        if (!onlineArtworkDimensionsAllowed(codec.width, codec.height) || codec.frameCount > 1) null
        else codec.readPixels().asComposeImageBitmap()
    }
}
