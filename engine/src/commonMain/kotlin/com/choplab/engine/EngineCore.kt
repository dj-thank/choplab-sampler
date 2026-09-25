package com.choplab.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Single render owner, one ControlRing producer, one EventRing consumer. Construct on a worker.
 * All state and filter storage is bounded and preallocated. No callback is invoked from render.
 * Commands and sequence notes take effect BEFORE their exact output frame is synthesized.
 */
class EngineCore(initialProgram: EngineProgram = EngineProgram.EMPTY, val config: EngineConfig = EngineConfig()) {
    private val pcmOwnership = PcmOwnership(config.residentByteLimit, initialProgram)
    private var programSlots = pcmOwnership.initialSlots
    val controls = ControlRing(config.controlCapacity, pcmOwnership, config.outputMode)
    val events = EventRing(config.eventCapacity)
    val readout = LiveReadout()
    private val interpolator = PitchInterpolator()
    private val limiter = MasterLimiter()
    private val arrangementMixer = ArrangementMixer()
    private val originalVoice = OriginalSourceVoice()
    private val songGain = ParameterSmoother(1f)
    private val voices = Array(PRIMARY_VOICES + FADE_VOICES) { Voice() }
    private val assetScratch: Array<PcmAsset?> = arrayOfNulls(
        EngineFormat.PAD_COUNT + Arrangement.MAX_CLIPS + PRIMARY_VOICES + FADE_VOICES + 1)
    private val clock = SequenceClock(initialProgram.tempo)
    private var program = initialProgram
    private var fenceId = -1L
    private var songFenceId = -1L
    private var sourceFenceId = -1L
    private var triggerSerial = 0L
    private var sequenceCycle = 0L
    private var sequenceIndex = 0
    private var scratchIndex = -1
    private var scratchPad = -1
    var frame = 0L
        private set
    var lateCommands = 0L
        private set
    var rejectedVoices = 0L
        private set
    var sequencePlaying = false
        private set
    var sequencePaused = false
        private set
    /** Next transport input frame, not the DAC position; master adds latencyFrames after this clock. */
    var sequenceFrame = 0L
        private set
    val activeClipCount: Int get() = if (sequencePlaying) arrangementMixer.activeCount else 0
    val originalLoaded: Boolean get() = originalVoice.source != null
    val originalPlaying: Boolean get() = originalVoice.playing
    val originalSourceFrame: Long get() = originalVoice.position.toLong()
    val originalMonitorGain: Float get() = originalVoice.monitorGain.value
    val songMonitorGain: Float get() = songGain.value
    val tickNumerator: Long get() = clock.tickNumerator
    val programRevision: Long get() = program.revision
    val activeVoiceCount: Int get() = countVoices(0, PRIMARY_VOICES)
    val fadeVoiceCount: Int get() = countVoices(PRIMARY_VOICES, voices.size)
    internal fun playingPadMask(upper: Boolean): Long {
        var mask = 0L
        for (voice in voices) {
            val pad = voice.pad ?: continue
            if (voice.suspended || (sequencePaused && voice.transportVoice)) continue
            if ((pad.id >= 64) == upper) mask = mask or (1L shl (pad.id and 63))
        }
        return mask
    }
    var residentBytes: Long = initialProgram.residentBytes
        private set
    val latencyFrames: Int get() = MasterLimiter.LOOKAHEAD_FRAMES

    init {
        require(initialProgram.residentBytes <= config.residentByteLimit)
        arrangementMixer.restore(initialProgram.arrangement, 0)
        readout.publish(this, 0f, 0f)
    }

