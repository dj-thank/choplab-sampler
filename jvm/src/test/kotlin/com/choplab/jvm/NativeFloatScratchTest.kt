package com.choplab.jvm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.*

class NativeFloatScratchTest {
    @Test fun decoderFloatHeadroomAndNativeIntegerPrecisionKeepStereoAndReleaseScratchExactly() {
        val root = Files.createTempDirectory("native-float-")
        try {
            val floats = floatArrayOf(1.234567f, -.9876543f, .00000013f, -.00000027f)
            NativeFloatScratch(root, 44_100, 2).use { writer ->
                val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                floats.forEach(buffer::putFloat); buffer.flip()
                writer.write(buffer, NativePcmEncoding.FLOAT32) { false }
                writer.finish().use { source ->
                    assertEquals(WavInfo(44_100, 2, 2, 32, true), source.info)
                    assertContentEquals(floats, source.read(0, 2))
                }
            }
            for (encoding in listOf(NativePcmEncoding.SIGNED16, NativePcmEncoding.SIGNED24, NativePcmEncoding.SIGNED32)) {
                val bits = encoding.bytes * 8
                val values = intArrayOf((1 shl (bits - 2)) + 3, -(1 shl (bits - 2)) - 7)
                val buffer = ByteBuffer.allocate(2 * encoding.bytes).order(ByteOrder.LITTLE_ENDIAN)
                values.forEach { value -> repeat(encoding.bytes) { buffer.put((value ushr (it * 8)).toByte()) } }; buffer.flip()
                NativeFloatScratch(root, 48_000, 2).use { writer ->
                    writer.write(buffer, encoding) { false }
                    writer.finish().use { source ->
                        val samples = source.read(0, 1)
                        values.forEachIndexed { i, value -> assertEquals((value.toDouble() / (1L shl (bits - 1))).toFloat(), samples[i]) }
                    }
                }
            }
            NativeFloatScratch(root, 48_000, 1).use { writer ->
                assertFailsWith<IllegalArgumentException> {
                    writer.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).flip(), NativePcmEncoding.FLOAT32) { false }
                }
            }
            NativeFloatScratch(root, 48_000, 1).use { writer ->
                assertFailsWith<java.util.concurrent.CancellationException> {
                    writer.write(ByteBuffer.allocate(4), NativePcmEncoding.FLOAT32) { true }
                }
            }
            assertEquals(0L, Files.list(root).use { it.count() })
        } finally { root.toFile().deleteRecursively() }
    }
}
