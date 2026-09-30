package com.choplab.jvm

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.*

class LrcExportSafetyTest {
    @Test fun preflightValidationUsesTheSameStrictBoundariesAsWrite() {
        LrcTextIO.validate("")
        LrcTextIO.validate("[00:01.000]音を拾う 🎵\n")
        assertFailsWith<CharacterCodingException> { LrcTextIO.validate("\ud800") }
        assertFailsWith<IllegalArgumentException> {
            LrcTextIO.validate("a".repeat(LrcTextIO.MAX_CHARACTERS + 1))
        }
    }

    @Test fun malformedSurrogatesNeverOpenTheDestination() {
        for (text in listOf("\ud800", "\udc00", "line\ud800end", "\udc00\ud800", "\ud800\ud800")) {
            var opens = 0
            assertFailsWith<CharacterCodingException> {
                LrcTextIO.write(text) { opens++; ByteArrayOutputStream() }
            }
            assertEquals(0, opens)
        }
    }

    @Test fun oversizedTextNeverOpensTheDestination() {
        var opens = 0
        assertFailsWith<IllegalArgumentException> {
            LrcTextIO.write("a".repeat(LrcTextIO.MAX_CHARACTERS + 1)) { opens++; ByteArrayOutputStream() }
        }
        assertEquals(0, opens)
    }

    @Test fun invalidTextPreservesAnExistingFile() {
        val file = Files.createTempFile("choplab-lrc-preserve-", ".lrc")
        val existing = "[00:01.000]既存の歌詞\n".toByteArray(Charsets.UTF_8)
        try {
            Files.write(file, existing)
            assertFailsWith<CharacterCodingException> {
                LrcTextIO.write("\ud800") { Files.newOutputStream(file) }
            }
            assertContentEquals(existing, Files.readAllBytes(file))
            assertFailsWith<IllegalArgumentException> {
                LrcTextIO.write("a".repeat(LrcTextIO.MAX_CHARACTERS + 1)) { Files.newOutputStream(file) }
            }
            assertContentEquals(existing, Files.readAllBytes(file))
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun invalidTextDoesNotCreateANewFile() {
        val directory = Files.createTempDirectory("choplab-lrc-new-")
        val file = directory.resolve("lyrics.lrc")
        try {
            assertFailsWith<CharacterCodingException> {
                LrcTextIO.write("\udc00") { Files.newOutputStream(file) }
            }
            assertFalse(Files.exists(file))
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory) }
    }

    @Test fun validUtf8OpensOnceAndClosesTheStream() {
        val text = "\uFEFF[00:01.000]音を拾う 🎵\r\n"
        val output = TrackingOutput()
        var opens = 0
        LrcTextIO.write(text) { opens++; output }
        assertEquals(1, opens)
        assertEquals(1, output.closes)
        assertContentEquals(text.toByteArray(Charsets.UTF_8), output.bytes.toByteArray())
    }

    @Test fun exactCharacterLimitAcceptsMultibyteText() {
        val text = "音".repeat(LrcTextIO.MAX_CHARACTERS)
        val output = TrackingOutput()
        LrcTextIO.write(text) { output }
        assertEquals(3 * LrcTextIO.MAX_CHARACTERS, output.bytes.size())
        assertEquals(1, output.closes)
    }

    @Test fun supplementaryCharactersAtTheLimitRemainValid() {
        val text = "🎵".repeat(LrcTextIO.MAX_CHARACTERS / 2)
        val output = TrackingOutput()
        LrcTextIO.write(text) { output }
        assertEquals(text, output.bytes.toString("UTF-8"))
        assertEquals(1, output.closes)
    }

    @Test fun emptyTextStillOpensAndClosesTheDestination() {
        val output = TrackingOutput()
        var opens = 0
        LrcTextIO.write("") { opens++; output }
        assertEquals(1, opens)
        assertEquals(0, output.bytes.size())
        assertEquals(1, output.closes)
    }

    @Test fun openerFailureIsPropagatedWithoutRetry() {
        val failure = IOException("provider open failed")
        var opens = 0
        val thrown = assertFailsWith<IOException> {
            LrcTextIO.write("lyrics") { opens++; throw failure }
        }
        assertSame(failure, thrown)
        assertEquals(1, opens)
    }

    @Test fun cancellationBeforeOpeningIsNotConvertedToSuccess() {
        val cancellation = CancellationException("cancelled before opening")
        val thrown = assertFailsWith<CancellationException> {
            LrcTextIO.write("lyrics") { throw cancellation }
        }
        assertSame(cancellation, thrown)
    }

    @Test fun writeFailureClosesTheStreamAndPreservesTheFailure() {
        val failure = IOException("provider write failed")
        val output = TrackingOutput(writeFailure = failure)
        val thrown = assertFailsWith<IOException> { LrcTextIO.write("lyrics") { output } }
        assertSame(failure, thrown)
        assertEquals(1, output.closes)
    }

    @Test fun cancellationWhileWritingClosesTheStream() {
        val cancellation = CancellationException("cancelled while writing")
        val output = TrackingOutput(writeFailure = cancellation)
        val thrown = assertFailsWith<CancellationException> { LrcTextIO.write("lyrics") { output } }
        assertSame(cancellation, thrown)
        assertEquals(1, output.closes)
    }

    @Test fun closeFailureDoesNotReportSuccess() {
        val failure = IOException("provider close failed")
        val output = TrackingOutput(closeFailure = failure)
        val thrown = assertFailsWith<IOException> { LrcTextIO.write("lyrics") { output } }
        assertSame(failure, thrown)
        assertEquals(1, output.closes)
    }

    @Test fun writeFailureRemainsPrimaryWhenCloseAlsoFails() {
        val writeFailure = IOException("provider write failed")
        val closeFailure = IOException("provider close failed")
        val output = TrackingOutput(writeFailure, closeFailure)
        val thrown = assertFailsWith<IOException> { LrcTextIO.write("lyrics") { output } }
        assertSame(writeFailure, thrown)
        assertSame(closeFailure, thrown.suppressed.single())
        assertEquals(1, output.closes)
    }

    @Test fun callerOwnedStreamRemainsOpenForTheExistingOverload() {
        val output = TrackingOutput()
        LrcTextIO.write(output, "lyrics")
        assertEquals("lyrics", output.bytes.toString("UTF-8"))
        assertEquals(0, output.closes)
    }

    @Test fun existingOverloadRejectsInvalidTextWithoutWritingOrClosing() {
        val output = TrackingOutput()
        assertFailsWith<CharacterCodingException> { LrcTextIO.write(output, "\ud800") }
        assertEquals(0, output.bytes.size())
        assertEquals(0, output.closes)
    }

    private class TrackingOutput(
        private val writeFailure: Exception? = null,
        private val closeFailure: Exception? = null,
    ) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var closes = 0
            private set

        override fun write(value: Int) {
            writeFailure?.let { throw it }
            bytes.write(value)
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            writeFailure?.let { throw it }
            bytes.write(buffer, offset, length)
        }
        override fun close() {
            closes++
            closeFailure?.let { throw it }
        }
    }
}
