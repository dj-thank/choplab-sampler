package com.choplab.jvm

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Shared Android/Desktop text boundary. Rejects oversized or malformed UTF-8 instead of replacing lyric text. */
object LrcTextIO {
    const val MAX_CHARACTERS = 1_048_576
    const val MAX_BYTES = MAX_CHARACTERS * 4
    fun read(input: InputStream): String {
        val bytes = readBounded(input, MAX_BYTES.toLong())
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(text.length <= MAX_CHARACTERS) { "LRC exceeds character limit" }
        return text
    }
    /** Writes to a caller-owned stream; the caller remains responsible for closing it. */
    fun write(output: OutputStream, text: String) {
        output.write(encode(text))
    }

    /**
     * Validates and encodes the complete text before opening a possibly destructive destination.
     * Owns and closes the returned stream, including when writing fails. An I/O failure after
     * opening can still leave partial output; this does not provide atomic provider publication.
     */
    fun write(text: String, openOutput: () -> OutputStream) {
        val bytes = encode(text)
        openOutput().use { it.write(bytes) }
    }

    private fun encode(text: String): ByteArray {
        require(text.length <= MAX_CHARACTERS) { "LRC exceeds character limit" }
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        val bytes = ByteArray(encoded.remaining()).also(encoded::get)
        require(bytes.size <= MAX_BYTES) { "LRC exceeds byte limit" }
        return bytes
    }
}
