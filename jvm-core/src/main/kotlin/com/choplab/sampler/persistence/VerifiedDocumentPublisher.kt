package com.choplab.sampler.persistence

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class DocumentPublicationException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Copies one closed, app-owned source into an external document and then opens
 * that document again. Success means the read-back has the exact same byte
 * count and SHA-256 as the bytes sent to the destination output.
 *
 * This verifies publication; it does not make a generic document provider
 * atomic and cannot restore a destination that a provider already changed.
 */
fun publishVerifiedDocument(
    source: File,
    openDestinationOutput: () -> OutputStream?,
    openDestinationReadBack: () -> InputStream?,
) {
    if (!source.isFile) throw DocumentPublicationException("書き込むファイルが見つかりません")

    val sourceFingerprint = try {
        openDestinationOutput()
            ?.use { output ->
                source.inputStream().buffered().use { input ->
                    copyAndFingerprint(input, output)
                }
            }
            ?: throw DocumentPublicationException("保存先を開けません")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: DocumentPublicationException) {
        throw failure
    } catch (failure: Exception) {
        throw DocumentPublicationException("保存先へ書き込めません", failure)
    }

    val destinationFingerprint = try {
        openDestinationReadBack()
            ?.use(::fingerprint)
            ?: throw DocumentPublicationException("保存先を再読できません")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: DocumentPublicationException) {
        throw failure
    } catch (failure: Exception) {
        throw DocumentPublicationException("保存先を再読できません", failure)
    }

    if (
        sourceFingerprint.byteCount != destinationFingerprint.byteCount ||
        !MessageDigest.isEqual(sourceFingerprint.digest, destinationFingerprint.digest)
    ) {
        throw DocumentPublicationException("保存先の内容が書き込んだデータと一致しません")
    }
}

private fun copyAndFingerprint(input: InputStream, output: OutputStream): StreamFingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var byteCount = 0L
    while (true) {
        val count = input.read(buffer)
        when {
            count < 0 -> break
            count > 0 -> {
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                byteCount = Math.addExact(byteCount, count.toLong())
            }
            else -> {
                val single = input.read()
                if (single < 0) break
                output.write(single)
                digest.update(single.toByte())
                byteCount = Math.addExact(byteCount, 1L)
            }
        }
    }
    output.flush()
    return StreamFingerprint(byteCount, digest.digest())
}

private fun fingerprint(input: InputStream): StreamFingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var byteCount = 0L
    while (true) {
        val count = input.read(buffer)
        when {
            count < 0 -> break
            count > 0 -> {
                digest.update(buffer, 0, count)
                byteCount = Math.addExact(byteCount, count.toLong())
            }
            else -> {
                val single = input.read()
                if (single < 0) break
                digest.update(single.toByte())
                byteCount = Math.addExact(byteCount, 1L)
            }
        }
    }
    return StreamFingerprint(byteCount, digest.digest())
}

private data class StreamFingerprint(
    val byteCount: Long,
    val digest: ByteArray,
)

private const val COPY_BUFFER_BYTES = 64 * 1024
