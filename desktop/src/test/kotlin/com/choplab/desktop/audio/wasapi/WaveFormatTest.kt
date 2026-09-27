package com.choplab.desktop.audio.wasapi

import com.sun.jna.Memory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WaveFormatTest {
    @Test
    fun readsPcmWaveFormatEx() {
        val memory = Memory(18L).apply {
            setShort(0, WAVE_FORMAT_PCM.toShort())
            setShort(2, 2)
            setInt(4, 48_000)
            setInt(8, 192_000)
            setShort(12, 4)
            setShort(14, 16)
            setShort(16, 0)
        }

        val format = WaveFormat.read(memory)

        assertEquals(2, format.channels)
        assertEquals(48_000, format.sampleRate)
        assertEquals(16, format.bitsPerSample)
        assertEquals(WaveEncoding.PCM_INTEGER, format.encoding)
    }

    @Test
    fun readsFloatWaveFormatExtensible() {
        // Standard little-endian WAVEFORMATEXTENSIBLE bytes, independent of the production GUID constants.
        nativeBytes("fe ff 02 00 80 bb 00 00 00 dc 05 00 08 00 20 00 16 00 20 00 03 00 00 00 " +
            "03 00 00 00 00 00 10 00 80 00 00 aa 00 38 9b 71").use { memory ->
            val format = WaveFormat.read(memory)
            assertEquals(65534, format.formatTag)
            assertEquals(2, format.channels)
            assertEquals(48_000, format.sampleRate)
            assertEquals(384_000, format.averageBytesPerSecond)
            assertEquals(8, format.blockAlign)
            assertEquals(32, format.bitsPerSample)
            assertEquals(22, format.extraSize)
            assertEquals(32, format.validBitsPerSample)
            assertEquals(3L, format.channelMask)
            assertEquals("{00000003-0000-0010-8000-00AA00389B71}", format.subFormat)
            assertEquals(WaveEncoding.IEEE_FLOAT, format.encoding)
            assertEquals(WaveEncoding.IEEE_FLOAT, format.copy(subFormat = format.subFormat!!.lowercase()).encoding)
        }
    }

    @Test
    fun readsPcmWaveFormatExtensibleWith24ValidBitsIn32BitContainers() {
        nativeBytes("fe ff 02 00 80 bb 00 00 00 dc 05 00 08 00 20 00 16 00 18 00 03 00 00 00 " +
            "01 00 00 00 00 00 10 00 80 00 00 aa 00 38 9b 71").use { memory ->
            val format = WaveFormat.read(memory)
            assertEquals(32, format.bitsPerSample)
            assertEquals(24, format.validBitsPerSample)
            assertEquals(3L, format.channelMask)
            assertEquals("{00000001-0000-0010-8000-00AA00389B71}", format.subFormat)
            assertEquals(WaveEncoding.PCM_INTEGER, format.encoding)
        }
    }

    @Test
    fun unknownSubtypesIncludingTheFormerNonstandardGuidsRemainUnknown() {
        for (subtype in listOf(
            "06 00 00 00 00 00 10 00 80 00 00 aa 00 38 9b 71",
            "01 00 00 00 00 00 10 00 80 00 00 a0 c9 22 31 96",
            "03 00 00 00 00 00 10 00 80 00 00 a0 c9 22 31 96",
        )) {
            nativeBytes("fe ff 02 00 80 bb 00 00 00 dc 05 00 08 00 20 00 16 00 20 00 03 00 00 00 $subtype").use {
                assertEquals(WaveEncoding.UNKNOWN, WaveFormat.read(it).encoding)
            }
        }
    }

    @Test
    fun rejectsUnboundedNativeValues() {
        val memory = Memory(18L).apply {
            clear()
            setShort(0, WAVE_FORMAT_PCM.toShort())
            setShort(2, 0)
            setInt(4, Int.MAX_VALUE)
            setInt(8, 1)
            setShort(12, 1)
            setShort(14, 16)
        }

        assertFailsWith<IllegalArgumentException> { WaveFormat.read(memory) }
    }

    private fun nativeBytes(hex: String): Memory {
        val bytes = hex.split(' ').map { it.toInt(16).toByte() }.toByteArray()
        return Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
    }
}
