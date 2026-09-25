package com.choplab.engine

import kotlin.concurrent.Volatile

/** Allocated by the single control producer, never by render. IDs increase across all commands. */
sealed class EngineCommand(val effectiveFrame: Long, val orderId: Long) {
    init { require(effectiveFrame >= 0 && orderId >= 0) }
    class Trigger(effectiveFrame: Long, orderId: Long, val padId: Int, val velocity: Float = 1f) :
        EngineCommand(effectiveFrame, orderId) {
        init { require(padId in 0 until 128 && velocity.isFinite() && velocity in 0f..1f) }
    }
    class Release(effectiveFrame: Long, orderId: Long, val padId: Int) : EngineCommand(effectiveFrame, orderId) {
        init { require(padId in 0 until 128) }
    }
    class Stop(effectiveFrame: Long, orderId: Long) : EngineCommand(effectiveFrame, orderId)
    class StopAll(effectiveFrame: Long, orderId: Long) : EngineCommand(effectiveFrame, orderId)
    class Panic(effectiveFrame: Long, orderId: Long) : EngineCommand(effectiveFrame, orderId)
    class SwapProgram(effectiveFrame: Long, orderId: Long, val program: EngineProgram) : EngineCommand(effectiveFrame, orderId)
    class StartSequence(effectiveFrame: Long, orderId: Long) : EngineCommand(effectiveFrame, orderId)
    /** Arrangement-only absolute seek; engine command time remains monotonic and independent. */
    class Seek(effectiveFrame: Long, orderId: Long, val sequenceFrame: Long) : EngineCommand(effectiveFrame, orderId) {
        init { require(sequenceFrame >= 0) }
    }
    class Pause(effectiveFrame: Long, orderId: Long) : EngineCommand(effectiveFrame, orderId)
    class Resume(effectiveFrame: Long, orderId: Long) : EngineCommand(effectiveFrame, orderId)
    sealed class OriginalSourceCommand(effectiveFrame: Long, orderId: Long) : EngineCommand(effectiveFrame, orderId)
    class SetOriginalSource(effectiveFrame: Long, orderId: Long, val source: OriginalSource?) : OriginalSourceCommand(effectiveFrame, orderId)
    class PlayOriginalSource(effectiveFrame: Long, orderId: Long) : OriginalSourceCommand(effectiveFrame, orderId)
    class PauseOriginalSource(effectiveFrame: Long, orderId: Long) : OriginalSourceCommand(effectiveFrame, orderId)
    class SeekOriginalSource(effectiveFrame: Long, orderId: Long, val sourceFrame: Long) : OriginalSourceCommand(effectiveFrame, orderId) {
        init { require(sourceFrame >= 0) }
    }
    class SetOriginalMonitorGain(effectiveFrame: Long, orderId: Long, val gain: Float) : OriginalSourceCommand(effectiveFrame, orderId) {
        init { require(gain.isFinite() && gain in 0f..2f) }
    }
    class SetSongMonitorGain(effectiveFrame: Long, orderId: Long, val gain: Float) : EngineCommand(effectiveFrame, orderId) {
        init { require(gain.isFinite() && gain in 0f..1f) }
    }
    class SetTempo(effectiveFrame: Long, orderId: Long, val tempo: Tempo) : EngineCommand(effectiveFrame, orderId)
    class ScratchStart(effectiveFrame: Long, orderId: Long, val padId: Int, val sourceFrame: Double) :
        EngineCommand(effectiveFrame, orderId) {
        init { require(padId in 0 until 128 && sourceFrame.isFinite()) }
    }
    /** Reach this absolute source position over the next durationFrames output frames. */
    class ScratchPosition(effectiveFrame: Long, orderId: Long, val sourceFrame: Double, val durationFrames: Int) :
        EngineCommand(effectiveFrame, orderId) {
        init { require(sourceFrame.isFinite() && durationFrames in 1..48_000) }
    }
    class ScratchCut(effectiveFrame: Long, orderId: Long, val gain: Float) : EngineCommand(effectiveFrame, orderId) {
        init { require(gain.isFinite() && gain in 0f..1f) }
    }
    class ScratchEnd(effectiveFrame: Long, orderId: Long) : EngineCommand(effectiveFrame, orderId)
}

