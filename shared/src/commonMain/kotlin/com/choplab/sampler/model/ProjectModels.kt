package com.choplab.sampler.model

import kotlin.jvm.JvmInline

object ProjectSchema {
    const val CURRENT_VERSION = 1
}

object ProjectLimits {
    const val MAX_AUDIO_ASSETS = 256
    const val MAX_IDENTIFIER_CHARS = 128
    const val MAX_AUDIO_ASSET_ID_CHARS = MAX_IDENTIFIER_CHARS
    const val MAX_ASSET_NAME_CHARS = 240
    const val MAX_PROJECT_NAME_CHARS = 120
    const val MAX_PATTERN_NAME_CHARS = 120
    const val MAX_SAMPLE_RATE = 192_000
    const val MAX_CHANNEL_COUNT = 2
    const val MAX_FRAMES_PER_ASSET = 115_200_000L
    const val MAX_TOTAL_PCM_BYTES = 512L * 1024L * 1024L
    const val MAX_PATTERNS = 128
    const val MAX_PATTERN_STEPS = 64
    const val MAX_PATTERN_EVENTS = SamplerConfig.PAD_COUNT * MAX_PATTERN_STEPS
    const val MAX_SONG_SECTIONS = 512
}

@JvmInline
value class AudioAssetId(val value: String) {
    init {
        require(value.isNotBlank()) { "Audio asset ID must not be blank" }
        require(value.length <= ProjectLimits.MAX_AUDIO_ASSET_ID_CHARS) { "Audio asset ID is too long" }
        require(!value.startsWith('.') && ".." !in value) { "Audio asset ID contains an unsafe path segment" }
        require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
            "Audio asset ID contains unsafe characters"
        }
    }

    override fun toString(): String = value
}

/**
 * Immutable PCM facade. Input and output arrays are copied so project/history
 * snapshots cannot observe mutation through a shared [ShortArray].
 */
class PcmBuffer private constructor(
    private val interleavedSamples: ShortArray,
    val sampleRate: Int,
    val channelCount: Int,
) {
    init {
        require(sampleRate in 8_000..ProjectLimits.MAX_SAMPLE_RATE) { "Unsupported sample rate" }
        require(channelCount in 1..ProjectLimits.MAX_CHANNEL_COUNT) { "Unsupported channel count" }
        require(interleavedSamples.size % channelCount == 0) { "PCM data contains a partial frame" }
        require(frameCount.toLong() <= ProjectLimits.MAX_FRAMES_PER_ASSET) { "PCM asset exceeds frame limit" }
        require(interleavedSamples.size.toLong() * Short.SIZE_BYTES <= ProjectLimits.MAX_TOTAL_PCM_BYTES) {
            "PCM asset exceeds byte limit"
        }
    }

    val frameCount: Int
        get() = interleavedSamples.size / channelCount

    fun sampleAt(frame: Int, channel: Int): Short {
        require(frame in 0 until frameCount) { "Frame is out of bounds" }
        require(channel in 0 until channelCount) { "Channel is out of bounds" }
        return interleavedSamples[frame * channelCount + channel]
    }

    fun copyInterleaved(): ShortArray = interleavedSamples.copyOf()

    companion object {
        fun fromInterleaved(samples: ShortArray, sampleRate: Int, channelCount: Int): PcmBuffer {
            require(sampleRate in 8_000..ProjectLimits.MAX_SAMPLE_RATE) { "Unsupported sample rate" }
            require(channelCount in 1..ProjectLimits.MAX_CHANNEL_COUNT) { "Unsupported channel count" }
            require(samples.size % channelCount == 0) { "PCM data contains a partial frame" }
            require(samples.size.toLong() / channelCount <= ProjectLimits.MAX_FRAMES_PER_ASSET) {
                "PCM asset exceeds frame limit"
            }
            require(samples.size.toLong() * Short.SIZE_BYTES <= ProjectLimits.MAX_TOTAL_PCM_BYTES) {
                "PCM asset exceeds byte limit"
            }
            return PcmBuffer(samples.copyOf(), sampleRate, channelCount)
        }
    }
}

data class AudioAssetMetadata(
    val id: AudioAssetId,
    val name: String,
    val sampleRate: Int,
    val channelCount: Int,
    val frameCount: Long,
) {
    init {
        require(name.isNotBlank() && name.length <= ProjectLimits.MAX_ASSET_NAME_CHARS) {
            "Audio asset name is invalid"
        }
        require(sampleRate in 8_000..ProjectLimits.MAX_SAMPLE_RATE) { "Unsupported sample rate" }
        require(channelCount in 1..ProjectLimits.MAX_CHANNEL_COUNT) { "Unsupported channel count" }
        require(frameCount in 0..ProjectLimits.MAX_FRAMES_PER_ASSET) { "Audio asset exceeds frame limit" }
    }

    val pcmByteCount: Long
        get() = frameCount * channelCount * Short.SIZE_BYTES
}

