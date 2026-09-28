package com.choplab.jvm

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.*

class VerifiedLibraryInputTest {
    @Test fun verifiesTheCopiedBytesIncludingSingleReadsSkipsAndRepeatedEof() {
        val original = ByteArray(200_000) { (it * 19).toByte() }
        VerifiedLibraryInput(ByteArrayInputStream(original), sha256(original)).use {
            assertEquals(original[0].toInt() and 255, it.read())
            assertEquals(17L, it.skip(17))
            assertContentEquals(original.copyOfRange(18, original.size), it.readBytes())
            assertEquals(-1, it.read()); assertEquals(-1, it.read())
        }
    }
    @Test fun aChangedOrTruncatedSavedFileFailsBeforeTheImporterCanAdoptItsScratchCopy() {
        val original = ByteArray(80_000) { 12 }
        for (changed in listOf(original.copyOf(40_000), original.copyOf().also { it[70_000] = 13 }, byteArrayOf())) {
            assertFailsWith<IOException> { VerifiedLibraryInput(ByteArrayInputStream(changed), sha256(original)).use { it.readBytes() } }
        }
    }
}
