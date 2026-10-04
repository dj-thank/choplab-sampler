package com.choplab.ui.source

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineArtworkIosTest {
    private fun encoded(width: Int, height: Int, format: EncodedImageFormat = EncodedImageFormat.PNG): ByteArray =
        Bitmap().use { bitmap ->
            assertTrue(bitmap.allocN32Pixels(width, height))
            bitmap.erase(0xff227744.toInt())
            Image.makeFromBitmap(bitmap).use { image ->
                assertNotNull(image.encodeToData(format)).use { it.bytes }
            }
        }

    @Test fun singleFrameArtworkRetainsPixelsAfterDecoderResourcesClose() = runBlocking {
        for (format in listOf(EncodedImageFormat.PNG, EncodedImageFormat.JPEG)) {
            val image = assertNotNull(decodeOnlineArtwork(encoded(40, 30, format)))
            try {
                assertEquals(40, image.width)
                assertEquals(30, image.height)
                val pixel = IntArray(1)
                image.readPixels(pixel, width = 1, height = 1)
                assertEquals(255, pixel[0] ushr 24)
                if (format == EncodedImageFormat.PNG) assertEquals(0xff227744.toInt(), pixel[0])
            } finally {
                image.asSkiaBitmap().close()
            }
        }
        val gif = assertNotNull(decodeOnlineArtwork(hexBytes(STATIC_GIF)))
        try {
            assertEquals(2, gif.width)
            assertEquals(2, gif.height)
        } finally {
            gif.asSkiaBitmap().close()
        }
    }

    @Test fun invalidEmptyAndTruncatedInputsAreRejected() = runBlocking {
        assertNull(decodeOnlineArtwork(byteArrayOf()))
        assertNull(decodeBoundedOnlineArtwork(byteArrayOf()))
        assertNull(decodeOnlineArtwork(byteArrayOf(1, 2, 3)))
        val png = encoded(40, 30)
        assertNull(decodeOnlineArtwork(png.copyOf(png.size / 2)))
    }

    @Test fun encodedLimitIsInclusiveAndAppliesBeforeNativeParsing() = runBlocking {
        // Pad a valid PNG to exercise the exact encoded byte limit.
        val bytes = encoded(2, 2).copyOf(1_048_576)
        val image = assertNotNull(decodeOnlineArtwork(bytes))
        try {
            assertEquals(2, image.width)
            assertEquals(2, image.height)
        } finally {
            image.asSkiaBitmap().close()
        }
        assertNull(decodeOnlineArtwork(bytes.copyOf(1_048_577)))
        assertNull(decodeBoundedOnlineArtwork(bytes.copyOf(1_048_577)))
    }

    @Test fun maximumPixelAreaWorksAndEachOversizedSideIsRejected() = runBlocking {
        val image = assertNotNull(decodeOnlineArtwork(encoded(2048, 2048)))
        try {
            assertEquals(2048, image.width)
            assertEquals(2048, image.height)
        } finally {
            image.asSkiaBitmap().close()
        }
        assertNull(decodeOnlineArtwork(encoded(2049, 1)))
        assertNull(decodeOnlineArtwork(encoded(1, 2049)))
    }

    @Test fun validAnimatedGifIsRejected() = runBlocking {
        val bytes = hexBytes(ANIMATED_GIF)
        Data.makeFromBytes(bytes).use { data ->
            Codec.makeFromData(data).use { codec -> assertEquals(2, codec.frameCount) }
        }
        assertNull(decodeOnlineArtwork(bytes))
    }

    private fun hexBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        // Synthetic 2x2 solid-color GIFs: one red frame, or red then green. No external image assets.
        const val STATIC_GIF = "47494638376102000200810000ff00000000000000000000002c0000000002000200000806000108041010003b"
        const val ANIMATED_GIF = "47494638396102000200810000ff000000000000000000000021ff0b4e45545343415045322e30030100000021f904000a0000002c00000000020002000008060001080410100021f904010a0001002c00000000020002008100ff000000000000000000000806000108041010003b"
    }
}
