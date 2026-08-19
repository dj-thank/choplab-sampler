package com.choplab.desktop.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Streaming mono PCM-16 WAV writer for the Windows audio adapters. */
class DesktopWavFileWriter(
    file: File,
    private val sampleRate: Int,
    private val channelCount: Int = 1,
) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private var bytesWritten = 0L
    private var closed = false

    init {
        require(sampleRate in 8_000..192_000)
        require(channelCount in 1..2)
        output.setLength(0L)
        writeHeader(0)
    }

    @Synchronized
    fun writePcm16Bytes(bytes: ByteArray, count: Int = bytes.size) {
        check(!closed)
        require(count in 0..bytes.size)
        output.write(bytes, 0, count)
        bytesWritten += count
    }

    @Synchronized
    fun writePcm16(samples: ShortArray, count: Int = samples.size) {
        check(!closed)
        require(count in 0..samples.size)
        val bytes = ByteArray(count * 2)
        for (index in 0 until count) {
            val sample = samples[index].toInt()
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
        writePcm16Bytes(bytes)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val size = bytesWritten.coerceAtMost(0xFFFF_FFFFL).toInt()
        output.seek(4L)
        writeInt(36 + size)
        output.seek(40L)
        writeInt(size)
        output.close()
    }

    private fun writeHeader(dataSize: Int) {
        val byteRate = sampleRate * channelCount * 2
        val blockAlign = channelCount * 2
        output.writeBytes("RIFF")
        writeInt(36 + dataSize)
        output.writeBytes("WAVEfmt ")
        writeInt(16)
        writeShort(1)
        writeShort(channelCount)
        writeInt(sampleRate)
        writeInt(byteRate)
        writeShort(blockAlign)
        writeShort(16)
        output.writeBytes("data")
        writeInt(dataSize)
    }

    private fun writeInt(value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 24) and 0xFF)
    }

    private fun writeShort(value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }
}
