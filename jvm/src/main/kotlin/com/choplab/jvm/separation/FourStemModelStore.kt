package com.choplab.jvm.separation

import com.choplab.core.separation.SeparationProblem
import com.choplab.jvm.hex
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID

/** Explicit first-download/repair. A corrupt previous cache is retained until a verified replacement is complete. */
class FourStemModelStore(private val directory: Path,
                         private val download: (String) -> ModelDownload = ::httpsDownload) {
    class ModelDownload(val bytes: Long, val input: InputStream, private val release: () -> Unit = {}) : Closeable {
        override fun close() { try { input.close() } finally { release() } }
    }
    val model: Path get() = directory.resolve(FourStemSpec.MODEL_FILE)
    @Synchronized fun ensure(allowDownload: Boolean, progress: (Long, Long) -> Unit = { _, _ -> }, check: () -> Unit = {}): Path {
        check()
        if (Files.isRegularFile(model, LinkOption.NOFOLLOW_LINKS)) {
            try { verify(model, check); return model } catch (invalid: SeparationException) {
                if (!allowDownload) throw invalid
            }
        } else if (!allowDownload) throw SeparationException(SeparationProblem.MODEL_MISSING)
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory))
        if (directory.toFile().usableSpace < FourStemSpec.MODEL_BYTES + 64L * 1024 * 1024)
            throw SeparationException(SeparationProblem.NO_SPACE)
        val pending = directory.resolve(".model-${UUID.randomUUID()}.part")
        try {
            download(FourStemSpec.MODEL_URL).use { response ->
                if (response.bytes >= 0 && response.bytes != FourStemSpec.MODEL_BYTES) throw SeparationException(SeparationProblem.MODEL_INVALID)
                Files.newOutputStream(pending, StandardOpenOption.CREATE_NEW).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        check()
                        val next = response.input.read(buffer)
                        if (next < 0) break
                        if (next == 0) throw SeparationException(SeparationProblem.DOWNLOAD_FAILED)
                        count += next
                        if (count > FourStemSpec.MODEL_BYTES) throw SeparationException(SeparationProblem.MODEL_INVALID)
                        output.write(buffer, 0, next); progress(count, FourStemSpec.MODEL_BYTES)
                    }
                }
            }
            verify(pending, check); check()
            Files.move(pending, model, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return model
        } finally { Files.deleteIfExists(pending) }
    }
    companion object {
        fun verify(path: Path, check: () -> Unit = {}) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != FourStemSpec.MODEL_BYTES)
                throw SeparationException(SeparationProblem.MODEL_INVALID)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) { check(); val count = input.read(bytes); if (count < 0) break; require(count > 0); digest.update(bytes, 0, count) }
            }
            if (digest.digest().hex() != FourStemSpec.MODEL_SHA256) throw SeparationException(SeparationProblem.MODEL_INVALID)
        }
        private fun httpsDownload(value: String): ModelDownload {
            var uri = URI(value)
            repeat(6) {
                require(uri.scheme == "https")
                val connection = uri.toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false; connection.connectTimeout = 20_000; connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "ChopLab")
                try {
                    val status = connection.responseCode
                    if (status in 300..399) {
                        uri = uri.resolve(connection.getHeaderField("Location") ?: throw SeparationException(SeparationProblem.DOWNLOAD_FAILED))
                        connection.disconnect()
                    } else {
                        if (status != 200) throw SeparationException(SeparationProblem.DOWNLOAD_FAILED)
                        return ModelDownload(connection.contentLengthLong, connection.inputStream, connection::disconnect)
                    }
                } catch (failure: Throwable) { connection.disconnect(); throw failure }
            }
            throw SeparationException(SeparationProblem.DOWNLOAD_FAILED)
        }
    }
}