enum class OfferResult { ACCEPTED, FULL, OUT_OF_ORDER, PCM_LIMIT, MONITOR_DISABLED }

/**
 * Single producer / single consumer. Volatile publication is acquire/release on both JVM targets.
 * Normal commands must be ordered by (frame, ID). Rejected offers do not consume an ID.
 * Stop/Panic has a separate mailbox, accepts an earlier frame, and invalidates all preceding IDs.
 * Several pending safety commands coalesce to the earliest frame and strongest action.
 */
class ControlRing internal constructor(val capacity: Int, private val ownership: PcmOwnership, private val outputMode: EngineOutputMode) {
    private class Lane(capacity: Int) {
        val commands: Array<EngineCommand?> = arrayOfNulls(capacity)
        val reservations: Array<IntArray?> = arrayOfNulls(capacity)
        val mask = capacity - 1
        @Volatile var write = 0L
        @Volatile var read = 0L
        var lastFrame = -1L
        fun peek(): EngineCommand? = if (read == write) null else commands[read.toInt() and mask]
        fun full(): Boolean = write - read >= commands.size
    }
    private val song = Lane(capacity)
    private val original = Lane(capacity)
    @Volatile private var safety: EngineCommand? = null
    @Volatile private var acknowledgedSafety = -1L
    @Volatile private var sourceSafety: EngineCommand? = null
    @Volatile private var acknowledgedSourceSafety = -1L
    @Volatile var overflowCount = 0L
        private set
    private var lastId = -1L

