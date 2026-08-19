package com.choplab.desktop.audio

import java.io.File

interface LocalAudioPlayer : AutoCloseable {
    val loadedFile: File?
    val isPlaying: Boolean

    fun load(file: File)

    fun play()

    fun stop()
}
