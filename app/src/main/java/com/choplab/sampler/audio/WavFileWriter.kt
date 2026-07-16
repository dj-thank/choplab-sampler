package com.choplab.sampler.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming PCM-16 WAV writer. The RIFF and data sizes are patched when [close] is called.
 */
class WavFileWriter(
    file: File,
    private val sampleRate: Int,
    private val channelCount: Int,
) : Closeable {
    private val randomAccessFile = RandomAccessFile(file, "rw")
    private var pcmBytesWritten = 0L
    private var closed = false

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount in 1..2) { "Only mono and stereo are supported" }
        randomAccessFile.setLength(0L)
        writeHeader(dataSize = 0)
    }

    @Synchronized
    fun writePcm16(samples: ShortArray, sampleCount: Int = samples.size) {
        check(!closed) { "WAV writer is already closed" }
        require(sampleCount in 0..samples.size)
        val bytes = ByteBuffer.allocate(sampleCount * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until sampleCount) {
            bytes.putShort(samples[index])
        }
        val array = bytes.array()
        randomAccessFile.write(array)
        pcmBytesWritten += array.size
    }

    @Synchronized
    fun writePcm16Bytes(bytes: ByteArray, byteCount: Int = bytes.size) {
        check(!closed) { "WAV writer is already closed" }
        require(byteCount in 0..bytes.size)
        randomAccessFile.write(bytes, 0, byteCount)
        pcmBytesWritten += byteCount
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true

        val safeDataSize = pcmBytesWritten.coerceAtMost(0xFFFF_FFFFL).toInt()
        randomAccessFile.seek(4L)
        writeIntLittleEndian(36 + safeDataSize)
        randomAccessFile.seek(40L)
        writeIntLittleEndian(safeDataSize)
        randomAccessFile.close()
    }

    private fun writeHeader(dataSize: Int) {
        val byteRate = sampleRate * channelCount * Short.SIZE_BYTES
        val blockAlign = channelCount * Short.SIZE_BYTES

        randomAccessFile.writeBytes("RIFF")
        writeIntLittleEndian(36 + dataSize)
        randomAccessFile.writeBytes("WAVE")
        randomAccessFile.writeBytes("fmt ")
        writeIntLittleEndian(16)
        writeShortLittleEndian(1) // PCM
        writeShortLittleEndian(channelCount)
        writeIntLittleEndian(sampleRate)
        writeIntLittleEndian(byteRate)
        writeShortLittleEndian(blockAlign)
        writeShortLittleEndian(16)
        randomAccessFile.writeBytes("data")
        writeIntLittleEndian(dataSize)
    }

    private fun writeIntLittleEndian(value: Int) {
        randomAccessFile.write(value and 0xFF)
        randomAccessFile.write((value ushr 8) and 0xFF)
        randomAccessFile.write((value ushr 16) and 0xFF)
        randomAccessFile.write((value ushr 24) and 0xFF)
    }

    private fun writeShortLittleEndian(value: Int) {
        randomAccessFile.write(value and 0xFF)
        randomAccessFile.write((value ushr 8) and 0xFF)
    }
}
