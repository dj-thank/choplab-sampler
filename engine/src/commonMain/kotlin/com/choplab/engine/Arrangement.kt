package com.choplab.engine

import kotlin.math.PI
import kotlin.math.cos

/** Immutable direct-PCM placement; no PAD identity or musical-grid quantization is involved. */
class ArrangementClip(
    val id: String,
    val asset: PcmAsset,
    val timelineStartFrame: Long,
    val sourceStartFrame: Int = 0,
    val sourceEndFrame: Int = asset.frameCount,
    val gain: Float = 1f,
    val pan: Float = 0f,
    val trackIndex: Int = 0,
) {
    val durationFrames: Long = sourceEndFrame.toLong() - sourceStartFrame
    val timelineEndFrame: Long = timelineStartFrame + durationFrames
    init {
        require(id.isNotBlank() && id.length <= 256)
        require(sourceStartFrame >= 0 && sourceEndFrame <= asset.frameCount && sourceEndFrame > sourceStartFrame)
        require(timelineStartFrame in 0..Arrangement.MAX_DURATION_FRAMES &&
            durationFrames > 0 && timelineEndFrame <= Arrangement.MAX_DURATION_FRAMES)
        require(gain.isFinite() && gain in 0f..8f && pan.isFinite() && pan in -1f..1f)
        require(trackIndex in 0 until Arrangement.MAX_TRACKS)
    }
    internal val leftGain = gain * if (pan > 0f) cos(pan * PI / 2).toFloat() else 1f
    internal val rightGain = gain * if (pan < 0f) cos(-pan * PI / 2).toFloat() else 1f
}

/**
 * Compiled and validated on the control/worker thread. Its declared duration preserves silence.
 * More than 32 simultaneous clips rejects this entire immutable document; none are dropped.
 */
class Arrangement(
    clips: List<ArrangementClip>,
    val durationFrames: Long = clips.maxOfOrNull { it.timelineEndFrame } ?: 0,
) {
    private val entries: Array<ArrangementClip>
    val clipCount: Int get() = entries.size
    val maximumOverlap: Int
    init {
        require(clips.size <= MAX_CLIPS)
        require(durationFrames in 0..MAX_DURATION_FRAMES)
        val ids = HashSet<String>()
        val boundaries = LongArray(clips.size * 2)
        for (i in clips.indices) {
            val clip = clips[i]
            require(ids.add(clip.id)) { "Duplicate arrangement clip ID" }
            require(clip.timelineEndFrame <= durationFrames) { "Declared arrangement duration truncates a clip" }
            // At a tie, exclusive ends precede inclusive starts.
            boundaries[i * 2] = clip.timelineStartFrame * 2 + 1
            boundaries[i * 2 + 1] = clip.timelineEndFrame * 2
        }
        boundaries.sort()
        var overlap = 0
        var maximum = 0
        for (boundary in boundaries) {
            overlap += if (boundary and 1L == 1L) 1 else -1
            maximum = maxOf(maximum, overlap)
            require(maximum <= MAX_SIMULTANEOUS_CLIPS) { "Arrangement exceeds 32 simultaneous clips" }
        }
        maximumOverlap = maximum
        entries = clips.sortedWith(compareBy<ArrangementClip> { it.timelineStartFrame }
            .thenBy { it.trackIndex }.thenBy { it.id }).toTypedArray()
    }
    fun clip(index: Int): ArrangementClip = entries[index]

    companion object {
        const val MAX_CLIPS = 1024
        const val MAX_TRACKS = 16
        const val MAX_SIMULTANEOUS_CLIPS = 32
        const val MAX_DURATION_FRAMES = 48_000L * 60 * 30
    }
}

/** Bounded mixer. Sorted active indices make summation independent of seek/partition history. */
internal class ArrangementMixer {
    private var arrangement: Arrangement? = null
    private val active = IntArray(Arrangement.MAX_SIMULTANEOUS_CLIPS)
    private var nextClip = 0
    var activeCount = 0
        private set
    var outputLeft = 0.0
        private set
    var outputRight = 0.0
        private set

    fun restore(value: Arrangement?, sequenceFrame: Long): Boolean {
        arrangement = value
        activeCount = 0
        nextClip = 0
        outputLeft = 0.0
        outputRight = 0.0
        if (value == null) return true
        while (nextClip < value.clipCount && value.clip(nextClip).timelineStartFrame <= sequenceFrame) {
            if (value.clip(nextClip).timelineEndFrame > sequenceFrame) {
                if (activeCount == active.size) return false
                active[activeCount++] = nextClip
            }
            nextClip++
        }
        return true
    }

    fun render(sequenceFrame: Long): Boolean {
        outputLeft = 0.0
        outputRight = 0.0
        val value = arrangement ?: return true
        var i = 0
        while (i < activeCount) {
            if (value.clip(active[i]).timelineEndFrame <= sequenceFrame) {
                for (j in i until activeCount - 1) active[j] = active[j + 1]
                activeCount--
            } else i++
        }
        while (nextClip < value.clipCount && value.clip(nextClip).timelineStartFrame <= sequenceFrame) {
            if (value.clip(nextClip).timelineEndFrame > sequenceFrame) {
                if (activeCount == active.size) return false
                active[activeCount++] = nextClip
            }
            nextClip++
        }
        for (index in 0 until activeCount) {
            val clip = value.clip(active[index])
            val sourceFrame = (clip.sourceStartFrame + sequenceFrame - clip.timelineStartFrame).toInt()
            outputLeft += clip.asset.at(sourceFrame, 0) * clip.leftGain.toDouble()
            outputRight += clip.asset.at(sourceFrame, 1) * clip.rightGain.toDouble()
        }
        return true
    }
}
