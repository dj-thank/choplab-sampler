package com.choplab.sampler.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming PCM-16 WAV writer. The RIFF and data sizes are patched when [close] is called.
 *
 * Classic RIFF uses unsigned 32-bit chunk sizes. Writes that would exceed that boundary
 * are rejected before bytes reach the file; callers must use RF64 for larger recordings.
 */
class WavFileWriter(
    file: File,
    private val sampleRate: Int,
    private val channelCount: Int,
    maximumPcmBytes: Long = MAX_RIFF_PCM_BYTES,
) : Closeable {
    private val randomAccessFile = RandomAccessFile(file, "rw")
    private val blockAlign = channelCount * Short.SIZE_BYTES
    private val maximumPcmBytes = maximumPcmBytes.alignedDown(blockAlign)
    private var pcmBytesWritten = 0L
    private var closed = false

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount in 1..2) { "Only mono and stereo are supported" }
        require(maximumPcmBytes in blockAlign.toLong()..MAX_RIFF_PCM_BYTES) {
            "maximumPcmBytes must fit a classic RIFF/WAV file"
        }
        randomAccessFile.setLength(0L)
        writeHeader(dataSize = 0L)
    }

    @Synchronized
    fun writePcm16(samples: ShortArray, sampleCount: Int = samples.size) {
        check(!closed) { "WAV writer is already closed" }
        require(sampleCount in 0..samples.size)
        require(sampleCount % channelCount == 0) { "PCM samples must contain complete frames" }
        val byteCountLong = sampleCount.toLong() * Short.SIZE_BYTES
        require(byteCountLong <= Int.MAX_VALUE) { "PCM write is too large" }
        ensureWriteFits(byteCountLong)

        val bytes = ByteBuffer.allocate(byteCountLong.toInt())
            .order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until sampleCount) {
            bytes.putShort(samples[index])
        }
        val array = bytes.array()
        randomAccessFile.write(array)
        pcmBytesWritten += array.size.toLong()
    }

    @Synchronized
    fun writePcm16Bytes(bytes: ByteArray, byteCount: Int = bytes.size) {
        check(!closed) { "WAV writer is already closed" }
        require(byteCount in 0..bytes.size)
        require(byteCount % blockAlign == 0) { "PCM bytes must contain complete frames" }
        ensureWriteFits(byteCount.toLong())
        randomAccessFile.write(bytes, 0, byteCount)
        pcmBytesWritten += byteCount.toLong()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true

        try {
            randomAccessFile.seek(4L)
            writeUnsignedIntLittleEndian(RIFF_HEADER_REMAINDER_BYTES + pcmBytesWritten)
            randomAccessFile.seek(40L)
            writeUnsignedIntLittleEndian(pcmBytesWritten)
            randomAccessFile.fd.sync()
        } finally {
            randomAccessFile.close()
        }
    }

    private fun ensureWriteFits(incomingBytes: Long) {
        require(incomingBytes >= 0L) { "incomingBytes must not be negative" }
        if (incomingBytes > maximumPcmBytes - pcmBytesWritten) {
            error(
                "PCM data exceeds the classic RIFF/WAV limit. " +
                    "Use a shorter recording or an RF64 writer.",
            )
        }
    }

    private fun writeHeader(dataSize: Long) {
        val byteRate = sampleRate.toLong() * channelCount * Short.SIZE_BYTES
        val blockAlign = channelCount * Short.SIZE_BYTES
        require(byteRate <= MAX_UNSIGNED_INT) { "WAV byte rate is too large" }

        randomAccessFile.writeBytes("RIFF")
        writeUnsignedIntLittleEndian(RIFF_HEADER_REMAINDER_BYTES + dataSize)
        randomAccessFile.writeBytes("WAVE")
        randomAccessFile.writeBytes("fmt ")
        writeUnsignedIntLittleEndian(16L)
        writeShortLittleEndian(1) // PCM
        writeShortLittleEndian(channelCount)
        writeUnsignedIntLittleEndian(sampleRate.toLong())
        writeUnsignedIntLittleEndian(byteRate)
        writeShortLittleEndian(blockAlign)
        writeShortLittleEndian(16)
        randomAccessFile.writeBytes("data")
        writeUnsignedIntLittleEndian(dataSize)
    }

    private fun writeUnsignedIntLittleEndian(value: Long) {
        require(value in 0L..MAX_UNSIGNED_INT) { "WAV chunk size exceeds unsigned 32-bit range" }
        randomAccessFile.write((value and 0xFF).toInt())
        randomAccessFile.write((value ushr 8 and 0xFF).toInt())
        randomAccessFile.write((value ushr 16 and 0xFF).toInt())
        randomAccessFile.write((value ushr 24 and 0xFF).toInt())
    }

    private fun writeShortLittleEndian(value: Int) {
        randomAccessFile.write(value and 0xFF)
        randomAccessFile.write((value ushr 8) and 0xFF)
    }

    private fun Long.alignedDown(alignment: Int): Long = this - this % alignment

    companion object {
        private const val MAX_UNSIGNED_INT = 0xFFFF_FFFFL
        private const val RIFF_HEADER_REMAINDER_BYTES = 36L

        /** Maximum PCM payload whose RIFF chunk size (36 + payload) still fits uint32. */
        const val MAX_RIFF_PCM_BYTES = MAX_UNSIGNED_INT - RIFF_HEADER_REMAINDER_BYTES
    }
}
