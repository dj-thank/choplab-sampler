package com.choplab.desktop.audio.wasapi

import com.sun.jna.Memory
import com.sun.jna.platform.win32.Guid
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
        val nativeSubFormat = Guid.GUID(KSDATAFORMAT_SUBTYPE_IEEE_FLOAT).apply { write() }
        val memory = Memory(40L).apply {
            clear()
            setShort(0, WAVE_FORMAT_EXTENSIBLE.toShort())
            setShort(2, 2)
            setInt(4, 48_000)
            setInt(8, 384_000)
            setShort(12, 8)
            setShort(14, 32)
            setShort(16, 22)
            setShort(18, 32)
            setInt(20, 3)
            write(24, nativeSubFormat.pointer.getByteArray(0, 16), 0, 16)
        }

        val format = WaveFormat.read(memory)

        assertEquals(32, format.validBitsPerSample)
        assertEquals(3L, format.channelMask)
        assertEquals(KSDATAFORMAT_SUBTYPE_IEEE_FLOAT, format.subFormat)
        assertEquals(WaveEncoding.IEEE_FLOAT, format.encoding)
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
}
