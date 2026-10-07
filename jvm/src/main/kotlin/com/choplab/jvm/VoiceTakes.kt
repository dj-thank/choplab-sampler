package com.choplab.jvm

import com.choplab.core.VoiceTake
import com.choplab.core.vocal.VocalCapturedSession
import com.choplab.core.vocal.VocalCapturedPass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val captureChannels: Int = 1,
    private val nanoTime: () -> Long = System::nanoTime,
    private val memory: PcmMemoryBudget = PcmMemoryBudget.shared,
    /** The host acknowledges publication only after its document edit commits. */
    private val durableTakes: Boolean = false,
    private val microphone: suspend () -> MicInput?,
) {
    /** How starting a take went. */
    enum class Start { STARTED, NO_ROOM, NO_INPUT }

    private val lock = Any()
    private var recorder: VoiceRecorder? = null
    private var retiring: VoiceRecorder? = null
    private var pending: VoiceRecorder? = null
    private val recovered = ArrayDeque<Path>()
    private val opener = CancellableInputOpener(microphone)
    private var closed = false
    private var openingEpoch = 0L

    init {
        require(captureChannels in 1..2)
        // Durable hosts offer crash remnants for explicit recovery/discard; never delete an unaccepted original.
        try {
            if (Files.isDirectory(scratch)) Files.list(scratch).use { files ->
                files.filter { it.fileName.toString().let { name -> name.startsWith("take-") || name.startsWith("pending-take-") } }
                    .sorted().forEach {
                        if (durableTakes) recovered.addLast(it)
                        else if (!it.fileName.toString().startsWith("pending-take-")) try { Files.deleteIfExists(it) } catch (_: Exception) { }
                    }
            }
        } catch (_: Exception) { }
    }

    /**
     * Opens the microphone for at most [maxSeconds], fewer when the asset store or the disk has room for less: a take
     * is written once, as a 32-bit WAV, and then moved into the store.
     */
    suspend fun start(maxSeconds: Int, waitForCue: Boolean = false, window: VoiceCaptureWindow? = null, passes: Int = 1): Start {
        var owned: VoiceRecorder? = null
        var published = false
        val epoch = synchronized(lock) { openingEpoch }
        return try { withContext(Dispatchers.IO) {
        require(maxSeconds in 1..300)
        require(passes in 1..8 && (passes == 1 || window != null))
        require(window == null || (waitForCue && window.frames48k * passes <= maxSeconds * 48_000L))
        synchronized(lock) {
            if (closed) return@withContext Start.NO_INPUT
            if (retiring?.terminated == true) retiring = null
            if (retiring != null) return@withContext Start.NO_INPUT
            check(recorder == null) { "A take is already recording" }
            if (pending != null || recovered.isNotEmpty() || opener.busy) return@withContext Start.NO_INPUT
        }
        val seconds = minOf(maxSeconds.toLong(), roomSeconds()).toInt()
        if (seconds < 1 || (window != null && window.frames48k * passes > seconds * 48_000L)) return@withContext Start.NO_ROOM
        val reserved = try { memory.reserve(VoiceRecorder.MEMORY_BYTES) } catch (_: PcmMemoryLimit) { return@withContext Start.NO_ROOM }
        var transferred = false
        try {
            val input = try { opener.open() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: PcmMemoryLimit) { return@withContext Start.NO_ROOM }
                catch (_: Exception) { null } ?: return@withContext Start.NO_INPUT
            val created = try {
                currentCoroutineContext().ensureActive()
                require(input.channels == captureChannels && input.sampleRate in 8_000..48_000)
                VoiceRecorder(input, scratch, seconds, waitForCue, nanoTime, window, reserved, passes).also { owned = it; transferred = true }
            } catch (failure: Exception) {
                try { input.close() } catch (_: Exception) { }
                throw failure
            }
            currentCoroutineContext().ensureActive()
            val kept = synchronized(lock) { (!closed && epoch == openingEpoch && recorder == null && retiring == null).also { if (it) recorder = created } }
            published = kept
            if (!kept) { owned = null; created.discard(); return@withContext Start.NO_INPUT }
            Start.STARTED
        } finally { if (!transferred) reserved.close() }
        } } catch (cancel: CancellationException) {
            // Permission/open can finish after cancellation. Release only the input this start still owns.
            withContext(Dispatchers.IO + NonCancellable) {
                owned?.let { created ->
                    val release = synchronized(lock) {
                        (if (recorder === created) { recorder = null; true } else !published)
                            .also { if (it) retiring = created }
                    }
                    if (release) try { created.discard() }
                    finally { synchronized(lock) { if (created.terminated && retiring === created) retiring = null } }
                }
            }
            throw cancel
        }
    }

    /** The song started now. */
    fun cue() { synchronized(lock) { recorder }?.cue() }
    fun cueAt(atNanos: Long): Boolean = synchronized(lock) { recorder }?.cueAt(atNanos) == true
    val armingTimedOut: Boolean get() = synchronized(lock) { recorder }?.armingTimedOut == true
    val completedPasses: Int get() = synchronized(lock) { recorder }?.completedPasses ?: 0
    val inputRouteRevision: Long? get() = synchronized(lock) { recorder }?.inputRouteRevision
    val inputRate: Int? get() = synchronized(lock) { recorder }?.inputRate
    val inputBufferFrames: Int? get() = synchronized(lock) { recorder }?.inputBufferFrames
    val windowComplete: Boolean get() = synchronized(lock) { recorder }?.windowComplete == true

    /** The running take reached its limit. */
    val full: Boolean get() = synchronized(lock) { recorder }?.full == true
    val recordedMillis: Long get() = synchronized(lock) { recorder ?: pending }?.recordedMillis ?: 0
    val limitMillis: Long? get() = synchronized(lock) { recorder ?: pending }?.limitMillis
    val peakLevel: Float? get() = synchronized(lock) { recorder }?.peakLevel
    val interruption: InputInterruption? get() = synchronized(lock) { recorder ?: pending }?.interruption
    val pendingSave: Boolean get() = synchronized(lock) { pending != null || recovered.isNotEmpty() }
    val inputBusy: Boolean get() = synchronized(lock) { recorder != null || pending != null || retiring != null } || opener.busy
    fun cancelOpening() { synchronized(lock) { openingEpoch++ }; opener.cancel() }

    /** The running take stopped by itself: the input went away or it could not be written. */
    val interrupted: Boolean get() = synchronized(lock) { recorder }?.interrupted == true

    /** Ends the take and stores it as [name]; null when none ran or nothing but silence was recorded. */
    suspend fun stop(name: String): VoiceTake? = withContext(Dispatchers.IO + NonCancellable) {
        finish(name).first
    }

    private fun finish(name: String): Pair<VoiceTake?, List<VocalCapturedPass>> {
        val current = synchronized(lock) {
            (pending ?: recorder).also { recorder = null; if (it != null) { retiring = it; if (durableTakes) pending = it } }
        }
        if (current == null) {
            val file = synchronized(lock) { recovered.firstOrNull() } ?: return null to emptyList()
            return com.choplab.core.VoiceTake(TakeFile.Pending.recover(file).publish(assets, name), 0) to emptyList()
        }
        try {
            val take = current.finish(assets, name, retain = durableTakes)
            if (take == null) synchronized(lock) { if (pending === current) pending = null }
            return take to current.capturedPasses
        }
        finally { synchronized(lock) { if (current.terminated && retiring === current) retiring = null } }
    }

    /** Publication does not own the document. Its acknowledged edit releases only this recovery copy. */
    suspend fun acknowledge() = withContext(Dispatchers.IO + NonCancellable) {
        val current = synchronized(lock) { pending }
        if (current != null) {
            current.acknowledge()
            synchronized(lock) { if (pending === current) pending = null }
        } else {
            val file = synchronized(lock) { recovered.firstOrNull() }
            if (file != null) { Files.deleteIfExists(file); synchronized(lock) { recovered.remove(file) } }
        }
    }

    /** Ends one multi-pass input session; all candidate ranges refer to the same immutable original WAV. */
    suspend fun stopPunch(name: String): VocalCapturedSession? = withContext(Dispatchers.IO + NonCancellable) {
        val (take, passes) = finish(name)
        take?.let { saved -> passes.takeIf { it.isNotEmpty() }?.let { VocalCapturedSession(saved.asset, it) } }
    }

    /** Ends the take and drops it. */
    suspend fun discard() {
        cancelOpening()
        withContext(Dispatchers.IO + NonCancellable) {
            val current = synchronized(lock) { (pending ?: recorder).also { pending = null; recorder = null; if (it != null) retiring = it } }
            if (current == null) {
                val file = synchronized(lock) { recovered.firstOrNull() }
                if (file != null) { Files.deleteIfExists(file); synchronized(lock) { recovered.remove(file) } }
                return@withContext
            }
            try { current.discard() }
            finally { synchronized(lock) { if (current.terminated && retiring === current) retiring = null } }
        }
    }

    /** No take starts after this; one still running is dropped. */
    suspend fun close() {
        cancelOpening()
        withContext(Dispatchers.IO + NonCancellable) {
            val current = synchronized(lock) { closed = true; (pending ?: recorder).also { recorder = null; pending = null } }
            if (current != null) { if (durableTakes) current.preserve() else current.discard() }
        }
    }

    /** Whole seconds of 48 kHz capture-channel float the store's quota and the disk still take. */
    private fun roomSeconds(): Long {
        val perSecond = 4L * 48_000 * captureChannels
        val store = assets.maxStoredBytes - assets.storedBytes() - 44
        val disk = usableDiskBytes(Files.createDirectories(scratch)) - diskReserveBytes - 44
        return minOf(store, disk).coerceAtLeast(0) / perSecond
    }
}
