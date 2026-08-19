package com.choplab.desktop.audio

import java.io.File

interface LocalAudioPlayer : AutoCloseable {
    val loadedFile: File?

    fun load(file: File)

    fun play()

    fun stop()
}