    /** Writes (does not add to) the supplied buffer; offsets and counts are frames. */
    fun render(output: FloatArray, offsetFrames: Int = 0, frameCount: Int = output.size / 2 - offsetFrames) {
        require(offsetFrames >= 0 && frameCount >= 0 && (offsetFrames.toLong() + frameCount) * 2 <= output.size)
        var peakLeft = 0f
        var peakRight = 0f
        for (f in 0 until frameCount) {
            consumeCommands()
            scheduleNotes()
            var left = 0.0
            var right = 0.0
            if (sequencePlaying && program.arrangement != null) {
                if (arrangementMixer.render(sequenceFrame)) {
                    left += arrangementMixer.outputLeft
                    right += arrangementMixer.outputRight
                } else failArrangementOverload()
            }
            for (i in voices.indices) {
                val voice = voices[i]
                if (voice.pad != null && !voice.suspended && !(sequencePaused && voice.transportVoice)) {
                    voice.render(interpolator)
                    left += voice.outputLeft
                    right += voice.outputRight
                }
            }
            if (config.outputMode == EngineOutputMode.MONITOR) {
                val gain = songGain.next().toDouble()
                left *= gain
                right *= gain
                originalVoice.render(interpolator)
                left += originalVoice.outputLeft
                right += originalVoice.outputRight
            }
            limiter.process(left * config.masterGain, right * config.masterGain)
            val index = (offsetFrames + f) * 2
            output[index] = limiter.outputLeft
            output[index + 1] = limiter.outputRight
            peakLeft = max(peakLeft, abs(limiter.outputLeft))
            peakRight = max(peakRight, abs(limiter.outputRight))
            if (sequencePlaying) {
                clock.advance(1)
                sequenceFrame++
                val arrangement = program.arrangement
                if (arrangement != null && sequenceFrame >= arrangement.durationFrames) {
                    sequencePlaying = false
                    sequencePaused = false
                }
            }
            frame++
        }
        residentBytes = retainedBytes(program)
        pcmOwnership.beginRetention()
        for (slot in programSlots) pcmOwnership.retain(slot)
        for (voice in voices) if (voice.pad != null) pcmOwnership.retain(voice.assetSlot)
        if (originalLoaded) pcmOwnership.retain(originalVoice.assetSlot)
        pcmOwnership.finishRetention()
        readout.publish(this, peakLeft, peakRight)
    }

    private fun consumeCommands() {
        val safety = controls.pendingSafety()
        if (safety != null && safety.effectiveFrame <= frame) {
            apply(safety, null)
            controls.acknowledgeSafety(safety.orderId)
        }
        val sourceSafety = controls.pendingSourceSafety()
        if (sourceSafety != null && sourceSafety.effectiveFrame <= frame) {
            if (sourceSafety.orderId > fenceId) {
                apply(sourceSafety, null)
                sourceFenceId = maxOf(sourceFenceId, sourceSafety.orderId)
            } else events.emit(EngineEventType.INVALIDATED, sourceSafety.orderId, sourceSafety.effectiveFrame, frame)
            controls.acknowledgeSourceSafety(sourceSafety.orderId)
        }
        while (true) {
            val songHead = controls.peekSong()
            if (songHead != null && songHead.orderId <= maxOf(fenceId, songFenceId)) {
                controls.remove(false)
                events.emit(EngineEventType.INVALIDATED, songHead.orderId, songHead.effectiveFrame, frame)
                continue
            }
            val originalHead = controls.peekOriginal()
            if (originalHead != null && originalHead.orderId <= maxOf(fenceId, sourceFenceId)) {
                controls.remove(true)
                events.emit(EngineEventType.INVALIDATED, originalHead.orderId, originalHead.effectiveFrame, frame)
                continue
            }
            val command = controls.peek() ?: break
            if (command.effectiveFrame > frame) break
            val source = command is EngineCommand.OriginalSourceCommand
            val reservations = controls.peekReservations(source)
            controls.remove(source)
            apply(command, reservations)
        }
    }

