package com.choplab.sampler.persistence

import android.content.ContentResolver
import android.net.Uri
import com.choplab.sampler.audio.WavCodec
import com.choplab.sampler.model.AdsrEnvelope
import com.choplab.sampler.model.LfoSettings
import com.choplab.sampler.model.LfoSyncDivision
import com.choplab.sampler.model.LfoTarget
import com.choplab.sampler.model.LfoWaveform
import com.choplab.sampler.model.MasterEffects
import com.choplab.sampler.model.MidiCcMapping
import com.choplab.sampler.model.MidiCcTarget
import com.choplab.sampler.model.PadEffects
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PatternModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.PlaybackMode
import com.choplab.sampler.model.ProjectSnapshot
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SongSection
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Versioned .choplab project archive.
 *
 * The archive contains project.json plus lossless PCM-16 WAV assets. Audio buffers are shared by
 * ID so the same source assigned to many pads is written only once.
 */
class ProjectArchive(private val resolver: ContentResolver) {
    fun save(uri: Uri, snapshot: ProjectSnapshot) {
        resolver.openOutputStream(uri, "w")?.use { save(it, snapshot) }
            ?: error("保存先を開けません")
    }

    fun load(uri: Uri): ProjectSnapshot = resolver.openInputStream(uri)?.use(::load)
        ?: error("プロジェクトを開けません")