data class ProjectPad(
    val globalIndex: Int,
    val assetId: AudioAssetId? = null,
    val startFrame: Long = 0,
    val endFrame: Long = 0,
    val pitchSemitones: Float = 0f,
    val timeStretchRatio: Float = 1f,
    val tone: Float = 1f,
    val gain: Float = 0.9f,
    val pan: Float = 0f,
    val reverse: Boolean = false,
    val playMode: PadPlayMode = PadPlayMode.ONE_SHOT,
    val chokeGroup: Int = 0,
) {
    init {
        require(globalIndex in 0 until SamplerConfig.PAD_COUNT) { "Pad index is out of bounds" }
        require(startFrame >= 0 && endFrame >= startFrame) { "Pad frame range is invalid" }
        if (assetId == null) {
            require(startFrame == 0L && endFrame == 0L) { "Unassigned pad must have an empty range" }
        } else {
            require(endFrame > startFrame) { "Assigned pad must have a non-empty range" }
        }
        require(pitchSemitones in -24f..24f) { "Pitch is out of bounds" }
        require(timeStretchRatio in 0.25f..4f) { "Time stretch is out of bounds" }
        require(tone in 0f..1f) { "Tone is out of bounds" }
        require(gain in 0f..1.5f) { "Gain is out of bounds" }
        require(pan in -1f..1f) { "Pan is out of bounds" }
        require(chokeGroup in 0..4) { "Choke group is out of bounds" }
    }

    val isAssigned: Boolean
        get() = assetId != null
}

data class PatternStep(
    val padIndex: Int,
    val stepIndex: Int,
    val velocity: Float = 1f,
) {
    init {
        require(padIndex in 0 until SamplerConfig.PAD_COUNT) { "Pattern pad is out of bounds" }
        require(stepIndex in 0 until ProjectLimits.MAX_PATTERN_STEPS) { "Pattern step is out of bounds" }
        require(velocity in 0f..1f) { "Velocity is out of bounds" }
    }
}

data class ProjectPattern(
    val id: String,
    val name: String,
    val lengthSteps: Int,
    val events: List<PatternStep> = emptyList(),
) {
    init {
        requirePortableIdentifier(id, "Pattern ID")
        require(name.isNotBlank() && name.length <= ProjectLimits.MAX_PATTERN_NAME_CHARS) {
            "Pattern name is invalid"
        }
        require(lengthSteps in setOf(16, 32, 64)) { "Pattern length must be 16, 32, or 64 steps" }
        require(events.size <= ProjectLimits.MAX_PATTERN_EVENTS) { "Pattern contains too many events" }
        require(events.all { it.stepIndex < lengthSteps }) { "Pattern event exceeds pattern length" }
        require(events.distinctBy { it.padIndex to it.stepIndex }.size == events.size) {
            "Pattern contains duplicate pad/step events"
        }
    }
}

data class SongSection(
    val patternId: String,
    val repeatCount: Int = 1,
) {
    init {
        requirePortableIdentifier(patternId, "Song pattern ID")
        require(repeatCount in 1..128) { "Song repeat count is out of bounds" }
    }
}

data class ProjectSnapshot(
    val schemaVersion: Int = ProjectSchema.CURRENT_VERSION,
    val projectId: String,
    val name: String,
    val audioAssets: List<AudioAssetMetadata> = emptyList(),
    val pads: List<ProjectPad> = List(SamplerConfig.PAD_COUNT) { ProjectPad(it) },
    val patterns: List<ProjectPattern> = emptyList(),
    val songSections: List<SongSection> = emptyList(),
    val tempoBpm: Float = 92f,
    val swingPercent: Float = 54f,
) {
    init {
        require(schemaVersion in 1..ProjectSchema.CURRENT_VERSION) { "Unsupported project schema version" }
        requirePortableIdentifier(projectId, "Project ID")
        require(name.isNotBlank() && name.length <= ProjectLimits.MAX_PROJECT_NAME_CHARS) {
            "Project name is invalid"
        }
        require(audioAssets.size <= ProjectLimits.MAX_AUDIO_ASSETS) { "Too many audio assets" }
        require(audioAssets.distinctBy { it.id }.size == audioAssets.size) { "Duplicate audio asset ID" }
        require(audioAssets.sumOf { it.pcmByteCount } <= ProjectLimits.MAX_TOTAL_PCM_BYTES) {
            "Project PCM total exceeds byte limit"
        }
        require(pads.size == SamplerConfig.PAD_COUNT) { "Project must contain ${SamplerConfig.PAD_COUNT} pads" }
        require(pads.map { it.globalIndex } == (0 until SamplerConfig.PAD_COUNT).toList()) {
            "Project pads must be ordered and contiguous"
        }
        val assetIds = audioAssets.mapTo(mutableSetOf()) { it.id }
        require(pads.all { it.assetId == null || it.assetId in assetIds }) { "Pad references missing audio asset" }
        val assetsById = audioAssets.associateBy { it.id }
        require(
            pads.all { pad ->
                pad.assetId == null || pad.endFrame <= requireNotNull(assetsById[pad.assetId]).frameCount
            },
        ) { "Pad range exceeds referenced audio asset" }
        require(patterns.size <= ProjectLimits.MAX_PATTERNS) { "Too many patterns" }
        require(patterns.distinctBy { it.id }.size == patterns.size) { "Duplicate pattern ID" }
        require(songSections.size <= ProjectLimits.MAX_SONG_SECTIONS) { "Song is too long" }
        val patternIds = patterns.mapTo(mutableSetOf()) { it.id }
        require(songSections.all { it.patternId in patternIds }) { "Song references missing pattern" }
        require(tempoBpm in 40f..240f) { "Tempo is out of bounds" }
        require(swingPercent in 50f..75f) { "Swing is out of bounds" }
    }
}