    private fun apply(command: EngineCommand, reservations: IntArray?) {
        var accepted = true
        when (command) {
            is EngineCommand.Trigger -> accepted = trigger(command.padId, command.velocity, command.orderId)
            is EngineCommand.Release -> for (voice in voices) {
                if (voice.pad?.id == command.padId && !voice.scratch) voice.release()
            }
            is EngineCommand.Stop -> { stop(false); songFenceId = maxOf(songFenceId, command.orderId) }
            is EngineCommand.Panic -> { stop(true); fenceId = maxOf(fenceId, command.orderId) }
            is EngineCommand.StopAll -> {
                stop(false)
                originalVoice.stop(false)
                fenceId = maxOf(fenceId, command.orderId)
            }
            is EngineCommand.SwapProgram -> {
                val bytes = retainedBytes(command.program)
                if (bytes > config.residentByteLimit) {
                    accepted = false
                    events.emit(EngineEventType.PROGRAM_MEMORY_LIMIT, command.orderId, command.effectiveFrame, frame)
                } else {
                    val arrangementChanged = program.arrangement != null || command.program.arrangement != null
                    program = command.program
                    programSlots = reservations!!
                    residentBytes = bytes
                    clock.setTempo(program.tempo)
                    if (arrangementChanged) {
                        clearTransportVoices()
                        val arrangement = program.arrangement
                        if (arrangement != null) {
                            sequenceFrame = minOf(sequenceFrame, arrangement.durationFrames)
                            if (sequenceFrame >= arrangement.durationFrames) sequencePlaying = false
                            clock.seekFrame(sequenceFrame)
                        }
                        if (!arrangementMixer.restore(arrangement, sequenceFrame)) failArrangementOverload()
                        // Samples already in lookahead retain their original graph until its 72-frame delay passes.
                    }
                    if (program.arrangement == null) repositionSequence()
                }
            }
            is EngineCommand.StartSequence -> {
                clearTransportVoices()
                clock.reset(); clock.setTempo(program.tempo)
                sequenceCycle = 0; sequenceIndex = 0; sequenceFrame = 0; sequencePaused = false
                val arrangement = program.arrangement
                sequencePlaying = arrangement == null || arrangement.durationFrames > 0
                if (!arrangementMixer.restore(arrangement, 0)) failArrangementOverload()
                if (arrangement != null) limiter.reset()
            }
            is EngineCommand.Seek -> {
                val arrangement = program.arrangement
                if (arrangement == null || command.sequenceFrame > arrangement.durationFrames) {
                    accepted = false
                    events.emit(EngineEventType.INVALID_COMMAND, command.orderId, command.effectiveFrame, frame)
                } else {
                    sequenceFrame = command.sequenceFrame
                    clock.seekFrame(sequenceFrame)
                    clearTransportVoices()
                    if (!arrangementMixer.restore(arrangement, sequenceFrame)) failArrangementOverload()
                    if (sequenceFrame >= arrangement.durationFrames) sequencePlaying = false
                    limiter.reset()
                }
            }
            is EngineCommand.Pause -> {
                sequencePlaying = false
                sequencePaused = true
                limiter.reset()
            }
            is EngineCommand.Resume -> {
                sequencePaused = false
                val arrangement = program.arrangement
                sequencePlaying = arrangement == null || sequenceFrame < arrangement.durationFrames
            }
            is EngineCommand.SetOriginalSource -> {
                originalVoice.set(command.source, if (command.source == null) -1 else reservations!![0])
                if (command.source == null) sourceFenceId = maxOf(sourceFenceId, command.orderId)
            }
            is EngineCommand.PlayOriginalSource -> {
                accepted = originalVoice.play()
                if (!accepted) events.emit(EngineEventType.ASSET_MISS, command.orderId, command.effectiveFrame, frame)
            }
            is EngineCommand.PauseOriginalSource -> {
                originalVoice.pause()
                sourceFenceId = maxOf(sourceFenceId, command.orderId)
            }
            is EngineCommand.SeekOriginalSource -> {
                accepted = originalVoice.seek(command.sourceFrame)
                if (!accepted) events.emit(EngineEventType.INVALID_COMMAND, command.orderId, command.effectiveFrame, frame)
            }
            is EngineCommand.SetOriginalMonitorGain -> originalVoice.monitorGain.set(command.gain, 96)
            is EngineCommand.SetSongMonitorGain -> songGain.set(command.gain, 96)
            is EngineCommand.SetTempo -> clock.setTempo(command.tempo)
            is EngineCommand.ScratchStart -> accepted = startScratch(command)
            is EngineCommand.ScratchPosition -> {
                if (scratchIndex >= 0) {
                    val voice = voices[scratchIndex]
                    val pad = voice.pad
                    if (pad != null) {
                        val target = command.sourceFrame.coerceIn(pad.startFrame.toDouble(), pad.endFrame - 1.0)
                        val speed = (target - voice.position) / command.durationFrames
                        if (abs(speed) <= 8.0) {
                            voice.scratchStep = speed
                            voice.motionFrames = command.durationFrames
                        } else accepted = false
                    } else accepted = false
                } else accepted = false
                if (!accepted) events.emit(EngineEventType.INVALID_COMMAND, command.orderId, command.effectiveFrame, frame, scratchPad)
            }
            is EngineCommand.ScratchCut -> if (scratchIndex >= 0) voices[scratchIndex].cut.set(command.gain, 96)
            is EngineCommand.ScratchEnd -> endScratch()
        }
        if (accepted) {
            val late = command.effectiveFrame < frame
            if (late) lateCommands++
            events.emit(if (late) EngineEventType.LATE else EngineEventType.APPLIED,
                command.orderId, command.effectiveFrame, frame)
        }
    }

