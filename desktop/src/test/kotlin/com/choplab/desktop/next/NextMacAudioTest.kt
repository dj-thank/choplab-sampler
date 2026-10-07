package com.choplab.desktop.next

import com.choplab.desktop.audio.MacMicrophonePermission
import com.choplab.jvm.MicInput
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NextMacAudioTest {
    @Test fun selectedDevicesReachTheActualFactoriesAndSurviveRefresh() = runBlocking<Unit> {
        var microphone = ""
        var output = ""
        val inputs = listOf("input-a", "input-b").map { key -> MacAudioEndpoint(key, key, openInput = { microphone = key; Input() }) }
        val outputs = listOf("output-a", "output-b").map { key -> MacAudioEndpoint(key, key, openOutput = { output = key; Sink() }) }
        val audio = NextMacAudio({ inputs + outputs }, permission = { MacMicrophonePermission.Status.AUTHORIZED })
        val choices = audio.devices()
        val chosen = MacAudioSelection(choices.inputs[1].id, choices.outputs[1].id)
        var released = 0
        assertTrue(audio.select(chosen) { released++; true })
        assertEquals(choices, audio.devices())
        val input = assertNotNull(audio.openMicrophone())
        audio.openOutput().close()
        assertEquals("input-b", microphone)
        assertEquals("output-b", output)
        assertFalse(audio.select(chosen) { released++; true }, "Active input cannot be rerouted")
        assertEquals(1, released)
        input.close()
        assertTrue(audio.select(chosen) { released++; true }, "Explicit reconnect reuses the selected endpoints")
    }

    @Test fun permissionDenialDoesNotOpenInputAndUnknownIsNotReportedAsDenial() = runBlocking<Unit> {
        var opened = 0
        var permission = MacMicrophonePermission.Status.DENIED
        val audio = NextMacAudio({ listOf(MacAudioEndpoint("default", "Default", { opened++; Input() }, { Sink() })) },
            permission = { permission })
        audio.devices()
        assertNull(audio.openMicrophone())
        assertEquals(MacMicrophonePermission.Status.DENIED, audio.microphonePermission)
        permission = MacMicrophonePermission.Status.UNKNOWN
        assertNull(audio.openMicrophone())
        assertEquals(MacMicrophonePermission.Status.UNKNOWN, audio.microphonePermission)
        assertEquals(0, opened)
    }

    @Test fun openingAndFailedOutputReleaseDoNotChangeTheSelectedRoute() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val audio = NextMacAudio({ listOf(MacAudioEndpoint("default", "Default", {
            started.countDown(); proceed.await(5, TimeUnit.SECONDS); Input()
        }, { Sink() })) }, permission = { MacMicrophonePermission.Status.AUTHORIZED })
        audio.devices()
        val input = async(Dispatchers.IO) { audio.openMicrophone() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertFalse(audio.select(MacAudioSelection()) { error("Opening input owns route") })
        proceed.countDown()
        input.await()?.close()
        assertFalse(audio.select(MacAudioSelection()) { false })
        assertEquals(MacAudioSelection(), audio.selection.value)
    }

    private class Input : MicInput {
        override val sampleRate = 48_000
        override fun read(buffer: FloatArray) = -1
        override fun stop() {}
        override fun close() {}
    }
    private class Sink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        override fun write(bytes: ByteArray, offset: Int, length: Int) = length
        override fun close() {}
    }
}