object LegacyProjectAdapter {
    fun toSnapshot(state: SamplerUiState, projectName: String = "Untitled"): ProjectSnapshot {
        val legacyAudio = linkedMapOf<Long, PcmAudio>()
        state.currentAudio?.let { legacyAudio[it.id] = it }
        state.pads.mapNotNull { it.audio }.forEach { legacyAudio[it.id] = it }

        val assetIds = legacyAudio.keys.associateWith { AudioAssetId("legacy-$it") }
        val assets = legacyAudio.values.map { audio ->
            AudioAssetMetadata(
                id = requireNotNull(assetIds[audio.id]),
                name = audio.name.ifBlank { "Legacy audio" },
                sampleRate = audio.sampleRate,
                channelCount = 1,
                frameCount = audio.frameCount.toLong(),
            )
        }
        val pads = state.pads.map { pad ->
            if (pad.isAssigned) {
                ProjectPad(
                    globalIndex = pad.globalIndex,
                    assetId = requireNotNull(assetIds[requireNotNull(pad.audio).id]),
                    startFrame = pad.startFrame.toLong(),
                    endFrame = pad.endFrame.toLong(),
                    pitchSemitones = pad.pitchSemitones.coerceIn(-24f, 24f),
                    tone = pad.tone.coerceIn(0f, 1f),
                    gain = pad.gain.coerceIn(0f, 1.5f),
                    reverse = pad.reverse,
                    playMode = pad.playMode,
                    chokeGroup = pad.chokeGroup.coerceIn(0, 4),
                )
            } else {
                ProjectPad(pad.globalIndex)
            }
        }
        val arrangement = state.materializedPatternArrangement()
        val patternIds = listOf("pattern-a", "pattern-b")
        val patterns = arrangement.storedStepsBySlot.mapIndexed { slot, steps ->
            val events = steps.mapNotNull { key ->
                val padIndex = key / SamplerConfig.STEP_COUNT
                val stepIndex = key % SamplerConfig.STEP_COUNT
                if (padIndex in 0 until SamplerConfig.PAD_COUNT && stepIndex in 0 until SamplerConfig.STEP_COUNT) {
                    PatternStep(padIndex, stepIndex)
                } else {
                    null
                }
            }.distinct().sortedWith(compareBy(PatternStep::stepIndex, PatternStep::padIndex))
            ProjectPattern(
                id = patternIds[slot],
                name = "Pattern ${patternVariationLabel(slot)}",
                lengthSteps = SamplerConfig.STEP_COUNT,
                events = events,
            )
        }

        return ProjectSnapshot(
            projectId = "legacy-${state.currentAudio?.id ?: 0L}",
            name = projectName.ifBlank { "Untitled" }.take(ProjectLimits.MAX_PROJECT_NAME_CHARS),
            audioAssets = assets,
            pads = pads,
            patterns = patterns,
            songSections = if (arrangement.songModeEnabled) {
                arrangement.songSections.map { slot -> SongSection(patternIds[slot]) }
            } else {
                emptyList()
            },
            tempoBpm = state.bpm.coerceIn(40f, 240f),
            swingPercent = state.swing.coerceIn(50f, 75f),
        )
    }
}

private fun requirePortableIdentifier(value: String, label: String) {
    require(value.isNotBlank() && value.length <= ProjectLimits.MAX_IDENTIFIER_CHARS) { "$label is invalid" }
    require(!value.startsWith('.') && ".." !in value) { "$label contains an unsafe path segment" }
    require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
        "$label contains unsafe characters"
    }
}
