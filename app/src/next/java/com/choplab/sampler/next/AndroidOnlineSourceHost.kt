package com.choplab.sampler.next

import android.content.Context
import android.net.Uri
import com.choplab.core.model.Asset
import com.choplab.core.model.ProjectLimits
import com.choplab.jvm.WavCodec
import com.choplab.ui.source.*
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Uses the original-byte import supplied by the Android host; no lossy intermediate enters the library. */
internal class AndroidOnlineSourceHost(private val context: Context, private val documents: AndroidDocuments) : OnlineSourceHost {
    override fun open(scope: CoroutineScope, stopAll: () -> Unit): OnlineImportSession {
        val port = AndroidOnlineSourcePort(File(context.filesDir, "audio-library").toPath(), ::validate, scope, stopAll)
        return OnlineImportSession(port) { id -> port.saved(id)?.let {
            // Stream import needs the real container suffix even when a provider title itself ends in another suffix.
            val name = it.title.take(240) + "." + it.path.toFile().extension
            OnlineImportSelection(documents.openedNamed(Uri.fromFile(it.path.toFile()), name, it.hash), it.hash)
        } }
    }

    /** Runs on the owned import worker. The native decoder's float scratch is always released, including cancellation. */
    private fun validate(file: File) {
        require(file.isFile && file.length() in 1..ProjectLimits.MAX_ASSET_BYTES && file.extension.lowercase() in Asset.EXTENSIONS)
        if (file.extension.equals("wav", true)) { file.inputStream().use { WavCodec.inspect(it) }; return }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= ProjectLimits.MAX_ASSET_BYTES)
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val scratch = Files.createTempDirectory(context.cacheDir.toPath(), "online-validation-")
        try { AndroidOriginalAudioDecoder(scratch).use { it.inspect(file.toPath(), hash) { Thread.currentThread().isInterrupted } } }
        finally { scratch.toFile().deleteRecursively() }
    }
}
