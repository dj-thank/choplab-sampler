package com.choplab.sampler.audio

import com.choplab.sampler.model.LfoTarget
import com.choplab.sampler.model.LfoWaveform
import com.choplab.sampler.model.MasterEffects
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.ProjectSnapshot
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.sequencer.SequenceCompiler
import com.choplab.sampler.sequencer.SequenceTimeline
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** Offline stereo renderer mirroring the Oboe engine's voice and master signal path. */
object OfflineRenderer {
    data class RenderSummary(
        val sampleRate: Int,
        val frameCount: Long,
        val peak: Float,
        val renderedPadCount: Int,
    ) {
        val durationSeconds: Double get() = frameCount.toDouble() / sampleRate
    }

    data class StemSummary(
        val master: RenderSummary,
        val bankStemCount: Int,
        val padStemCount: Int,
    )

    fun renderToWav(
        outputFile: File,
        project: ProjectSnapshot,
        timeline: SequenceTimeline,
        includedPads: Set<Int>? = null,
        applyMasterEffects: Boolean = true,
        outputSampleRate: Int = 48_000,
        tailSeconds: Float = 2f,
        progress: ((Float) -> Unit)? = null,
    ): RenderSummary {
        require(outputSampleRate in 8_000..192_000)
        val safe = project.sanitized()
        val allowed = includedPads?.filterTo(mutableSetOf()) { it in 0 until SamplerConfig.PAD_COUNT }
        val eventFrames = LongArray(timeline.stepCount)
        val gateFrames = IntArray(timeline.stepCount)
        var cursor = 0.0
        for (step in 0 until timeline.stepCount) {
            eventFrames[step] = cursor.toLong()
            val length = stepFrames(outputSampleRate, safe.bpm, safe.swing, step)
            gateFrames[step] = max(1, (length * 0.92).toInt())
            cursor += length
        }
        val sequenceFrames = max(1L, ceil(cursor).toLong())
        val totalFrames = sequenceFrames + (tailSeconds.coerceIn(0f, 12f) * outputSampleRate).toLong()
        require(totalFrames * 4L + 36L <= 0xFFFF_FFFFL) {
            "書き出し結果がWAVの4GB上限を超えます。Songを分割してください"
        }

        val usedPads = SequenceCompiler.usedPads(timeline).filterTo(mutableSetOf()) { pad ->
            allowed == null || pad in allowed
        }
        val voices = ArrayList<Voice>(96)
        val delay = DelayBus(outputSampleRate)
        val reverb = ReverbBus(outputSampleRate)
        val master = MasterProcessor(outputSampleRate)
        val pcm = ShortArray(BLOCK_FRAMES * 2)
        var pcmIndex = 0
        var nextEvent = 0
        var peak = 0f

        WavFileWriter(outputFile, outputSampleRate, 2).use { writer ->
            var frame = 0L
            while (frame < totalFrames) {
                while (nextEvent < eventFrames.size && eventFrames[nextEvent] == frame) {
                    val mask = timeline.padMasks[nextEvent]
                    for (padIndex in 0 until SamplerConfig.PAD_COUNT) {
                        if ((mask and (1L shl padIndex)) == 0L) continue
                        if (allowed != null && padIndex !in allowed) continue
                        val pad = safe.pads.getOrNull(padIndex)?.sanitized() ?: continue
                        if (!pad.isAssigned) continue
                        if (pad.chokeGroup > 0) {
                            voices.filter { it.active && it.chokeGroup == pad.chokeGroup }
                                .forEach { it.noteOff(force = true) }
                        }
                        if (voices.size >= MAX_POLYPHONY) {
                            voices.minByOrNull { it.serial }?.active = false
                        }
                        voices += Voice(
                            pad = pad,
                            outputSampleRate = outputSampleRate,
                            velocity = 1f,
                            serial = frame * 64L + padIndex,
                            autoReleaseFrames = if (pad.playMode == PadPlayMode.GATE) gateFrames[nextEvent] else -1,
                        )
                    }
                    nextEvent++
                }

                var dryL = 0f
                var dryR = 0f
                var delayL = 0f
                var delayR = 0f
                var reverbL = 0f
                var reverbR = 0f
                var index = 0
                while (index < voices.size) {
                    val voice = voices[index]
                    val sample = voice.render(safe.bpm, outputSampleRate)
                    dryL += sample.dryL
                    dryR += sample.dryR
                    delayL += sample.delayL
                    delayR += sample.delayR
                    reverbL += sample.reverbL
                    reverbR += sample.reverbR
                    if (!voice.active) voices.removeAt(index) else index++
                }

                val fx = if (applyMasterEffects) safe.masterEffects else MasterEffects(
                    delayEnabled = false,
                    delayMix = 0f,
                    reverbEnabled = false,
                    reverbMix = 0f,
                    compressorEnabled = false,
                    masterGain = 1f,
                )
                val delayed = delay.process(delayL, delayR, fx, safe.bpm)
                val reverberated = reverb.process(
                    reverbL + delayed.first * 0.15f,
                    reverbR + delayed.second * 0.15f,
                    fx,
                )
                var left = dryL + delayed.first + reverberated.first
                var right = dryR + delayed.second + reverberated.second
                if (applyMasterEffects) {
                    val mastered = master.process(left, right, fx)
                    left = mastered.first
                    right = mastered.second
                }
                peak = max(peak, max(abs(left), abs(right)))
                pcm[pcmIndex++] = floatToPcm16(left)
                pcm[pcmIndex++] = floatToPcm16(right)
                if (pcmIndex == pcm.size) {
                    writer.writePcm16(pcm)
                    pcmIndex = 0
                }
                frame++
                if (frame % (outputSampleRate / 4).coerceAtLeast(1) == 0L) {
                    progress?.invoke((frame.toDouble() / totalFrames).toFloat().coerceIn(0f, 1f))
                }
            }
            if (pcmIndex > 0) writer.writePcm16(pcm, pcmIndex)
        }
        progress?.invoke(1f)
        return RenderSummary(outputSampleRate, totalFrames, peak, usedPads.size)
    }