    private fun trigger(padId: Int, velocity: Float, orderId: Long, transportVoice: Boolean = false): Boolean {
        val pad = program.pad(padId)
        if (pad == null) {
            events.emit(EngineEventType.ASSET_MISS, orderId, frame, frame, padId)
            return false
        }
        if (padId == scratchPad || velocity == 0f) return true
        if (pad.chokeGroup != 0) for (voice in voices) {
            if (voice.pad?.chokeGroup == pad.chokeGroup) voice.release()
        }
        val slot = acquireVoice()
        if (slot < 0) {
            rejectedVoices++
            events.emit(EngineEventType.VOICE_LIMIT, orderId, frame, frame, padId)
            return false
        }
        voices[slot].start(pad, velocity, triggerSerial++)
        voices[slot].assetSlot = slotForPad(pad)
        voices[slot].transportVoice = transportVoice
        return true
    }

    private fun acquireVoice(): Int {
        for (i in 0 until PRIMARY_VOICES) if (voices[i].pad == null) return i
        var fade = -1
        for (i in PRIMARY_VOICES until voices.size) if (voices[i].pad == null) { fade = i; break }
        if (fade < 0) return -1 // Preserve all existing tails; overload rejects the newest trigger.
        var oldest = -1
        for (i in 0 until PRIMARY_VOICES) {
            if (!voices[i].scratch && !voices[i].suspended && (oldest < 0 || voices[i].serial < voices[oldest].serial)) oldest = i
        }
        if (oldest < 0) return -1
        voices[fade].copyFrom(voices[oldest])
        voices[fade].release(STEAL_FADE_FRAMES)
        voices[oldest].clear()
        return oldest
    }

    private fun startScratch(command: EngineCommand.ScratchStart): Boolean {
        val pad = program.pad(command.padId)
        if (pad == null) {
            events.emit(EngineEventType.ASSET_MISS, command.orderId, command.effectiveFrame, frame, command.padId)
            return false
        }
        endScratch()
        for (voice in voices) if (voice.pad?.id == pad.id && !voice.scratch) voice.suspended = true
        val slot = acquireVoice()
        if (slot < 0) {
            for (voice in voices) if (voice.suspended) voice.suspended = false
            rejectedVoices++
            events.emit(EngineEventType.VOICE_LIMIT, command.orderId, command.effectiveFrame, frame, command.padId)
            return false
        }
        voices[slot].start(pad, 1f, triggerSerial++)
        voices[slot].assetSlot = slotForPad(pad)
        voices[slot].scratch = true
        voices[slot].position = command.sourceFrame.coerceIn(pad.startFrame.toDouble(), pad.endFrame - 1.0)
        scratchPad = pad.id
        scratchIndex = slot
        return true
    }

    private fun endScratch() {
        if (scratchIndex >= 0) {
            val voice = voices[scratchIndex]
            voice.release(STEAL_FADE_FRAMES)
            // Continue the last movement while fading, then release its retained PCM.
            voice.motionFrames = maxOf(voice.motionFrames, STEAL_FADE_FRAMES)
        }
        for (voice in voices) if (voice.suspended) { voice.suspended = false; voice.age = 0 }
        scratchPad = -1
        scratchIndex = -1
    }

