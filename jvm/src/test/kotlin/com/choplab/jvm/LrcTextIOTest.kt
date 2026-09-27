package com.choplab.jvm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import kotlin.test.*

class LrcTextIOTest {
    @Test fun boundedUtf8PreservesJapaneseAndRejectsDamagedOrOversizedText() {
        val text = "[00:01.000]音を拾う 🎵\n"
        val output = ByteArrayOutputStream()
        LrcTextIO.write(output, text)
        assertEquals(text, LrcTextIO.read(ByteArrayInputStream(output.toByteArray())))
        assertFailsWith<CharacterCodingException> { LrcTextIO.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertFailsWith<CharacterCodingException> { LrcTextIO.write(ByteArrayOutputStream(), "\ud800") }
        assertFailsWith<IllegalArgumentException> { LrcTextIO.read(ByteArrayInputStream(ByteArray(LrcTextIO.MAX_BYTES + 1))) }
        assertFailsWith<IllegalArgumentException> { LrcTextIO.write(ByteArrayOutputStream(), "a".repeat(LrcTextIO.MAX_CHARACTERS + 1)) }
    }
}