    fun offer(command: EngineCommand): OfferResult {
        val isOriginal = command is EngineCommand.OriginalSourceCommand
        if ((isOriginal || command is EngineCommand.SetSongMonitorGain) && outputMode == EngineOutputMode.EXPORT) return OfferResult.MONITOR_DISABLED
        if (command.orderId <= lastId) return OfferResult.OUT_OF_ORDER
        val globalStop = command is EngineCommand.Panic || command is EngineCommand.StopAll
        if ((globalStop && (song.full() || original.full() || command.effectiveFrame < maxOf(song.lastFrame, original.lastFrame))) ||
            (command is EngineCommand.Stop && (song.full() || command.effectiveFrame < song.lastFrame))) {
            val pending = safety
            val live = pending != null && pending.orderId > acknowledgedSafety
            val at = if (live) minOf(pending!!.effectiveFrame, command.effectiveFrame) else command.effectiveFrame
            val panic = command is EngineCommand.Panic || (live && pending is EngineCommand.Panic)
            val all = globalStop || (live && pending is EngineCommand.StopAll) || panic
            safety = if (panic) EngineCommand.Panic(at, command.orderId)
                else if (all) EngineCommand.StopAll(at, command.orderId) else EngineCommand.Stop(at, command.orderId)
            lastId = command.orderId
            song.lastFrame = command.effectiveFrame
            if (all) original.lastFrame = command.effectiveFrame
            return OfferResult.ACCEPTED
        }
        if ((command is EngineCommand.PauseOriginalSource || (command is EngineCommand.SetOriginalSource && command.source == null)) &&
            (command.effectiveFrame < original.lastFrame || original.full())) {
            val pending = sourceSafety
            val live = pending != null && pending.orderId > acknowledgedSourceSafety
            val at = if (live) minOf(pending!!.effectiveFrame, command.effectiveFrame) else command.effectiveFrame
            val clear = command is EngineCommand.SetOriginalSource || (live && pending is EngineCommand.SetOriginalSource)
            sourceSafety = if (clear) EngineCommand.SetOriginalSource(at, command.orderId, null) else EngineCommand.PauseOriginalSource(at, command.orderId)
            lastId = command.orderId
            original.lastFrame = command.effectiveFrame
            return OfferResult.ACCEPTED
        }
        val lane = if (isOriginal) original else song
        if (command.effectiveFrame < lane.lastFrame) return OfferResult.OUT_OF_ORDER
        if (lane.full()) { overflowCount++; return OfferResult.FULL }
        val leases = when (command) {
            is EngineCommand.SwapProgram -> ownership.reserve(command.program) ?: return if (ownership.reservationBusy) OfferResult.FULL else OfferResult.PCM_LIMIT
            is EngineCommand.SetOriginalSource -> ownership.reserve(command.source) ?: return if (ownership.reservationBusy) OfferResult.FULL else OfferResult.PCM_LIMIT
            else -> null
        }
        val index = lane.write.toInt() and lane.mask
        lane.commands[index] = command
        lane.reservations[index] = leases
        lane.write++
        lane.lastFrame = command.effectiveFrame
        lastId = command.orderId
        return OfferResult.ACCEPTED
    }
    internal fun peekSong(): EngineCommand? = song.peek()
    internal fun peekOriginal(): EngineCommand? = original.peek()
    internal fun peek(): EngineCommand? {
        val a = song.peek() ?: return original.peek()
        val b = original.peek() ?: return a
        return if (a.effectiveFrame < b.effectiveFrame || (a.effectiveFrame == b.effectiveFrame && a.orderId < b.orderId)) a else b
    }
    internal fun peekReservations(source: Boolean): IntArray? {
        val lane = if (source) original else song
        return lane.reservations[lane.read.toInt() and lane.mask]
    }
    internal fun remove(source: Boolean) {
        val lane = if (source) original else song
        val index = lane.read.toInt() and lane.mask
        ownership.consume(lane.reservations[index])
        lane.reservations[index] = null
        lane.commands[index] = null
        lane.read++
    }
    internal fun pendingSafety(): EngineCommand? {
        val result = safety
        return if (result != null && result.orderId > acknowledgedSafety) result else null
    }
    internal fun acknowledgeSafety(id: Long) { acknowledgedSafety = id }
    internal fun pendingSourceSafety(): EngineCommand? {
        val result = sourceSafety
        return if (result != null && result.orderId > acknowledgedSourceSafety) result else null
    }
    internal fun acknowledgeSourceSafety(id: Long) { acknowledgedSourceSafety = id }
}
enum class EngineEventType { APPLIED, LATE, INVALIDATED, VOICE_LIMIT, ASSET_MISS, PROGRAM_MEMORY_LIMIT, INVALID_COMMAND, ARRANGEMENT_OVERLOAD }

class MutableEngineEvent {
    var type = EngineEventType.APPLIED
    var orderId = -1L
    var requestedFrame = 0L
    var appliedFrame = 0L
    var detail = 0
}

/** Renderer is the only producer; a full event ring drops newest and exposes a monotonic loss count. */
class EventRing internal constructor(val capacity: Int) {
    private val types = IntArray(capacity)
    private val ids = LongArray(capacity)
    private val requested = LongArray(capacity)
    private val applied = LongArray(capacity)
    private val details = IntArray(capacity)
    private val mask = capacity - 1
    @Volatile private var write = 0L
    @Volatile private var read = 0L
    @Volatile var overflowCount = 0L
        private set
    internal fun emit(type: EngineEventType, orderId: Long, requestedFrame: Long, appliedFrame: Long, detail: Int = 0) {
        if (write - read == capacity.toLong()) { overflowCount++; return }
        val i = write.toInt() and mask
        types[i] = type.ordinal
        ids[i] = orderId
        requested[i] = requestedFrame
        applied[i] = appliedFrame
        details[i] = detail
        write++
    }
    fun poll(target: MutableEngineEvent): Boolean {
        if (read == write) return false
        val i = read.toInt() and mask
        target.type = EngineEventType.entries[types[i]]
        target.orderId = ids[i]
        target.requestedFrame = requested[i]
        target.appliedFrame = applied[i]
        target.detail = details[i]
        read++
        return true
    }
}