    fun exportStemsZip(
        output: OutputStream,
        workingDirectory: File,
        project: ProjectSnapshot,
        timeline: SequenceTimeline,
        outputSampleRate: Int = 48_000,
        progress: ((String, Float) -> Unit)? = null,
    ): StemSummary {
        workingDirectory.mkdirs()
        val safe = project.sanitized()
        val used = SequenceCompiler.usedPads(timeline)
            .filter { safe.pads.getOrNull(it)?.isAssigned == true }
            .sorted()
        val banks = used.groupBy { it / SamplerConfig.PADS_PER_BANK }
        val jobs = 1 + banks.size + used.size
        var complete = 0
        lateinit var masterSummary: RenderSummary

        ZipOutputStream(output.buffered()).use { zip ->
            fun renderEntry(
                name: String,
                pads: Set<Int>?,
                label: String,
                withMasterEffects: Boolean,
            ): RenderSummary {
                progress?.invoke(label, complete.toFloat() / jobs)
                val file = File(workingDirectory, name.substringAfterLast('/'))
                val summary = renderToWav(
                    outputFile = file,
                    project = safe,
                    timeline = timeline,
                    includedPads = pads,
                    applyMasterEffects = withMasterEffects,
                    outputSampleRate = outputSampleRate,
                )
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                file.delete()
                complete++
                return summary
            }

            masterSummary = renderEntry("Master.wav", null, "Master", withMasterEffects = true)
            banks.forEach { (bank, padIndices) ->
                renderEntry(
                    "Banks/Bank_${('A'.code + bank).toChar()}.wav",
                    padIndices.toSet(),
                    "Bank ${('A'.code + bank).toChar()}",
                    withMasterEffects = false,
                )
            }
            used.forEach { pad ->
                val bank = ('A'.code + pad / SamplerConfig.PADS_PER_BANK).toChar()
                val number = pad % SamplerConfig.PADS_PER_BANK + 1
                renderEntry(
                    "Pads/Pad_${bank}_${number.toString().padStart(2, '0')}.wav",
                    setOf(pad),
                    "Pad $bank$number",
                    withMasterEffects = false,
                )
            }
            val manifest = buildString {
                append("{\n")
                append("  \"format\": \"ChopLab stems v1\",\n")
                append("  \"project\": \"").append(jsonEscape(safe.projectName)).append("\",\n")
                append("  \"sampleRate\": ").append(outputSampleRate).append(",\n")
                append("  \"channels\": 2,\n")
                append("  \"stemProcessing\": \"pad inserts only; master delay, reverb, compressor and limiter bypassed\",\n")
                append("  \"mode\": \"").append(safe.playbackMode.name).append("\",\n")
                append("  \"bankStems\": ").append(banks.size).append(",\n")
                append("  \"padStems\": ").append(used.size).append("\n")
                append("}\n")
            }
            zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0L })
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        progress?.invoke("完了", 1f)
        return StemSummary(masterSummary, banks.size, used.size)
    }

    private data class FrameResult(
        val dryL: Float = 0f,
        val dryR: Float = 0f,
        val delayL: Float = 0f,
        val delayR: Float = 0f,
        val reverbL: Float = 0f,
        val reverbR: Float = 0f,
    )

    private class Voice(
        pad: PadModel,
        private val outputSampleRate: Int,
        private val velocity: Float,
        val serial: Long,
        private val autoReleaseFrames: Int,
    ) {
        private enum class EnvelopeStage { ATTACK, DECAY, SUSTAIN, RELEASE, DONE }

        private val source: PcmAudio = requireNotNull(pad.audio)
        private val start = pad.startFrame.coerceIn(0, source.frameCount - 1)
        private val end = pad.endFrame.coerceIn(start + 1, source.frameCount)
        private val reverse = pad.reverse
        private val direction = if (reverse) -1 else 1
        private val gain = pad.gain
        private val pan = pad.pan
        private val tone = pad.tone
        private val resonance = pad.resonance
        private val pitchRatio = 2.0.pow(pad.pitchSemitones.toDouble() / 12.0).toFloat()
        private val stretch = pad.timeStretchRatio
        private val sourceRateRatio = source.sampleRate.toFloat() / outputSampleRate
        private val playMode = pad.playMode
        val chokeGroup = pad.chokeGroup
        private val effects = pad.effects
        private val lfo = pad.lfo

        private var envelope = if (pad.adsr.attackMs <= 0.001f) 1f else 0f
        private var envelopeStage = if (pad.adsr.attackMs <= 0.001f) EnvelopeStage.DECAY else EnvelopeStage.ATTACK
        private val attackFrames = max(1, (pad.adsr.attackMs * outputSampleRate / 1000f).toInt())
        private val decayFrames = max(1, (pad.adsr.decayMs * outputSampleRate / 1000f).toInt())
        private val releaseFrames = max(1, (pad.adsr.releaseMs * outputSampleRate / 1000f).toInt())
        private val sustain = pad.adsr.sustainLevel
        private var releaseStep = 1f / releaseFrames

        private val directMode = abs(pad.pitchSemitones) < 0.0001f &&
            abs(stretch - 1f) < 0.0001f &&
            !(lfo.enabled && lfo.target == LfoTarget.PITCH && lfo.depth > 0.0001f)
        private var directPosition = if (reverse) end - 1.0 else start.toDouble()
        private val directStep = sourceRateRatio * direction
        private var outputAge = 0L
        private val maxOutputFrames = max(1L, round((end - start) / sourceRateRatio * stretch).toLong())

        private val grainLength = min(1024L, maxOutputFrames).toInt().coerceIn(32, 1024)
        private val grainHop = max(8, grainLength / 4)
        private var nextGrainAt = 0L
        private var nextGrainSource = if (reverse) end - 1.0 else start.toDouble()
        private val grainSourceHop = direction * grainHop * sourceRateRatio / max(0.25f, stretch)
        private val grainActive = BooleanArray(4)
        private val grainAge = IntArray(4)
        private val grainPosition = DoubleArray(4)

        private var lfoPhase = 0f
        private var sampleHold = 0f
        private var randomState = ((pad.globalIndex + 1) * 0x9e3779b9L xor serial).toInt().let { if (it == 0) 1 else it }

        private var ic1L = 0f
        private var ic2L = 0f
        private var ic1R = 0f
        private var ic2R = 0f
        private var heldL = 0f
        private var heldR = 0f
        private var reductionCounter = 0

        var active = true

        fun noteOff(force: Boolean) {
            if (!active || envelopeStage == EnvelopeStage.RELEASE || envelopeStage == EnvelopeStage.DONE) return
            if (!force && playMode == PadPlayMode.ONE_SHOT) return
            envelopeStage = EnvelopeStage.RELEASE
            releaseStep = envelope / max(1, releaseFrames)
        }

        fun render(bpm: Float, sampleRate: Int): FrameResult {
            if (!active) return FrameResult()
            if (autoReleaseFrames >= 0 && outputAge >= autoReleaseFrames && playMode == PadPlayMode.GATE) noteOff(false)
            if (outputAge >= maxOutputFrames) {
                active = false
                return FrameResult()
            }

            val lfoValue = advanceLfo(bpm, sampleRate)
            val pitchMod = if (lfo.enabled && lfo.target == LfoTarget.PITCH) {
                2.0.pow((lfoValue * lfo.depth * 12f / 12f).toDouble()).toFloat()
            } else 1f
            var (left, right) = renderSource(pitchMod)

            var cutoffTone = tone
            if (lfo.enabled && lfo.target == LfoTarget.FILTER) {
                cutoffTone = (tone + lfoValue * lfo.depth * 0.45f).coerceIn(0f, 1f)
            }
            if (cutoffTone < 0.999f || resonance > 0.001f || (lfo.enabled && lfo.target == LfoTarget.FILTER)) {
                val cutoff = (40f * 500f.pow(cutoffTone)).coerceIn(25f, sampleRate * 0.45f)
                val g = tan(PI * cutoff / sampleRate).toFloat()
                val k = 2f - 1.92f * resonance
                val a1 = 1f / (1f + g * (g + k))
                val a2 = g * a1
                val a3 = g * a2
                val filteredL = filter(left, ic1L, ic2L, a1, a2, a3)
                ic1L = filteredL.second
                ic2L = filteredL.third
                left = filteredL.first
                val filteredR = filter(right, ic1R, ic2R, a1, a2, a3)
                ic1R = filteredR.second
                ic2R = filteredR.third
                right = filteredR.first
            }

            if (effects.drive > 0.0001f) {
                val amount = 1f + effects.drive * 18f
                val normalizer = 1f / max(0.001f, tanh(amount))
                left = tanh(left * amount) * normalizer
                right = tanh(right * amount) * normalizer
            }
            if (reductionCounter <= 0) {
                heldL = left
                heldR = right
                reductionCounter = effects.sampleRateReduction
            }
            reductionCounter--
            if (effects.bitDepth < 16) {
                val levels = ((1 shl (effects.bitDepth - 1)) - 1).toFloat()
                heldL = round(heldL * levels) / levels
                heldR = round(heldR * levels) / levels
            }
            left = heldL
            right = heldR

            var dynamicPan = pan
            var ampMod = 1f
            if (lfo.enabled && lfo.target == LfoTarget.AMP) {
                ampMod = 1f - lfo.depth + lfo.depth * (lfoValue + 1f) * 0.5f
            } else if (lfo.enabled && lfo.target == LfoTarget.PAN) {
                dynamicPan = (pan + lfoValue * lfo.depth).coerceIn(-1f, 1f)
            }
            val envelopeValue = advanceEnvelope()
            val leftPan = sqrt(0.5f * (1f - dynamicPan))
            val rightPan = sqrt(0.5f * (1f + dynamicPan))
            val fadeFrames = min(64L, max(1L, maxOutputFrames / 4L))
            val startFade = (outputAge.toFloat() / fadeFrames).coerceIn(0f, 1f)
            val endFade = ((maxOutputFrames - outputAge).toFloat() / fadeFrames).coerceIn(0f, 1f)
            val level = gain * velocity * envelopeValue * ampMod * min(startFade, endFade)
            left *= level * leftPan
            right *= level * rightPan
            outputAge++
            return FrameResult(
                dryL = left,
                dryR = right,
                delayL = left * effects.delaySend,
                delayR = right * effects.delaySend,
                reverbL = left * effects.reverbSend,
                reverbR = right * effects.reverbSend,
            )
        }

        private fun renderSource(pitchMod: Float): Pair<Float, Float> {
            if (directMode) {
                val result = Pair(sample(directPosition, 0), sample(directPosition, 1))
                directPosition += directStep
                return result
            }
            for (i in grainActive.indices) if (grainActive[i] && grainAge[i] >= grainLength) grainActive[i] = false
            while (outputAge >= nextGrainAt && nextGrainAt < maxOutputFrames) {
                if (nextGrainSource < start || nextGrainSource >= end) {
                    nextGrainAt = maxOutputFrames
                    break
                }
                var slot = grainActive.indexOfFirst { !it }
                if (slot < 0) slot = grainAge.indices.maxByOrNull { grainAge[it] } ?: 0
                grainActive[slot] = true
                grainAge[slot] = 0
                grainPosition[slot] = nextGrainSource
                nextGrainAt += grainHop
                nextGrainSource += grainSourceHop
            }
            var left = 0f
            var right = 0f
            var norm = 0f
            val increment = direction * sourceRateRatio * pitchRatio * pitchMod
            for (i in grainActive.indices) {
                if (!grainActive[i]) continue
                val phase = grainAge[i].toFloat() / max(1, grainLength - 1)
                val window = 0.5f - 0.5f * cos((2.0 * PI * phase)).toFloat()
                val position = grainPosition[i]
                if (position >= start && position < end) {
                    left += sample(position, 0) * window
                    right += sample(position, 1) * window
                    norm += window
                }
                grainPosition[i] = grainPosition[i] + increment.toDouble()
                grainAge[i]++
                if (grainAge[i] >= grainLength) grainActive[i] = false
            }
            return if (norm > 1e-8f) Pair(left / norm, right / norm) else Pair(0f, 0f)
        }

        private fun sample(frame: Double, channel: Int): Float {
            if (frame < 0 || frame >= source.frameCount) return 0f
            val lower = floor(frame).toInt().coerceIn(0, source.frameCount - 1)
            val upper = min(lower + 1, source.frameCount - 1)
            val fraction = (frame - lower).toFloat()
            val first = source.sampleAt(lower, if (source.channelCount == 1) 0 else channel) / 32768f
            val second = source.sampleAt(upper, if (source.channelCount == 1) 0 else channel) / 32768f
            return first + (second - first) * fraction
        }

        private fun advanceEnvelope(): Float {
            when (envelopeStage) {
                EnvelopeStage.ATTACK -> {
                    envelope += 1f / attackFrames
                    if (envelope >= 1f) { envelope = 1f; envelopeStage = EnvelopeStage.DECAY }
                }
                EnvelopeStage.DECAY -> {
                    envelope -= (1f - sustain) / decayFrames
                    if (envelope <= sustain) { envelope = sustain; envelopeStage = EnvelopeStage.SUSTAIN }
                }
                EnvelopeStage.SUSTAIN -> envelope = sustain
                EnvelopeStage.RELEASE -> {
                    envelope -= releaseStep
                    if (envelope <= 0.00001f) { envelope = 0f; envelopeStage = EnvelopeStage.DONE; active = false }
                }
                EnvelopeStage.DONE -> { envelope = 0f; active = false }
            }
            return envelope
        }

        private fun advanceLfo(bpm: Float, sampleRate: Int): Float {
            if (!lfo.enabled || lfo.depth <= 0.0001f) return 0f
            val value = when (lfo.waveform) {
                LfoWaveform.SINE -> sin(2.0 * PI * lfoPhase).toFloat()
                LfoWaveform.TRIANGLE -> 1f - 4f * abs(lfoPhase - 0.5f)
                LfoWaveform.SQUARE -> if (lfoPhase < 0.5f) 1f else -1f
                LfoWaveform.SAW -> 2f * lfoPhase - 1f
                LfoWaveform.SAMPLE_HOLD -> sampleHold
            }
            val rate = if (lfo.tempoSync) {
                bpm.coerceIn(30f, 300f) / (60f * lfo.syncDivision.beats.coerceIn(0.0625f, 4f))
            } else lfo.rateHz.coerceIn(0.05f, 30f)
            lfoPhase += rate / sampleRate
            if (lfoPhase >= 1f) {
                lfoPhase -= floor(lfoPhase.toDouble()).toFloat()
                if (lfo.waveform == LfoWaveform.SAMPLE_HOLD) {
                    randomState = xorshift(randomState)
                    sampleHold = ((randomState ushr 8) and 0xFFFF) / 32767.5f - 1f
                }
            }
            return value
        }

        private fun filter(input: Float, ic1: Float, ic2: Float, a1: Float, a2: Float, a3: Float): Triple<Float, Float, Float> {
            val v3 = input - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            return Triple(v2, 2f * v1 - ic1, 2f * v2 - ic2)
        }
    }

    private class DelayBus(sampleRate: Int) {
        private val left = FloatArray(sampleRate * 8)
        private val right = FloatArray(sampleRate * 8)
        private var write = 0

        fun process(inputL: Float, inputR: Float, p: MasterEffects, bpm: Float): Pair<Float, Float> {
            if (!p.delayEnabled || p.delayMix <= 0.0001f) {
                left[write] = 0f; right[write] = 0f; write = (write + 1) % left.size
                return Pair(0f, 0f)
            }
            val delayFrames = (left.size.coerceAtMost((60f / bpm.coerceIn(30f, 300f) * p.delayBeats * left.size / 8f).toInt()))
                .coerceIn(1, left.size - 1)
            val read = (write - delayFrames + left.size) % left.size
            val delayedL = left[read]
            val delayedR = right[read]
            if (p.pingPong) {
                left[write] = inputL + delayedR * p.delayFeedback
                right[write] = inputR + delayedL * p.delayFeedback
            } else {
                left[write] = inputL + delayedL * p.delayFeedback
                right[write] = inputR + delayedR * p.delayFeedback
            }
            write = (write + 1) % left.size
            return Pair(delayedL * p.delayMix, delayedR * p.delayMix)
        }
    }

    private class ReverbBus(sampleRate: Int) {
        private val delaysL = intArrayOf(1116, 1188, 1277, 1356).map { max(1, it * sampleRate / 44_100) }
        private val delaysR = delaysL.map { it + max(1, 23 * sampleRate / 44_100) }
        private val buffersL = delaysL.map { FloatArray(it) }
        private val buffersR = delaysR.map { FloatArray(it) }
        private val indicesL = IntArray(4)
        private val indicesR = IntArray(4)
        private val dampL = FloatArray(4)
        private val dampR = FloatArray(4)

        fun process(inputL: Float, inputR: Float, p: MasterEffects): Pair<Float, Float> {
            if (!p.reverbEnabled || p.reverbMix <= 0.0001f) return Pair(0f, 0f)
            val feedback = 0.68f + p.reverbSize * 0.26f
            val damping = p.reverbDamping * 0.8f
            var sumL = 0f
            var sumR = 0f
            for (i in 0 until 4) {
                val outL = buffersL[i][indicesL[i]]
                val outR = buffersR[i][indicesR[i]]
                dampL[i] += (outL - dampL[i]) * (1f - damping)
                dampR[i] += (outR - dampR[i]) * (1f - damping)
                buffersL[i][indicesL[i]] = inputL + dampL[i] * feedback
                buffersR[i][indicesR[i]] = inputR + dampR[i] * feedback
                indicesL[i] = (indicesL[i] + 1) % buffersL[i].size
                indicesR[i] = (indicesR[i] + 1) % buffersR[i].size
                sumL += outL
                sumR += outR
            }
            return Pair(sumL * 0.25f * p.reverbMix, sumR * 0.25f * p.reverbMix)
        }
    }

    private class MasterProcessor(sampleRate: Int) {
        private val attackCoeff = kotlin.math.exp(-1f / (0.003f * sampleRate))
        private val releaseCoeff = kotlin.math.exp(-1f / (0.12f * sampleRate))
        private var envelope = 0f

        fun process(leftInput: Float, rightInput: Float, p: MasterEffects): Pair<Float, Float> {
            var left = leftInput
            var right = rightInput
            if (p.compressorEnabled) {
                val detector = max(abs(left), abs(right))
                val coefficient = if (detector > envelope) attackCoeff else releaseCoeff
                envelope = coefficient * envelope + (1f - coefficient) * detector
                val threshold = 10.0.pow(p.compressorThresholdDb / 20.0).toFloat()
                if (envelope > threshold && envelope > 1e-8f) {
                    val inputDb = 20f * log10(envelope)
                    val outputDb = p.compressorThresholdDb + (inputDb - p.compressorThresholdDb) / p.compressorRatio
                    val reduction = 10.0.pow((outputDb - inputDb) / 20.0).toFloat()
                    left *= reduction
                    right *= reduction
                }
            }
            if (p.masterDrive > 0.0001f) {
                val amount = 1f + p.masterDrive * 12f
                val normalizer = 1f / max(0.001f, tanh(amount))
                left = tanh(left * amount) * normalizer
                right = tanh(right * amount) * normalizer
            }
            return Pair(softClip(left * p.masterGain), softClip(right * p.masterGain))
        }
    }

    private fun stepFrames(sampleRate: Int, bpm: Float, swing: Float, step: Int): Double {
        val straight = sampleRate * 60.0 / bpm.coerceIn(30f, 300f) / 4.0
        val longRatio = swing.coerceIn(50f, 75f) / 50.0
        return if (step % 2 == 0) straight * longRatio else straight * (2.0 - longRatio)
    }

    private fun softClip(value: Float): Float = value / (1f + abs(value))
    private fun floatToPcm16(value: Float): Short =
        (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    private fun xorshift(seed: Int): Int {
        var value = if (seed == 0) 0x6d2b79f5 else seed
        value = value xor (value shl 13)
        value = value xor (value ushr 17)
        value = value xor (value shl 5)
        return value
    }

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private const val BLOCK_FRAMES = 2_048
    private const val MAX_POLYPHONY = 96
}
