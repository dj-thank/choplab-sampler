package com.choplab.jvm

import com.choplab.core.VoiceTake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * An editor host's microphone: one take at a time, recorded by a [VoiceRecorder] into private scratch files under
 * [scratch] and stored in [assets] when it ends. [microphone] opens the platform input and returns null when there is
 * none; asking for permission stays with the host. The editor calls one method at a time; [close] may come from any
 * thread and also stops a take whose start raced it.
 */
class VoiceTakes(
    private val assets: FileAssetStore,
    private val scratch: Path,
    /** Free space the disk keeps for everything else; a take never records into it. */
    private val diskReserveBytes: Long = 64L shl 20,
    private val usableDiskBytes: (Path) -> Long = { it.toFile().usableSpace },
    private val microphone: () -> MicInput?,
) {
    /** How starting a take went. */
    enum class Start { STARTED, NO_ROOM, NO_INPUT }

    private val lock = Any()
    private var recorder: VoiceRecorder? = null
    private var closed = false

    init {
        // Scratch files a crash left behind hold nothing the document refers to; one that cannot go now goes later.
        try {
            if (Files.isDirectory(scratch)) Files.list(scratch).use { files ->
                files.filter { it.fileName.toString().startsWith("take-") }.forEach { try { Files.deleteIfExists(it) } catch (_: Exception) { } }
            }
        } catch (_: Exception) { }
    }

    /**
     * Opens the microphone for at most [maxSeconds], fewer when the asset store or the disk has room for less: a take
     * is written once, as a 32-bit mono WAV, and then moved into the store.
     */
    suspend fun start(maxSeconds: Int): Start = withContext(Dispatchers.IO) {
        require(maxSeconds > 0)
        synchronized(lock) {
            if (closed) return@withContext Start.NO_INPUT
            check(recorder == null) { "A take is already recording" }
        }
        val seconds = minOf(maxSeconds.toLong(), roomSeconds()).toInt()
        if (seconds < 1) return@withContext Start.NO_ROOM
        val input = try { microphone() } catch (_: Exception) { null } ?: return@withContext Start.NO_INPUT
        val created = try { VoiceRecorder(input, scratch, seconds) } catch (failure: Exception) {
            try { input.close() } catch (_: Exception) { }
            throw failure
        }
        val kept = synchronized(lock) { (!closed && recorder == null).also { if (it) recorder = created } }
        if (!kept) { created.discard(); return@withContext Start.NO_INPUT }
        Start.STARTED
    }

    /** The song started now. */
    fun cue() { synchronized(lock) { recorder }?.cue() }

    /** The running take reached its limit. */
    val full: Boolean get() = synchronized(lock) { recorder }?.full == true

    /** The running take stopped by itself: the input went away or it could not be written. */
    val interrupted: Boolean get() = synchronized(lock) { recorder }?.interrupted == true

    /** Ends the take and stores it as [name]; null when none ran or nothing but silence was recorded. */
    suspend fun stop(name: String): VoiceTake? = withContext(Dispatchers.IO + NonCancellable) {
        val current = synchronized(lock) { recorder.also { recorder = null } } ?: return@withContext null
        current.finish(assets, name)
    }

    /** Ends the take and drops it. */
    suspend fun discard() {
        withContext(Dispatchers.IO + NonCancellable) { synchronized(lock) { recorder.also { recorder = null } }?.discard() }
    }

    /** No take starts after this; one still running is dropped. */
    suspend fun close() {
        withContext(Dispatchers.IO + NonCancellable) { synchronized(lock) { closed = true; recorder.also { recorder = null } }?.discard() }
    }

    /** Whole seconds of 48 kHz mono float the store's quota and the disk still take. */
    private fun roomSeconds(): Long {
        val perSecond = 4L * 48_000
        val store = assets.maxStoredBytes - assets.storedBytes() - 44
        val disk = usableDiskBytes(Files.createDirectories(scratch)) - diskReserveBytes - 44
        return minOf(store, disk).coerceAtLeast(0) / perSecond
    }
}
