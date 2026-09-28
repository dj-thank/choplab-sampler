package com.choplab.jvm

import com.choplab.core.model.ProjectLimits
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest

/** A saved receipt binds the bytes actually copied, not an earlier inspection of a mutable file. */
class VerifiedLibraryInput(private val source: InputStream, private val expectedHash: String) : InputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var count = 0L
    private var complete = false
    init { require(expectedHash.matches(Regex("[0-9a-f]{64}"))) }

    override fun read(): Int = source.read().also { value ->
        if (value < 0) verify() else { bounded(1); digest.update(value.toByte()) }
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = source.read(bytes, offset, length).also { read ->
        if (read < 0) verify() else if (read > 0) { bounded(read); digest.update(bytes, offset, read) }
    }
    override fun read(bytes: ByteArray): Int = read(bytes, 0, bytes.size)
    // InputStream's default skip reads through us, so skipped bytes also enter the receipt digest.
    override fun close() = source.close()
    private fun bounded(size: Int) {
        count += size
        if (count > ProjectLimits.MAX_ASSET_BYTES) throw IOException("Library audio exceeds the byte limit")
    }
    private fun verify() {
        if (complete) return
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (count == 0L || actual != expectedHash) throw IOException("Saved library audio changed")
        complete = true
    }
}
