package com.choplab.desktop.audio.wasapi

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasapiEventNativeTest {
    @Test
    fun sharedEventInitializationUsesStandardIndependentFormatFlagsAndVtableEntries() {
        wasapiClientFormat().use { memory ->
            val expected = ("fe ff 02 00 80 bb 00 00 00 dc 05 00 08 00 20 00 16 00 20 00 03 00 00 00 " +
                "03 00 00 00 00 00 10 00 80 00 00 aa 00 38 9b 71").split(' ').map { it.toInt(16).toByte() }.toByteArray()
            assertContentEquals(expected, memory.getByteArray(0, 40))
            for (mode in WasapiStreamMode.entries) {
                val indexes = mutableListOf<Int>()
                val event = WinNT.HANDLE(Pointer(9L))
                val calls = WasapiClientCalls { index, args ->
                    indexes += index
                    when (index) {
                        3 -> {
                            assertEquals(6, args.size)
                            assertEquals(0, args[0]) // AUDCLNT_SHAREMODE_SHARED
                            assertEquals(if (mode == WasapiStreamMode.LOOPBACK) 0x880e0000.toInt() else 0x880c0000.toInt(), args[1])
                            assertEquals(0L, args[2])
                            assertEquals(0L, args[3])
                            assertEquals(memory, args[4])
                            assertEquals(Pointer.NULL, args[5])
                        }
                        4 -> (args[0] as IntByReference).value = 480
                        6 -> (args[0] as IntByReference).value = 96
                        13 -> assertEquals(event, args.single())
                        14 -> {
                            val iid = (args[0] as Guid.REFIID).value.toGuidString()
                            assertEquals(if (mode == WasapiStreamMode.OUTPUT) "{F294ACFC-3146-4483-A7BF-ADDCA7C260E2}"
                                else "{C8ADBD64-E71E-48A0-A4DE-185C395CD317}", iid)
                            (args[1] as PointerByReference).value = Pointer(12L)
                        }
                    }
                    0
                }
                calls.initialize(mode, memory)
                calls.setEvent(event)
                assertEquals(480, calls.bufferFrames())
                assertEquals(Pointer(12L), calls.service(mode))
                calls.start()
                assertEquals(96, calls.paddingFrames())
                calls.stop()
                assertEquals(listOf(3, 13, 4, 14, 10, 6, 11), indexes)
            }
        }
    }

    @Test
    fun captureCopiesStereoAndNullSilentPacketReleasesEveryAcquisitionExactlyOnce() {
        Memory(32).use { native ->
            val input = floatArrayOf(1.5f, -1.75f, .25f, -.125f, 0f, -0f, .8f, .2f)
            native.write(0, input, 0, input.size)
            var silent = false
            val indexes = mutableListOf<Int>()
            val calls = WasapiCaptureCalls { index, args ->
                indexes += index
                when (index) {
                    5 -> (args[0] as IntByReference).value = 4
                    3 -> {
                        (args[0] as PointerByReference).value = if (silent) Pointer.NULL else native
                        (args[1] as IntByReference).value = 4
                        (args[2] as IntByReference).value = if (silent) 2 else 0
                        (args[3] as LongByReference).value = 0x1_0000_0001L
                        (args[4] as LongByReference).value = 9_876_543_210L
                    }
                    4 -> assertEquals(4, args.single())
                }
                0
            }
            val packet = WasapiPacket()
            val result = FloatArray(8)
            assertEquals(4, calls.read(result, packet))
            assertContentEquals(input, result)
            assertEquals(0x1_0000_0001L, packet.firstFrame)
            assertEquals(9_876_543_210L, packet.qpc100ns)
            silent = true
            assertEquals(4, calls.read(result, packet))
            assertContentEquals(FloatArray(8), result)
            assertEquals(2, packet.flags)
            assertEquals(listOf(5, 3, 4, 5, 3, 4), indexes)
        }
    }

    @Test
    fun emptyAndInvalidPacketsNeverReadAStaleNativePointerOrLoseTheRelease() {
        for (case in listOf("empty", "oversized", "null", "failed")) {
            var releases = 0
            val calls = WasapiCaptureCalls { index, args ->
                when (index) {
                    5 -> (args[0] as IntByReference).value = 4
                    3 -> {
                        (args[0] as PointerByReference).value = Pointer.NULL
                        (args[1] as IntByReference).value = if (case == "empty") 0 else 4
                        if (case == "empty") return@WasapiCaptureCalls 0x08890001
                        if (case == "failed") return@WasapiCaptureCalls 0x88890004.toInt()
                    }
                    4 -> { assertEquals(4, args.single()); releases++ }
                }
                0
            }
            val target = FloatArray(if (case == "oversized") 2 else 8) { 3f }
            val packet = WasapiPacket().apply { firstFrame = -123; qpc100ns = -456 }
            if (case == "empty") {
                assertEquals(0, calls.read(target, packet))
                assertEquals(-123, packet.firstFrame)
                assertEquals(-456, packet.qpc100ns)
            } else assertFailsWith<IllegalStateException> { calls.read(target, packet) }
            assertEquals(if (case in listOf("empty", "failed")) 0 else 1, releases)
            assertTrue(target.all { it == 3f })
        }
    }

    @Test
    fun outputCopiesExactFloatHeadroomAndFailedCopiesReleaseAsSilence() {
        Memory(16).use { native ->
            var nullBuffer = false
            val released = mutableListOf<Pair<Int, Int>>()
            val calls = WasapiRenderCalls { index, args ->
                when (index) {
                    3 -> {
                        assertEquals(2, args[0])
                        (args[1] as PointerByReference).value = if (nullBuffer) Pointer.NULL else native
                    }
                    4 -> released += (args[0] as Int) to (args[1] as Int)
                }
                0
            }
            val input = floatArrayOf(1.3f, -.7f, -.4f, 1.8f)
            calls.write(input, 2)
            assertContentEquals(input, native.getFloatArray(0, 4))
            nullBuffer = true
            assertFailsWith<WasapiStreamException> { calls.write(input, 2) }
            assertEquals(listOf(2 to 0, 2 to 2), released)
        }
    }

    @Test
    fun unsupportedLoopbackWindowsAndUnboundedNativeFramesAreRefused() {
        assertFalse(eventLoopbackSupported(6, 99_999))
        assertFalse(eventLoopbackSupported(10, 15_062))
        assertTrue(eventLoopbackSupported(10, 15_063))
        assertTrue(eventLoopbackSupported(10, 26_100))
        assertTrue(eventLoopbackSupported(11, 1))
        for (value in listOf(0, -1, 48_001, Int.MAX_VALUE)) {
            val calls = WasapiClientCalls { _, args -> (args.single() as IntByReference).value = value; 0 }
            assertEquals(WasapiFault.INVALID_BUFFER, assertFailsWith<WasapiStreamException> { calls.bufferFrames() }.failure.fault)
        }
    }
}
