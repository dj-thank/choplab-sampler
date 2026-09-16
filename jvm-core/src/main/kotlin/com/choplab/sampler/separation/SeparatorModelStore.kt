package com.choplab.sampler.separation

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * App-private copy of the pinned drum-separation model for platforms that do not ship it
 * inside the application image. The file is fetched once from the commit-pinned URL and is
 * used only after its exact size and SHA-256 match [SeparatorSpec].
 */
class SeparatorModelStore(
    private val directory: File,
    private val expectedSha256: String = SeparatorSpec.MODEL_SHA256,
    private val expectedBytes: Long = SeparatorSpec.MODEL_BYTES,
    private val open: (String) -> Download = ::openHttpDownload,
) {
    /** One HTTP body. [length] is -1 when the server does not declare it. */
    class Download(val length: Long, val stream: InputStream)

    val modelFile: File get() = directory.resolve(SeparatorSpec.MODEL_FILE)
    private val partialFile: File get() = directory.resolve(SeparatorSpec.MODEL_FILE + ".part")

    @Volatile private var verifiedLength = -1L
    @Volatile private var verifiedModified = -1L

    /** True only when the model is present with the expected size; hashing happens in [ensure]. */
    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() == expectedBytes

    /**
     * Returns a verified model file, downloading it first when missing or corrupt.
     * Blocks the calling worker. [onProgress] reports 0..1 of the download only.
     */
    @Synchronized
    fun ensure(onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): File {
        val target = modelFile
        if (target.isFile && target.length() == expectedBytes) {
            if (verifiedLength == target.length() && verifiedModified == target.lastModified()) return target
            if (sha256(target, isCancelled).equals(expectedSha256, ignoreCase = true)) {
                remember(target)
                return target
            }
            target.delete()
        }
        require(directory.isDirectory || directory.mkdirs()) { "分離モデルの保存先を作成できません" }
        val required = expectedBytes + SPACE_MARGIN_BYTES
        if (directory.usableSpace < required) {
            throw IllegalStateException("端末の空き容量が不足しています（分離モデルに約${required / (1024 * 1024)}MB必要）")
        }
        partialFile.delete()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            open(SeparatorSpec.MODEL_URL).stream.use { input ->
                partialFile.outputStream().buffered(BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastReported = -1
                    while (true) {
                        if (isCancelled() || Thread.currentThread().isInterrupted) {
                            throw CancellationException("model download cancelled")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        written += count
                        if (written > expectedBytes) throw IOException("分離モデルのサイズが想定と異なります")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = (written * 100 / expectedBytes).toInt()
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(written.toFloat() / expectedBytes)
                        }
                    }
                }
            }
            if (written != expectedBytes) throw IOException("分離モデルの取得が途中で終わりました")
            val actual = hex(digest.digest())
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw IOException("分離モデルの検証に失敗しました（SHA-256不一致）")
            }
            moveIntoPlace(partialFile, target)
            remember(target)
            return target
        } finally {
            partialFile.delete()
        }
    }

    private fun remember(file: File) {
        verifiedLength = file.length()
        verifiedModified = file.lastModified()
    }

    private fun moveIntoPlace(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File, isCancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                if (isCancelled()) throw CancellationException("model verification cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val output = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = digits[value ushr 4]
            output[index * 2 + 1] = digits[value and 0x0f]
        }
        return String(output)
    }

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

        /** HTTPS only; redirects stay on HTTPS (the pinned host redirects to its CDN). */
        fun openHttpDownload(url: String): Download {
            require(url.startsWith("https://")) { "分離モデルはHTTPSでのみ取得します" }
            var current = URL(url)
            repeat(MAX_REDIRECTS) {
                val connection = current.openConnection() as HttpURLConnection
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "ChopLab")
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    val next = URL(current, location ?: throw IOException("分離モデルの転送先がありません"))
                    if (next.protocol != "https") throw IOException("分離モデルの転送先がHTTPSではありません")
                    current = next
                    return@repeat
                }
                if (status !in 200..299) {
                    connection.disconnect()
                    throw IOException("分離モデルを取得できません（HTTP $status）")
                }
                return Download(connection.contentLengthLong, connection.inputStream)
            }
            throw IOException("分離モデルの転送が多すぎます")
        }

        private const val MAX_REDIRECTS = 5
    }
}