    private fun stop(panic: Boolean) {
        sequencePlaying = false
        sequencePaused = false
        sequenceFrame = 0
        clock.reset()
        sequenceCycle = 0
        sequenceIndex = 0
        arrangementMixer.restore(program.arrangement, 0)
        scratchIndex = -1
        scratchPad = -1
        for (voice in voices) {
            voice.suspended = false
            if (panic) voice.clear() else voice.release(STEAL_FADE_FRAMES)
        }
        if (panic) { originalVoice.stop(true); limiter.reset() }
    }

    private fun scheduleNotes() {
        if (!sequencePlaying || program.arrangement != null) return
        val pattern = program.pattern ?: return
        if (pattern.noteCount == 0) return
        while (true) {
            val note = pattern.note(sequenceIndex)
            val tick = sequenceCycle * pattern.lengthTicks + note.tick
            if (clock.framesUntil(tick) > 0) break
            trigger(note.padId, note.velocity, -1, true)
            sequenceIndex++
            if (sequenceIndex == pattern.noteCount) { sequenceIndex = 0; sequenceCycle++ }
        }
    }

    private fun repositionSequence() {
        val pattern = program.pattern ?: return
        if (pattern.noteCount == 0) return
        // Start at the first note whose exact deadline is at or after the current phase.
        sequenceCycle = clock.tickNumerator / (pattern.lengthTicks.toLong() * SequenceClock.UNITS_PER_TICK)
        sequenceIndex = 0
        while (SequenceClock.targetNumerator(sequenceCycle * pattern.lengthTicks + pattern.note(sequenceIndex).tick,
                clock.tempo.swingPermille) < clock.tickNumerator) {
            sequenceIndex++
            if (sequenceIndex == pattern.noteCount) { sequenceIndex = 0; sequenceCycle++; break }
        }
    }

    private fun retainedBytes(candidate: EngineProgram): Long {
        var count = 0
        var bytes = 0L
        for (i in 0 until candidate.assetCount + voices.size + 1) {
            val asset = if (i < candidate.assetCount) candidate.asset(i)
                else if (i < candidate.assetCount + voices.size) voices[i - candidate.assetCount].pad?.asset
                else originalVoice.source?.asset
            if (asset != null) {
                var exists = false
                for (j in 0 until count) if (assetScratch[j] === asset) { exists = true; break }
                if (!exists) { assetScratch[count++] = asset; bytes += asset.residentBytes }
            }
        }
        for (i in 0 until count) assetScratch[i] = null
        return bytes
    }

    private fun slotForPad(pad: Pad): Int {
        for (i in 0 until program.assetCount) if (program.asset(i) === pad.asset) return programSlots[i]
        return -1
    }

    private fun clearTransportVoices() {
        for (voice in voices) if (voice.transportVoice) voice.clear()
    }

    private fun failArrangementOverload() {
        sequencePlaying = false
        sequencePaused = false
        events.emit(EngineEventType.ARRANGEMENT_OVERLOAD, -1, frame, frame)
    }

    private fun countVoices(start: Int, end: Int): Int {
        var result = 0
        for (i in start until end) if (voices[i].pad != null) result++
        return result
    }

    companion object {
        const val PRIMARY_VOICES = 32
        const val FADE_VOICES = 16
        const val STEAL_FADE_FRAMES = 96
        const val STOP_TAIL_FRAMES = STEAL_FADE_FRAMES + MasterLimiter.LOOKAHEAD_FRAMES
    }
}

private class Voice {
    var pad: Pad? = null
    var position = 0.0
    var serial = 0L
    var age = 0
    var suspended = false
    var scratch = false
    var transportVoice = false
    var assetSlot = -1
    var scratchStep = 0.0
    var motionFrames = 0
    val cut = ParameterSmoother(1f)
    private var velocity = 1f
    private var released = false
    private var releaseAge = 0
    private var releaseLength = 96
    private var releaseGain = 1.0
    var outputLeft = 0.0
        private set
    var outputRight = 0.0
        private set

