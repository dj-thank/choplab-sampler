package com.choplab.sampler.source

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Reads observed text tags only, with a fixed allocation/scan budget. Audio is never decoded or rewritten. */
object LocalAudioTags {
    const val MAX_TAG_BYTES = 1024 * 1024
    enum class Problem { MALFORMED, TOO_LARGE }
    data class Result(val title: String? = null, val metadata: AudioLibraryMetadata = AudioLibraryMetadata(), val problem: Problem? = null)
    private class Oversized : Exception()

    fun read(file: File, checkCancelled: () -> Unit = {}): Result {
        if (file.extension.lowercase() !in setOf("mp3", "flac")) return Result()
        return try {
            RandomAccessFile(file, "r").use { input ->
                checkCancelled()
                if (file.extension.equals("flac", true)) flac(input, checkCancelled) else id3(input, checkCancelled)
            }
        } catch (error: InterruptedException) { throw error }
        catch (_: Oversized) { Result(problem = Problem.TOO_LARGE) }
        catch (_: Exception) { checkCancelled(); Result(problem = Problem.MALFORMED) }
    }

    private fun result(tags: Map<String, String>): Result {
        fun text(key: String) = tags[key]?.filter { it.code >= 32 && it.code != 127 }?.trim()?.take(240)?.takeIf(String::isNotBlank)
        fun number(key: String) = text(key)?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it in 1..9999 }
        return Result(text("TITLE"), AudioLibraryMetadata(text("ARTIST").orEmpty(), text("ALBUM").orEmpty(), number("TRACKNUMBER"), number("DISCNUMBER")))
    }

    private fun flac(input: RandomAccessFile, check: () -> Unit): Result {
        if (input.length() < 4) return Result()
        val magic = ByteArray(4).also(input::readFully)
        if (!magic.contentEquals("fLaC".toByteArray())) return Result(problem = Problem.MALFORMED)
        var scanned = 0
        val tags = linkedMapOf<String, String>()
        repeat(128) {
            check()
            val header = input.readUnsignedByte()
            val size = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            scanned += 4 + size
            if (scanned > MAX_TAG_BYTES) throw Oversized()
            require(input.filePointer + size <= input.length())
            if (header and 127 == 4) {
                val buffer = ByteBuffer.wrap(ByteArray(size).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN)
                fun bytes(): ByteArray {
                    val n = buffer.int
                    require(n >= 0 && n <= buffer.remaining())
                    return ByteArray(n).also(buffer::get)
                }
                bytes() // vendor is deliberately not retained
                val count = buffer.int.also { require(it in 0..4096) }
                repeat(count) {
                    check()
                    val entry = decode(bytes(), Charsets.UTF_8)
                    val key = entry.substringBefore('=').uppercase()
                    if ('=' in entry && key in setOf("TITLE", "ARTIST", "ALBUM", "TRACKNUMBER", "DISCNUMBER"))
                        tags.putIfAbsent(key, entry.substringAfter('=').take(240))
                }
            } else input.seek(input.filePointer + size)
            if (header and 128 != 0) return result(tags)
        }
        throw Oversized()
    }

    private fun id3(input: RandomAccessFile, check: () -> Unit): Result {
        if (input.length() < 10) return Result()
        val header = ByteArray(10).also(input::readFully)
        if (!header.copyOfRange(0, 3).contentEquals("ID3".toByteArray())) {
            if (input.length() < 128) return Result()
            input.seek(input.length() - 128)
            val v1 = ByteArray(128).also(input::readFully)
            if (!v1.copyOfRange(0, 3).contentEquals("TAG".toByteArray())) return Result()
            fun text(a: Int, b: Int) = String(v1, a, b - a, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
            return result(mapOf("TITLE" to text(3, 33), "ARTIST" to text(33, 63), "ALBUM" to text(63, 93),
                "TRACKNUMBER" to if (v1[125] == 0.toByte()) (v1[126].toInt() and 255).toString() else ""))
        }
        val version = header[3].toInt()
        require(version in 2..4)
        val size = syncSafe(header, 6)
        if (size > MAX_TAG_BYTES) throw Oversized()
        require(size <= input.length() - 10)
        // Unsupported header/frame transforms fall back explicitly instead of interpreting compressed bytes as tags.
        require(header[5].toInt() and 0xe0 == 0)
        val bytes = ByteArray(size).also(input::readFully)
        val tags = linkedMapOf<String, String>()
        val keys = mapOf("TIT2" to "TITLE", "TT2" to "TITLE", "TPE1" to "ARTIST", "TP1" to "ARTIST",
            "TALB" to "ALBUM", "TAL" to "ALBUM", "TRCK" to "TRACKNUMBER", "TRK" to "TRACKNUMBER",
            "TPOS" to "DISCNUMBER", "TPA" to "DISCNUMBER")
        var pos = 0; var frames = 0
        val headerSize = if (version == 2) 6 else 10
        while (pos + headerSize <= bytes.size && bytes[pos] != 0.toByte()) {
            check()
            if (++frames > 4096) throw Oversized()
            val name = String(bytes, pos, if (version == 2) 3 else 4, Charsets.ISO_8859_1)
            require(name.all { it in 'A'..'Z' || it in '0'..'9' })
            val length = if (version == 2) ((bytes[pos + 3].toInt() and 255) shl 16) or
                ((bytes[pos + 4].toInt() and 255) shl 8) or (bytes[pos + 5].toInt() and 255)
                else if (version == 4) syncSafe(bytes, pos + 4) else ByteBuffer.wrap(bytes, pos + 4, 4).int
            require(length > 0 && length <= bytes.size - pos - headerSize)
            if (version != 2) require(bytes[pos + 9] == 0.toByte())
            keys[name]?.let { key ->
                val start = pos + headerSize
                val encoding = bytes[start].toInt()
                val charset = when (encoding) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> error("Invalid tag encoding") }
                tags.putIfAbsent(key, decode(bytes.copyOfRange(start + 1, start + length), charset).substringBefore('\u0000').take(240))
            }
            pos += headerSize + length
        }
        return result(tags)
    }
    private fun syncSafe(bytes: ByteArray, offset: Int): Int {
        var value = 0
        repeat(4) { val byte = bytes[offset + it].toInt() and 255; require(byte < 128); value = (value shl 7) or byte }
        return value
    }
    private fun decode(bytes: ByteArray, charset: java.nio.charset.Charset) = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}