/** Reused by the UI/control consumer. Publication occurs after each render call. */
class EngineSnapshot {
    @Volatile var frame = 0L
    @Volatile var programRevision = 0L
    @Volatile var activeVoices = 0
    @Volatile var fadeVoices = 0
    @Volatile var sequencePlaying = false
    @Volatile var sequencePaused = false
    @Volatile var sequenceFrame = 0L
    @Volatile var activeClips = 0
    @Volatile var originalLoaded = false
    @Volatile var originalPlaying = false
    @Volatile var originalSourceFrame = 0L
    @Volatile var originalMonitorGain = 1f
    @Volatile var songMonitorGain = 1f
    @Volatile var tickNumerator = 0L
    @Volatile var peakLeft = 0f
    @Volatile var peakRight = 0f
    @Volatile var lateCommands = 0L
    @Volatile var rejectedVoices = 0L
    @Volatile var controlOverflows = 0L
    @Volatile var eventOverflows = 0L
    @Volatile var residentBytes = 0L
}

/** Bounded seqlock read; false means the reader should try again on its next UI/control tick. */
class LiveReadout internal constructor() {
    @Volatile private var version = 0L
    private val data = EngineSnapshot()
    internal fun publish(engine: EngineCore, peakLeft: Float, peakRight: Float) {
        version++
        data.frame = engine.frame
        data.programRevision = engine.programRevision
        data.activeVoices = engine.activeVoiceCount
        data.fadeVoices = engine.fadeVoiceCount
        data.sequencePlaying = engine.sequencePlaying
        data.sequencePaused = engine.sequencePaused
        data.sequenceFrame = engine.sequenceFrame
        data.activeClips = engine.activeClipCount
        data.originalLoaded = engine.originalLoaded
        data.originalPlaying = engine.originalPlaying
        data.originalSourceFrame = engine.originalSourceFrame
        data.originalMonitorGain = engine.originalMonitorGain
        data.songMonitorGain = engine.songMonitorGain
        data.tickNumerator = engine.tickNumerator
        data.peakLeft = peakLeft
        data.peakRight = peakRight
        data.lateCommands = engine.lateCommands
        data.rejectedVoices = engine.rejectedVoices
        data.controlOverflows = engine.controls.overflowCount
        data.eventOverflows = engine.events.overflowCount
        data.residentBytes = engine.residentBytes
        version++
    }
    fun copyInto(target: EngineSnapshot): Boolean {
        repeat(3) {
            val before = version
            if (before and 1L == 0L) {
                target.frame = data.frame
                target.programRevision = data.programRevision
                target.activeVoices = data.activeVoices
                target.fadeVoices = data.fadeVoices
                target.sequencePlaying = data.sequencePlaying
                target.sequencePaused = data.sequencePaused
                target.sequenceFrame = data.sequenceFrame
                target.activeClips = data.activeClips
                target.originalLoaded = data.originalLoaded
                target.originalPlaying = data.originalPlaying
                target.originalSourceFrame = data.originalSourceFrame
                target.originalMonitorGain = data.originalMonitorGain
                target.songMonitorGain = data.songMonitorGain
                target.tickNumerator = data.tickNumerator
                target.peakLeft = data.peakLeft
                target.peakRight = data.peakRight
                target.lateCommands = data.lateCommands
                target.rejectedVoices = data.rejectedVoices
                target.controlOverflows = data.controlOverflows
                target.eventOverflows = data.eventOverflows
                target.residentBytes = data.residentBytes
                if (before == version) return true
            }
        }
        return false
    }
}