    fun start(pad: Pad, velocity: Float, serial: Long) {
        this.pad = pad
        this.velocity = velocity
        this.serial = serial
        position = if (pad.reverse) pad.endFrame - 1.0 else pad.startFrame.toDouble()
        age = 0; suspended = false; scratch = false; transportVoice = false; scratchStep = 0.0; motionFrames = 0
        released = false; releaseAge = 0; releaseLength = pad.releaseFrames; releaseGain = 1.0
        cut.set(1f, 0)
    }
    fun clear() { pad = null; outputLeft = 0.0; outputRight = 0.0; suspended = false; scratch = false; transportVoice = false; assetSlot = -1 }
    fun release(frames: Int = pad?.releaseFrames ?: 96) {
        if (!released) {
            val currentPad = pad
            releaseGain = if (currentPad != null) heldLevel(currentPad) else 0.0
            released = true; releaseAge = 0; releaseLength = frames
        }
        else if (frames < releaseLength - releaseAge) {
            releaseGain *= 1.0 - smoothUnit(releaseAge.toDouble() / maxOf(1, releaseLength - 1))
            releaseAge = 0
            releaseLength = frames
        }
    }
    fun copyFrom(other: Voice) {
        pad = other.pad; position = other.position; serial = other.serial; age = other.age
        suspended = other.suspended; scratch = other.scratch; scratchStep = other.scratchStep; motionFrames = other.motionFrames
        transportVoice = other.transportVoice
        assetSlot = other.assetSlot
        velocity = other.velocity; released = other.released; releaseAge = other.releaseAge; releaseLength = other.releaseLength
        releaseGain = other.releaseGain
        cut.set(other.cut.value, 0)
    }
    fun render(interpolator: PitchInterpolator) {
        outputLeft = 0.0; outputRight = 0.0
        val pad = pad ?: return
        val loop = pad.mode == PlayMode.LOOP && !scratch
        val speed = if (scratch) scratchStep else pad.step
        if (!loop && !scratch && (position < pad.startFrame || position >= pad.endFrame)) { clear(); return }
        if (released && releaseAge >= releaseLength) { clear(); return }
        val duration = (pad.endFrame - pad.startFrame) / abs(pad.step)
        var envelope = if (released) releaseGain * (1.0 - smoothUnit(releaseAge.toDouble() / maxOf(1, releaseLength - 1)))
            else heldLevel(pad)
        if (!loop && !scratch) {
            val remaining = if (speed < 0) (position - pad.startFrame) / -speed else (pad.endFrame - 1.0 - position) / speed
            val naturalRelease = minOf(pad.releaseFrames, (duration / 4).toInt())
            if (naturalRelease > 0) envelope *= smoothUnit(remaining / naturalRelease)
        }
        val gate = cut.next().toDouble()
        if (!scratch || (motionFrames > 0 && abs(speed) > 1e-12)) {
            envelope *= gate * velocity
            outputLeft = interpolator.read(pad.asset, position, speed, 0, pad.startFrame, pad.endFrame, loop,
                pad.loopCrossfadeFrames) * envelope * pad.leftGain
            outputRight = interpolator.read(pad.asset, position, speed, 1, pad.startFrame, pad.endFrame, loop,
                pad.loopCrossfadeFrames) * envelope * pad.rightGain
            position += speed
            if (scratch) {
                motionFrames--
                if (position < pad.startFrame || position > pad.endFrame - 1.0) motionFrames = 0
                position = position.coerceIn(pad.startFrame.toDouble(), pad.endFrame - 1.0)
            } else if (loop) {
                val length = pad.endFrame - pad.startFrame
                position = pad.startFrame + (position - pad.startFrame) - floor((position - pad.startFrame) / length) * length
            }
        }
        if (age < Int.MAX_VALUE) age++
        if (released) releaseAge++
    }

    private fun heldLevel(pad: Pad): Double {
        val duration = (pad.endFrame - pad.startFrame) / abs(pad.step)
        val attack = if (pad.mode != PlayMode.LOOP && !scratch) minOf(pad.attackFrames, (duration / 4).toInt()) else pad.attackFrames
        return AdsrEnvelope.heldLevel(age, attack, pad.decayFrames, pad.sustainLevel)
    }
}