    fun save(file: File, snapshot: ProjectSnapshot) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { save(it, snapshot) }
    }

    fun load(file: File): ProjectSnapshot = FileInputStream(file).use(::load)

    fun save(output: OutputStream, snapshot: ProjectSnapshot) {
        val safe = snapshot.sanitized()
        val pool = safe.audioPool()
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putDeterministicEntry("project.json")
            zip.write(toJson(safe, pool).toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            pool.toSortedMap().forEach { (id, audio) ->
                zip.putDeterministicEntry("audio/$id.wav")
                WavCodec.writePcm16(zip, audio)
                zip.closeEntry()
            }
        }
    }

    fun load(input: InputStream): ProjectSnapshot {
        var manifest: JSONObject? = null
        val audioById = linkedMapOf<Long, PcmAudio>()
        var totalInterleavedSamples = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                when {
                    entry.name == "project.json" -> {
                        val bytes = zip.readBytesBounded(MAX_MANIFEST_BYTES)
                        manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                    }
                    entry.name.startsWith("audio/") && entry.name.endsWith(".wav") -> {
                        require(audioById.size < MAX_AUDIO_ASSETS) { "プロジェクト内の音声数が多すぎます" }
                        val id = entry.name.substringAfter("audio/").substringBeforeLast(".wav").toLongOrNull()
                            ?: error("プロジェクト内の音声IDが不正です: ${entry.name}")
                        require(id >= 0L && id !in audioById) { "プロジェクト内の音声IDが重複または不正です: $id" }
                        val decoded = WavCodec.readPcm16(
                            input = zip,
                            name = entry.name.substringAfterLast('/'),
                            id = id,
                            maxFrames = MAX_AUDIO_FRAMES,
                        )
                        totalInterleavedSamples += decoded.samples.size.toLong()
                        require(totalInterleavedSamples <= MAX_TOTAL_INTERLEAVED_SAMPLES) {
                            "プロジェクト内の音声データが大きすぎます"
                        }
                        audioById[id] = decoded
                    }
                }
                zip.closeEntry()
            }
        }
        val json = manifest ?: error("project.jsonがありません")
        return fromJson(json, audioById).sanitized()
    }

    private fun toJson(snapshot: ProjectSnapshot, audioPool: Map<Long, PcmAudio>): JSONObject =
        JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("app", "ChopLab")
            put("projectName", snapshot.projectName)
            put("currentAudioId", snapshot.currentAudio?.id ?: JSONObject.NULL)
            put("rangeStartFrame", snapshot.rangeStartFrame)
            put("rangeEndFrame", snapshot.rangeEndFrame)
            put("sliceMarkers", JSONArray(snapshot.sliceMarkers))
            put("activeSliceIndex", snapshot.activeSliceIndex ?: JSONObject.NULL)
            put("selectedBank", snapshot.selectedBank)
            put("selectedPad", snapshot.selectedPad)
            put("autoNextPad", snapshot.autoNextPad)
            put("selectedPattern", snapshot.selectedPattern)
            put("sequencerPage", snapshot.sequencerPage)
            put("playbackMode", snapshot.playbackMode.name)
            put("bpm", snapshot.bpm.toDouble())
            put("swing", snapshot.swing.toDouble())
            put("midiChannel", snapshot.midiChannel)
            put("midiBaseNote", snapshot.midiBaseNote)
            put("midiClockSync", snapshot.midiClockSync)

            put("audio", JSONArray().apply {
                audioPool.toSortedMap().values.forEach { audio ->
                    put(JSONObject().apply {
                        put("id", audio.id)
                        put("name", audio.name)
                        put("sampleRate", audio.sampleRate)
                        put("channelCount", audio.channelCount)
                        put("frameCount", audio.frameCount)
                        put("file", "audio/${audio.id}.wav")
                    })
                }
            })
            put("pads", JSONArray().apply { snapshot.pads.forEach { put(it.toJson()) } })
            put("patterns", JSONArray().apply { snapshot.patterns.forEach { put(it.toJson()) } })
            put("song", JSONArray().apply { snapshot.songSections.forEach { put(it.toJson()) } })
            put("masterEffects", snapshot.masterEffects.toJson())
            put("midiCcMappings", JSONArray().apply {
                snapshot.midiCcMappings.forEach { mapping ->
                    put(JSONObject().apply {
                        put("channel", mapping.channel)
                        put("cc", mapping.cc)
                        put("target", mapping.target.name)
                    })
                }
            })
        }

    private fun fromJson(root: JSONObject, loadedAudio: Map<Long, PcmAudio>): ProjectSnapshot {
        val version = root.optInt("schemaVersion", 1)
        require(version in 1..SCHEMA_VERSION) {
            "このプロジェクト形式は新しすぎます (schema $version)"
        }
        val names = mutableMapOf<Long, String>()
        root.optJSONArray("audio")?.forEachObject { item ->
            names[item.optLong("id", -1L)] = item.optString("name", "Sample")
        }
        val audio = loadedAudio.mapValues { (id, value) ->
            value.copy(name = names[id] ?: value.name)
        }
        fun audioById(value: Long): PcmAudio? = if (value < 0L) null else audio[value]

        val pads = mutableListOf<PadModel>()
        root.optJSONArray("pads")?.forEachObject { item ->
            val index = item.optInt("globalIndex", pads.size)
            val source = audioById(item.optLong("audioId", -1L))
            pads += PadModel(
                globalIndex = index,
                audio = source,
                startFrame = item.optInt("startFrame", 0),
                endFrame = item.optInt("endFrame", source?.frameCount ?: 0),
                pitchSemitones = item.optDouble("pitchSemitones", 0.0).toFloat(),
                timeStretchRatio = item.optDouble("timeStretchRatio", 1.0).toFloat(),
                tone = item.optDouble("tone", 1.0).toFloat(),
                resonance = item.optDouble("resonance", 0.08).toFloat(),
                gain = item.optDouble("gain", 0.9).toFloat(),
                pan = item.optDouble("pan", 0.0).toFloat(),
                reverse = item.optBoolean("reverse", false),
                playMode = enumValue(item.optString("playMode"), PadPlayMode.ONE_SHOT),
                chokeGroup = item.optInt("chokeGroup", 0),
                adsr = item.optJSONObject("adsr").toAdsr(),
                lfo = item.optJSONObject("lfo").toLfo(),
                effects = item.optJSONObject("effects").toPadEffects(),
            )
        }

        val patterns = mutableListOf<PatternModel>()
        root.optJSONArray("patterns")?.forEachObject { item ->
            val steps = mutableSetOf<Int>()
            item.optJSONArray("activeSteps")?.forEachInt(steps::add)
            patterns += PatternModel(
                index = item.optInt("index", patterns.size),
                name = item.optString("name", "PATTERN ${patterns.size + 1}"),
                lengthSteps = item.optInt("lengthSteps", 16),
                activeSteps = steps,
            )
        }

        val song = mutableListOf<SongSection>()
        root.optJSONArray("song")?.forEachObject { item ->
            song += SongSection(
                id = item.optLong("id", System.nanoTime()),
                patternIndex = item.optInt("patternIndex", 0),
                repeats = item.optInt("repeats", 1),
            )
        }

        val mappings = mutableListOf<MidiCcMapping>()
        root.optJSONArray("midiCcMappings")?.forEachObject { item ->
            mappings += MidiCcMapping(
                channel = item.optInt("channel", -1),
                cc = item.optInt("cc", 0),
                target = enumValue(item.optString("target"), MidiCcTarget.SELECTED_PAD_TONE),
            )
        }

        val currentAudioId = if (root.isNull("currentAudioId")) -1L else root.optLong("currentAudioId", -1L)
        return ProjectSnapshot(
            projectName = root.optString("projectName", "Untitled"),
            currentAudio = audioById(currentAudioId),
            rangeStartFrame = root.optInt("rangeStartFrame", 0),
            rangeEndFrame = root.optInt("rangeEndFrame", audioById(currentAudioId)?.frameCount ?: 0),
            sliceMarkers = root.optJSONArray("sliceMarkers").toIntList(),
            activeSliceIndex = if (root.isNull("activeSliceIndex")) null else root.optInt("activeSliceIndex"),
            selectedBank = root.optInt("selectedBank", 0),
            selectedPad = root.optInt("selectedPad", 0),
            autoNextPad = root.optBoolean("autoNextPad", true),
            pads = pads,
            patterns = patterns,
            selectedPattern = root.optInt("selectedPattern", 0),
            sequencerPage = root.optInt("sequencerPage", 0),
            songSections = song,
            playbackMode = enumValue(root.optString("playbackMode"), PlaybackMode.PATTERN),
            bpm = root.optDouble("bpm", 92.0).toFloat(),
            swing = root.optDouble("swing", 54.0).toFloat(),
            masterEffects = root.optJSONObject("masterEffects").toMasterEffects(),
            midiChannel = root.optInt("midiChannel", -1),
            midiBaseNote = root.optInt("midiBaseNote", SamplerConfig.DEFAULT_MIDI_BASE_NOTE),
            midiClockSync = root.optBoolean("midiClockSync", false),
            midiCcMappings = mappings,
        )
    }

    private fun PadModel.toJson() = JSONObject().apply {
        put("globalIndex", globalIndex)
        put("audioId", audio?.id ?: JSONObject.NULL)
        put("startFrame", startFrame)
        put("endFrame", endFrame)
        put("pitchSemitones", pitchSemitones.toDouble())
        put("timeStretchRatio", timeStretchRatio.toDouble())
        put("tone", tone.toDouble())
        put("resonance", resonance.toDouble())
        put("gain", gain.toDouble())
        put("pan", pan.toDouble())
        put("reverse", reverse)
        put("playMode", playMode.name)
        put("chokeGroup", chokeGroup)
        put("adsr", JSONObject().apply {
            put("attackMs", adsr.attackMs.toDouble())
            put("decayMs", adsr.decayMs.toDouble())
            put("sustainLevel", adsr.sustainLevel.toDouble())
            put("releaseMs", adsr.releaseMs.toDouble())
        })
        put("lfo", JSONObject().apply {
            put("enabled", lfo.enabled)
            put("waveform", lfo.waveform.name)
            put("target", lfo.target.name)
            put("rateHz", lfo.rateHz.toDouble())
            put("depth", lfo.depth.toDouble())
            put("tempoSync", lfo.tempoSync)
            put("syncDivision", lfo.syncDivision.name)
        })
        put("effects", JSONObject().apply {
            put("drive", effects.drive.toDouble())
            put("bitDepth", effects.bitDepth)
            put("sampleRateReduction", effects.sampleRateReduction)
            put("delaySend", effects.delaySend.toDouble())
            put("reverbSend", effects.reverbSend.toDouble())
        })
    }

    private fun PatternModel.toJson() = JSONObject().apply {
        put("index", index)
        put("name", name)
        put("lengthSteps", lengthSteps)
        put("activeSteps", JSONArray(activeSteps.sorted()))
    }

    private fun SongSection.toJson() = JSONObject().apply {
        put("id", id)
        put("patternIndex", patternIndex)
        put("repeats", repeats)
    }

    private fun MasterEffects.toJson() = JSONObject().apply {
        put("delayEnabled", delayEnabled)
        put("delayMix", delayMix.toDouble())
        put("delayFeedback", delayFeedback.toDouble())
        put("delayBeats", delayBeats.toDouble())
        put("pingPong", pingPong)
        put("reverbEnabled", reverbEnabled)
        put("reverbMix", reverbMix.toDouble())
        put("reverbSize", reverbSize.toDouble())
        put("reverbDamping", reverbDamping.toDouble())
        put("compressorEnabled", compressorEnabled)
        put("compressorThresholdDb", compressorThresholdDb.toDouble())
        put("compressorRatio", compressorRatio.toDouble())
        put("masterDrive", masterDrive.toDouble())
        put("masterGain", masterGain.toDouble())
    }

    private fun JSONObject?.toAdsr() = if (this == null) AdsrEnvelope() else AdsrEnvelope(
        attackMs = optDouble("attackMs", 2.0).toFloat(),
        decayMs = optDouble("decayMs", 60.0).toFloat(),
        sustainLevel = optDouble("sustainLevel", 1.0).toFloat(),
        releaseMs = optDouble("releaseMs", 120.0).toFloat(),
    )

    private fun JSONObject?.toLfo() = if (this == null) LfoSettings() else LfoSettings(
        enabled = optBoolean("enabled", false),
        waveform = enumValue(optString("waveform"), LfoWaveform.SINE),
        target = enumValue(optString("target"), LfoTarget.FILTER),
        rateHz = optDouble("rateHz", 2.0).toFloat(),
        depth = optDouble("depth", 0.0).toFloat(),
        tempoSync = optBoolean("tempoSync", false),
        syncDivision = enumValue(optString("syncDivision"), LfoSyncDivision.EIGHTH),
    )

    private fun JSONObject?.toPadEffects() = if (this == null) PadEffects() else PadEffects(
        drive = optDouble("drive", 0.0).toFloat(),
        bitDepth = optInt("bitDepth", 16),
        sampleRateReduction = optInt("sampleRateReduction", 1),
        delaySend = optDouble("delaySend", 0.0).toFloat(),
        reverbSend = optDouble("reverbSend", 0.0).toFloat(),
    )

    private fun JSONObject?.toMasterEffects() = if (this == null) MasterEffects() else MasterEffects(
        delayEnabled = optBoolean("delayEnabled", true),
        delayMix = optDouble("delayMix", 0.12).toFloat(),
        delayFeedback = optDouble("delayFeedback", 0.28).toFloat(),
        delayBeats = optDouble("delayBeats", 0.5).toFloat(),
        pingPong = optBoolean("pingPong", true),
        reverbEnabled = optBoolean("reverbEnabled", true),
        reverbMix = optDouble("reverbMix", 0.08).toFloat(),
        reverbSize = optDouble("reverbSize", 0.55).toFloat(),
        reverbDamping = optDouble("reverbDamping", 0.35).toFloat(),
        compressorEnabled = optBoolean("compressorEnabled", true),
        compressorThresholdDb = optDouble("compressorThresholdDb", -8.0).toFloat(),
        compressorRatio = optDouble("compressorRatio", 4.0).toFloat(),
        masterDrive = optDouble("masterDrive", 0.0).toFloat(),
        masterGain = optDouble("masterGain", 0.92).toFloat(),
    )

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (index in 0 until length()) optJSONObject(index)?.let(block)
    }

    private fun JSONArray.forEachInt(block: (Int) -> Unit) {
        for (index in 0 until length()) block(optInt(index))
    }

    private fun JSONArray?.toIntList(): List<Int> = buildList {
        if (this@toIntList != null) for (index in 0 until length()) add(optInt(index))
    }

    private fun ZipOutputStream.putDeterministicEntry(name: String) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
    }

    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "project.jsonが大きすぎます" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        const val SCHEMA_VERSION = 2
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        private const val MAX_AUDIO_FRAMES = 30_000_000
        private const val MAX_AUDIO_ASSETS = SamplerConfig.PAD_COUNT + 1
        private const val MAX_TOTAL_INTERLEAVED_SAMPLES = 80_000_000L
    }
}
