package com.choplab.desktop.audio

import com.choplab.sampler.model.PcmAudio
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.Clip

/** First desktop proof: user-selected PCM WAV playback through the JDK audio adapter. */
class JavaSoundWavPlayer : LocalAudioPlayer {
    private var clip: Clip? = null

    override var loadedFile: File? = null
        private set

    override val isPlaying: Boolean
        get() = clip?.isRunning == true

    override fun load(file: File) {
        require(file.isFile) { "Audio file does not exist: ${file.path}" }
        require(AudioSystem.getAudioFileFormat(file).type == AudioFileFormat.Type.WAVE) {
            "The first desktop audio adapter supports WAV files only"
        }

        val newClip = AudioSystem.getClip()
        AudioSystem.getAudioInputStream(file).use { stream -> newClip.open(stream) }
        clip?.close()
        clip = newClip
        loadedFile = file
    }

    override fun play() {
        val activeClip = clip ?: error("Load a WAV file before playing")
        activeClip.stop()
        activeClip.framePosition = 0
        activeClip.start()
    }

    fun playPcm(audio: PcmAudio, startFrame: Int = 0, endFrame: Int = audio.frameCount) {
        val start = startFrame.coerceIn(0, (audio.frameCount - 1).coerceAtLeast(0))
        val end = endFrame.coerceIn(start + 1, audio.frameCount)
        val samples = audio.samples.copyOfRange(start, end)
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xFF).toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        val format = AudioFormat(audio.sampleRate.toFloat(), 16, 1, true, false)
        val newClip = AudioSystem.getClip()
        AudioSystem.getAudioInputStream(
            format,
            AudioInputStream(ByteArrayInputStream(bytes), format, samples.size.toLong()),
        ).use { stream -> newClip.open(stream) }
        clip?.close()
        clip = newClip
        loadedFile = null
        newClip.start()
    }

    override fun stop() {
        clip?.stop()
    }

    override fun close() {
        clip?.close()
        clip = null
        loadedFile = null
    }
}
