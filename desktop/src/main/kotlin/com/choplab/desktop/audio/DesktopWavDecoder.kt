package com.choplab.desktop.audio

import com.choplab.sampler.model.PcmAudio
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/** JVM adapter for the same mono PCM shape consumed by the shared deck. */
object DesktopWavDecoder {
    fun decode(file: File): PcmAudio {
        require(file.isFile) { "Audio file does not exist: ${file.path}" }
        AudioSystem.getAudioInputStream(file).use { source ->
            val sourceFormat = source.format
            val targetFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sourceFormat.sampleRate,
                16,
                sourceFormat.channels,
                sourceFormat.channels * 2,
                sourceFormat.sampleRate,
                false,
            )
            val pcmStream = if (sourceFormat.matches(targetFormat)) {
                source
            } else {
                AudioSystem.getAudioInputStream(targetFormat, source)
            }
            pcmStream.use { pcm ->
                return readMono(file.name, pcm)
            }
        }
    }

    private fun readMono(name: String, stream: AudioInputStream): PcmAudio {
        val bytes = stream.readBytes()
        val channels = stream.format.channels.coerceAtLeast(1)
        val frameBytes = channels * 2
        val frames = bytes.size / frameBytes
        val samples = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) {
                val offset = frame * frameBytes + channel * 2
                val value = (bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)
                sum += value.toShort().toInt()
            }
            samples[frame] = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return PcmAudio(
            name = name,
            samples = samples,
            sampleRate = stream.format.sampleRate.toInt().coerceAtLeast(1),
        )
    }
}
