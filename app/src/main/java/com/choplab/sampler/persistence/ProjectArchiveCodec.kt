package com.choplab.sampler.persistence

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.ProjectLimits
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Bounded, versioned `.choplab` archive for the currently implemented sampler state.
 *
 * The manifest is deliberately small and text based. Audio assets are stored once as
 * canonical mono PCM-16 WAV and shared by current-source and PAD references.
 */
object ProjectArchiveCodec {
    private const val LEGACY_PCM_SCHEMA_VERSION = 1
    private const val WAV_SCHEMA_VERSION = 2
    private const val CONTENT_KIND_SCHEMA_VERSION = 4
    private const val SCHEMA_VERSION = CONTENT_KIND_SCHEMA_VERSION
    private const val MANIFEST_ENTRY = "project.txt"
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_MVP_AUDIO_FRAMES = 30_000_000

    fun write(state: SamplerUiState, output: OutputStream) {
        val audio = collectAudio(state)
        val audioIndexById = audio.mapIndexed { index, value -> value.id to index }.toMap()
        val manifest = encodeManifest(state, audio, audioIndexById).toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES) { "プロジェクト情報が大きすぎます" }

        ZipOutputStream(BufferedOutputStream(NonClosingOutputStream(output))).use { zip ->
            zip.putDeterministicEntry(MANIFEST_ENTRY)
            zip.write(manifest)
            zip.closeEntry()

            audio.forEachIndexed { index, value ->
                zip.putDeterministicEntry(audioEntry(index, SCHEMA_VERSION))
                MonoPcm16WavCodec.write(zip, value.samples, value.sampleRate)
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream): SamplerUiState {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            val manifestEntry = zip.nextEntry ?: error("project.txtがありません")
            validateEntryName(manifestEntry.name)
            require(!manifestEntry.isDirectory && manifestEntry.name == MANIFEST_ENTRY) {
                "project.txtを最初に配置してください"
            }
            val manifestBytes = zip.readBytesBounded(MAX_MANIFEST_BYTES)
            zip.closeEntry()
            val manifest = parseManifest(manifestBytes.toString(Charsets.UTF_8))

            val audioByIndex = linkedMapOf<Int, PcmAudio>()
            val seenEntries = mutableSetOf(MANIFEST_ENTRY)
            var totalPcmBytes = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                validateEntryName(entry.name)
                require(!entry.isDirectory) { "ディレクトリ項目は使用できません: ${entry.name}" }
                require(seenEntries.add(entry.name)) { "重複した項目があります: ${entry.name}" }
                val metadata = manifest.audio.singleOrNull { it.entryName == entry.name }
                    ?: error("未登録の項目があります: ${entry.name}")
                require(metadata.index !in audioByIndex) { "音声IDが重複しています" }
                totalPcmBytes += metadata.frameCount.toLong() * Short.SIZE_BYTES
                require(totalPcmBytes <= ProjectLimits.MAX_TOTAL_PCM_BYTES) {
                    "プロジェクト内の音声データが大きすぎます"
                }
                val samples = when (manifest.schemaVersion) {
                    LEGACY_PCM_SCHEMA_VERSION -> zip.readLegacyPcm16LittleEndian(metadata.frameCount)
                    in WAV_SCHEMA_VERSION..SCHEMA_VERSION ->
                        MonoPcm16WavCodec.read(zip, metadata.frameCount, metadata.sampleRate)
                    else -> error("未対応のプロジェクト形式です")
                }
                require(zip.read() == -1) { "音声データのサイズが一致しません: ${entry.name}" }
                audioByIndex[metadata.index] = PcmAudio(
                    id = metadata.id,
                    name = metadata.name,
                    samples = samples,
                    sampleRate = metadata.sampleRate,
                )
                zip.closeEntry()
            }

            require(audioByIndex.size == manifest.audio.size) { "プロジェクト内の音声が不足しています" }
            return manifest.toState(audioByIndex)
        }
    }

    private fun collectAudio(state: SamplerUiState): List<PcmAudio> {
        val byId = linkedMapOf<Long, PcmAudio>()
        fun add(value: PcmAudio?) {
            if (value == null) return
            require(value.sampleRate in 8_000..ProjectLimits.MAX_SAMPLE_RATE) { "音声のサンプルレートが不正です" }
            require(value.samples.isNotEmpty() && value.samples.size <= MAX_MVP_AUDIO_FRAMES) {
                "音声データの長さが不正です"
            }
            val previous = byId[value.id]
            require(
                previous == null ||
                    previous.name == value.name &&
                    previous.sampleRate == value.sampleRate &&
                    previous.samples.contentEquals(value.samples),
            ) { "同じ音声IDに異なるデータがあります" }
            if (previous == null) byId[value.id] = value
        }
        add(state.currentAudio)
        state.pads.forEach { add(it.audio) }
        require(byId.size <= ProjectLimits.MAX_AUDIO_ASSETS) { "プロジェクト内の音声数が多すぎます" }
        val totalBytes = byId.values.sumOf { it.samples.size.toLong() * Short.SIZE_BYTES }
        require(totalBytes <= ProjectLimits.MAX_TOTAL_PCM_BYTES) { "プロジェクト内の音声データが大きすぎます" }
        return byId.values.toList()
    }

    private fun encodeManifest(
        state: SamplerUiState,
        audio: List<PcmAudio>,
        audioIndexById: Map<Long, Int>,
    ): String = buildString {
        appendLine("CHOPLAB_PROJECT\t$SCHEMA_VERSION")
        appendLine("audioCount\t${audio.size}")
        audio.forEachIndexed { index, value ->
            appendLine(
                listOf(
                    "audio",
                    index,
                    value.id,
                    value.sampleRate,
                    value.frameCount,
                    encodeText(value.name),
                    audioEntry(index, SCHEMA_VERSION),
                ).joinToString("\t"),
            )
        }
        appendLine(
            listOf(
                "state",
                state.rangeStartFrame,
                state.rangeEndFrame,
                state.activeSliceIndex ?: -1,
                state.manualChopEnabled.toFlag(),
                state.selectedBank,
                state.selectedPad,
                state.autoNextPad.toFlag(),
                state.bpm,
                state.swing,
                state.sourcePlayheadFrame,
                state.masterPitchSemitones,
                state.currentAudio?.let { audioIndexById[it.id] } ?: -1,
                encodeText(state.selectedDrumKitId),
            ).joinToString("\t"),
        )
        appendLine("slices\t${state.sliceMarkers.joinToString(",")}")
        appendLine("steps\t${state.activeSteps.sorted().joinToString(",")}")
        appendLine("padCount\t${state.pads.size}")
        state.pads.forEach { pad ->
            appendLine(
                listOf(
                    "pad",
                    pad.globalIndex,
                    pad.audio?.let { audioIndexById[it.id] } ?: -1,
                    pad.startFrame,
                    pad.endFrame,
                    pad.pitchSemitones,
                    pad.tone,
                    pad.gain,
                    pad.reverse.toFlag(),
                    pad.playMode.name,
                    pad.contentKind.name,
                    pad.chokeGroup,
                ).joinToString("\t"),
            )
        }
    }

    private fun parseManifest(text: String): Manifest {
        val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
        var cursor = 0
        fun next(label: String): List<String> {
            require(cursor < lines.size) { "$label がありません" }
            val values = lines[cursor++].split('\t')
            require(values.firstOrNull() == label) { "$label が不正です" }
            return values
        }

        val header = next("CHOPLAB_PROJECT")
        require(header.size == 2) { "プロジェクト形式が不正です" }
        val schemaVersion = header[1].toIntStrict("schemaVersion")
        require(schemaVersion in LEGACY_PCM_SCHEMA_VERSION..SCHEMA_VERSION) {
            if (schemaVersion > SCHEMA_VERSION) {
                "このプロジェクトは新しいChopLabで作成されています。アプリを更新してください"
            } else {
                "このプロジェクト形式には対応していません"
            }
        }
        val audioCountLine = next("audioCount")
        require(audioCountLine.size == 2) { "audioCountが不正です" }
        val audioCount = audioCountLine[1].toIntStrict("audioCount")
        require(audioCount in 0..ProjectLimits.MAX_AUDIO_ASSETS) { "プロジェクト内の音声数が不正です" }
        val audio = List(audioCount) { expectedIndex ->
            val values = next("audio")
            require(values.size == 7) { "音声情報が不正です" }
            val index = values[1].toIntStrict("audio.index")
            require(index == expectedIndex) { "音声indexが連続していません" }
            val id = values[2].toLongStrict("audio.id")
            val sampleRate = values[3].toIntStrict("audio.sampleRate")
            require(sampleRate in 8_000..ProjectLimits.MAX_SAMPLE_RATE) { "音声sampleRateが不正です" }
            val frameCount = values[4].toIntStrict("audio.frameCount")
            require(frameCount in 1..MAX_MVP_AUDIO_FRAMES) { "音声frameCountが不正です" }
            val name = decodeText(values[5])
            require(name.isNotBlank() && name.length <= ProjectLimits.MAX_ASSET_NAME_CHARS) { "音声名が不正です" }
            val entryName = values[6]
            validateEntryName(entryName)
            require(entryName == audioEntry(index, schemaVersion)) { "音声entryが不正です" }
            AudioManifest(index, id, sampleRate, frameCount, name, entryName)
        }
        require(audio.distinctBy { it.id }.size == audio.size) { "音声IDが重複しています" }
        require(audio.distinctBy { it.entryName }.size == audio.size) { "音声entryが重複しています" }
        require(audio.sumOf { it.frameCount.toLong() * Short.SIZE_BYTES } <= ProjectLimits.MAX_TOTAL_PCM_BYTES) {
            "プロジェクト内の音声データが大きすぎます"
        }
        val state = next("state")
        val expectedStateFields = if (schemaVersion >= CONTENT_KIND_SCHEMA_VERSION) 14 else 13
        require(state.size == expectedStateFields) { "stateが不正です" }
        val slices = next("slices").also { require(it.size in 1..2) { "slicesが不正です" } }
        val steps = next("steps").also { require(it.size in 1..2) { "stepsが不正です" } }
        val padCountLine = next("padCount")
        require(padCountLine.size == 2) { "padCountが不正です" }
        val padCount = padCountLine[1].toIntStrict("padCount")
        require(padCount == SamplerConfig.PAD_COUNT) { "PAD数が不正です" }
        val pads = List(padCount) { expectedIndex ->
            val values = next("pad")
            val expectedPadFields = if (schemaVersion >= CONTENT_KIND_SCHEMA_VERSION) 12 else 11
            require(values.size == expectedPadFields) { "PAD情報が不正です" }
            val globalIndex = values[1].toIntStrict("pad.globalIndex")
            require(globalIndex == expectedIndex) { "PAD indexが連続していません" }
            PadManifest(
                globalIndex = globalIndex,
                audioIndex = values[2].toIntStrict("pad.audioIndex"),
                startFrame = values[3].toIntStrict("pad.startFrame"),
                endFrame = values[4].toIntStrict("pad.endFrame"),
                pitchSemitones = values[5].toFiniteFloat("pad.pitch"),
                tone = values[6].toFiniteFloat("pad.tone"),
                gain = values[7].toFiniteFloat("pad.gain"),
                reverse = values[8].toBooleanStrict("pad.reverse"),
                playMode = runCatching { PadPlayMode.valueOf(values[9]) }
                    .getOrElse { error("PAD playModeが不正です") },
                contentKind = if (schemaVersion >= CONTENT_KIND_SCHEMA_VERSION) {
                    runCatching { PadContentKind.valueOf(values[10]) }
                        .getOrElse { error("PAD contentKindが不正です") }
                } else {
                    PadContentKind.SAMPLE
                },
                chokeGroup = values[expectedPadFields - 1].toIntStrict("pad.chokeGroup"),
            )
        }
        require(cursor == lines.size) { "project.txtに未対応の情報があります" }
        return Manifest(
            schemaVersion = schemaVersion,
            audio = audio,
            rangeStartFrame = state[1].toIntStrict("rangeStartFrame"),
            rangeEndFrame = state[2].toIntStrict("rangeEndFrame"),
            activeSliceIndex = state[3].toIntStrict("activeSliceIndex"),
            manualChopEnabled = state[4].toBooleanStrict("manualChopEnabled"),
            selectedBank = state[5].toIntStrict("selectedBank"),
            selectedPad = state[6].toIntStrict("selectedPad"),
            autoNextPad = state[7].toBooleanStrict("autoNextPad"),
            bpm = state[8].toFiniteFloat("bpm"),
            swing = state[9].toFiniteFloat("swing"),
            sourcePlayheadFrame = state[10].toIntStrict("sourcePlayheadFrame"),
            masterPitchSemitones = state[11].toFiniteFloat("masterPitchSemitones"),
            currentAudioIndex = state[12].toIntStrict("currentAudioIndex"),
            selectedDrumKitId = if (schemaVersion >= CONTENT_KIND_SCHEMA_VERSION) {
                decodeText(state[13]).also { require(it.length <= 64) { "drumKitIdが不正です" } }
            } else {
                "dusty-jazz"
            },
            sliceMarkers = slices.getOrNull(1).toIntList("slices"),
            activeSteps = steps.getOrNull(1).toIntList("steps").toSet(),
            pads = pads,
        )
    }

    private fun Manifest.toState(audioByIndex: Map<Int, PcmAudio>): SamplerUiState {
        fun audioAt(index: Int): PcmAudio? {
            require(index in -1 until audio.size) { "音声参照が不正です" }
            return if (index == -1) null else requireNotNull(audioByIndex[index]) { "音声参照がありません" }
        }

        val currentAudio = audioAt(currentAudioIndex)
        val safeEnd = currentAudio?.frameCount ?: 0
        require(selectedBank in 0 until SamplerConfig.BANK_COUNT) { "選択BANKが不正です" }
        require(selectedPad in 0 until SamplerConfig.PAD_COUNT) { "選択PADが不正です" }
        require(selectedPad / SamplerConfig.PADS_PER_BANK == selectedBank) { "選択BANKとPADが一致しません" }
        require(rangeStartFrame in 0..safeEnd && rangeEndFrame in rangeStartFrame..safeEnd) { "選択範囲が不正です" }
        require(sliceMarkers == sliceMarkers.distinct().sorted()) { "チョップ位置が不正です" }
        require(sliceMarkers.all { it > rangeStartFrame && it < rangeEndFrame }) { "チョップ位置が範囲外です" }
        val sliceCount = if (currentAudio == null || rangeEndFrame <= rangeStartFrame) 0 else sliceMarkers.size + 1
        require(activeSliceIndex == -1 || activeSliceIndex in 0 until sliceCount) { "選択スライスが不正です" }
        require(bpm in 40f..240f) { "BPMが不正です" }
        require(swing in 50f..75f) { "Swingが不正です" }
        require(masterPitchSemitones in -12f..12f) { "曲のKEYが不正です" }
        require(activeSteps.all { it in 0 until SamplerConfig.PAD_COUNT * SamplerConfig.STEP_COUNT }) {
            "シーケンス位置が不正です"
        }
        val restoredPads = pads.map { pad ->
            val padAudio = audioAt(pad.audioIndex)
            require(pad.pitchSemitones in -24f..24f) { "PAD pitchが不正です" }
            require(pad.tone in 0f..1f) { "PAD toneが不正です" }
            require(pad.gain in 0f..1.5f) { "PAD gainが不正です" }
            require(pad.chokeGroup in 0..4) { "PAD chokeが不正です" }
            if (padAudio == null) {
                require(pad.startFrame == 0 && pad.endFrame == 0) { "空PADの範囲が不正です" }
            } else {
                require(pad.startFrame in 0 until pad.endFrame && pad.endFrame <= padAudio.frameCount) {
                    "PAD範囲が不正です"
                }
            }
            PadModel(
                globalIndex = pad.globalIndex,
                audio = padAudio,
                startFrame = pad.startFrame,
                endFrame = pad.endFrame,
                pitchSemitones = pad.pitchSemitones,
                tone = pad.tone,
                gain = pad.gain,
                reverse = pad.reverse,
                playMode = pad.playMode,
                contentKind = pad.contentKind,
                chokeGroup = pad.chokeGroup,
            )
        }
        return SamplerUiState(
            statusMessage = "プロジェクトを復元しました",
            currentAudio = currentAudio,
            rangeStartFrame = rangeStartFrame,
            rangeEndFrame = rangeEndFrame,
            sliceMarkers = sliceMarkers,
            activeSliceIndex = activeSliceIndex.takeIf { it >= 0 },
            manualChopEnabled = manualChopEnabled,
            selectedBank = selectedBank,
            selectedPad = selectedPad,
            autoNextPad = autoNextPad,
            pads = restoredPads,
            activeSteps = activeSteps,
            bpm = bpm,
            swing = swing,
            sourcePlayheadFrame = sourcePlayheadFrame.coerceIn(0, (safeEnd - 1).coerceAtLeast(0)),
            masterPitchSemitones = masterPitchSemitones,
            selectedDrumKitId = selectedDrumKitId,
        )
    }

    private data class Manifest(
        val schemaVersion: Int,
        val audio: List<AudioManifest>,
        val rangeStartFrame: Int,
        val rangeEndFrame: Int,
        val activeSliceIndex: Int,
        val manualChopEnabled: Boolean,
        val selectedBank: Int,
        val selectedPad: Int,
        val autoNextPad: Boolean,
        val bpm: Float,
        val swing: Float,
        val sourcePlayheadFrame: Int,
        val masterPitchSemitones: Float,
        val currentAudioIndex: Int,
        val selectedDrumKitId: String,
        val sliceMarkers: List<Int>,
        val activeSteps: Set<Int>,
        val pads: List<PadManifest>,
    )

    private data class AudioManifest(
        val index: Int,
        val id: Long,
        val sampleRate: Int,
        val frameCount: Int,
        val name: String,
        val entryName: String,
    )

    private data class PadManifest(
        val globalIndex: Int,
        val audioIndex: Int,
        val startFrame: Int,
        val endFrame: Int,
        val pitchSemitones: Float,
        val tone: Float,
        val gain: Float,
        val reverse: Boolean,
        val playMode: PadPlayMode,
        val contentKind: PadContentKind,
        val chokeGroup: Int,
    )

    private fun ZipOutputStream.putDeterministicEntry(name: String) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
    }

    private fun InputStream.readLegacyPcm16LittleEndian(frameCount: Int): ShortArray {
        val result = ShortArray(frameCount)
        val buffer = ByteArray(8 * 1024)
        var sampleIndex = 0
        while (sampleIndex < frameCount) {
            val wantedBytes = minOf(buffer.size, (frameCount - sampleIndex) * Short.SIZE_BYTES)
            var received = 0
            while (received < wantedBytes) {
                val count = read(buffer, received, wantedBytes - received)
                require(count >= 0) { "音声データが途中で終わっています" }
                received += count
            }
            var offset = 0
            while (offset < received) {
                val low = buffer[offset].toInt() and 0xFF
                val high = buffer[offset + 1].toInt()
                result[sampleIndex++] = ((high shl 8) or low).toShort()
                offset += Short.SIZE_BYTES
            }
        }
        return result
    }

    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "project.txtが大きすぎます" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun validateEntryName(name: String) {
        require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name) { "危険な項目名です: $name" }
        require(name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "危険な項目名です: $name" }
    }

    private fun audioEntry(index: Int, schemaVersion: Int) =
        if (schemaVersion == LEGACY_PCM_SCHEMA_VERSION) "audio/$index.pcm" else "audio/$index.wav"

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() {
            flush()
        }
    }

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String = runCatching {
        Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
    }.getOrElse { error("文字列情報が不正です") }

    private fun Boolean.toFlag(): Int = if (this) 1 else 0

    private fun String.toBooleanStrict(label: String): Boolean = when (this) {
        "0" -> false
        "1" -> true
        else -> error("$label が不正です")
    }

    private fun String.toIntStrict(label: String): Int = toIntOrNull() ?: error("$label が不正です")
    private fun String.toLongStrict(label: String): Long = toLongOrNull() ?: error("$label が不正です")
    private fun String.toFiniteFloat(label: String): Float =
        toFloatOrNull()?.takeIf(Float::isFinite) ?: error("$label が不正です")

    private fun String?.toIntList(label: String): List<Int> {
        if (isNullOrEmpty()) return emptyList()
        return split(',').map { it.toIntOrNull() ?: error("$label が不正です") }
    }
}
