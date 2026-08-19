package com.choplab.desktop.audio

import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioSystem
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

    override fun stop() {
        clip?.stop()
    }

    override fun close() {
        clip?.close()
        clip = null
        loadedFile = null
    }
}
