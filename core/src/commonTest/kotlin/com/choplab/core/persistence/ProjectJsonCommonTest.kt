package com.choplab.core.persistence

import com.choplab.core.model.*
import com.choplab.engine.MixCompressor
import com.choplab.engine.MixDelay
import com.choplab.engine.MixEq
import com.choplab.engine.MixFilter
import com.choplab.engine.MixFilterMode
import com.choplab.engine.MixInsert
import com.choplab.engine.MixReverb
import com.choplab.engine.MixSettings
import com.choplab.engine.PlayMode
import com.choplab.engine.Tempo
import com.choplab.engine.TrackFx
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The strict document codec runs on every host. A document written on iPadOS must be the same bytes a JVM host
 * writes for the same project, and read back the same way, including the fractional values whose text could differ.
 */
class ProjectJsonCommonTest {
    private val hash = "0123456789abcdef".repeat(4)

    private fun rich(): Project {
        val asset = Asset(hash, "wav", 4_096, 44_100, 2, 1_000, "Warm Keys")
        val pads = (0..127).map { id ->
            if (id != 2) Pad(id) else Pad(2, hash, FrameRange(10, 900), "A03 Warm Keys", PlayMode.GATE, 2.5, .9f, -.35f, true, 3,
                12, 480, 24, 4_800, .7f, .42f)
        }
        val fx = TrackFx(MixInsert(MixEq(1.5f, -2.25f, 3.1f), MixFilter(MixFilterMode.LOW_PASS, 12_000.5f),
            MixCompressor(true, -18.5f, 3.3f, 7.5f, 120.25f, 1.1f)), .15f, .05f)
        return Project(
            id = "night-sketch", title = "夜のスケッチ — 🎹",
            assets = frozenListOf(asset),
            banks = (0..7).map { if (it == 0) Bank(0, "メロディ", 0xFF7A1A, "melody", "bank-a") else Bank(it) }.frozen(),
            pads = pads.frozen(),
            patterns = frozenListOf(Pattern("pattern-1", "Verse", 2, frozenListOf(Note(0, 2, .33f), Note(1_440, 2, 1f)))),
            song = frozenListOf(SongSection("pattern-1", 3)),
            tracks = frozenListOf(Track("bank-a", "Melody", TrackKind.BANK, .8f, .1f, fx = fx),
                Track("voice", "Voice", TrackKind.VOCAL, 1.2f, -.6f, mute = true, solo = true)),
            clips = frozenListOf(Clip("clip-1", "bank-a", hash, FrameRange(10, 900), 960, null, 1.25f, -.5f),
                Clip("clip-2", "voice", hash, FrameRange(0, 1_000), 0, 96_000)),
            lyrics = frozenListOf(LyricLine("line-1", "歌詞のテスト", 0, 1_920, frozenListOf(LyricWord("歌詞", 0, 960)))),
            takes = frozenListOf(Take("take-1", "voice", hash, FrameRange(0, 1_000), 48_000, -240)),
            source = Source(hash, FrameRange(0, 1_000), frozenListOf(250L, 500L), -3.0),
            tempo = Tempo(92_000, 580),
            mix = MixSettings(MixDelay(true, 9_000, .3f, .45f), MixReverb(true, 1.7f, .25f, .6f), fx.insert, .95f),
        )
    }

    /** FNV-1a 64: a compact fingerprint of bytes the JVM host produced, checkable on every target. */
    private fun fingerprint(bytes: ByteArray): String {
        var hash = -0x340d631b7bdddcdbL
        for (byte in bytes) { hash = (hash xor (byte.toLong() and 0xff)) * 0x100000001b3L }
        return "${bytes.size}:${hash.toULong().toString(16)}"
    }

    @Test fun theDefaultDocumentIsTheJvmGoldenOnEveryHost() {
        // jvm/src/test/resources/schema15-empty.json, the golden the JVM persistence test compares byte for byte.
        assertEquals("32552:910debf62d49b11f", fingerprint(ProjectJson.encode(Project())))
    }

    @Test fun aRichDocumentEncodesToTheSameBytesAndReadsBackTheSameOnEveryHost() {
        val project = rich()
        val bytes = ProjectJson.encode(project)
        assertEquals(RICH_FINGERPRINT, fingerprint(bytes), bytes.decodeToString())
        assertEquals(project, ProjectJson.decode(bytes))
        assertContentEquals(bytes, ProjectJson.encodeElement(ProjectJson.parse(bytes)), "Parsed text re-encodes unchanged")
    }

    @Test fun ambiguousOrMalformedDocumentsAreRejected() {
        val text = ProjectJson.encode(Project()).decodeToString()
        for (mutated in listOf(
            text.replace("\"schemaVersion\":15", "\"schemaVersion\":9"),
            text.replace("\"schemaVersion\":15", "\"schemaVersion\":15,\"schemaVersion\":15"),
            text.replace("\"schemaVersion\":15", "\"schemaVersion\":15,\"\\u0073chemaVersion\":15"),
            text.replace("\"schemaVersion\":15", "\"schemaVersion\":\"15\""),
            text.replace("\"id\":\"untitled\"", "\"id\":\"untitled\",\"privatePath\":\"secret\""),
            text.replace("\"masterGain\":1.0", "\"masterGain\":1e999"),
        )) assertFailsWith<IllegalArgumentException>(mutated.take(80)) { ProjectJson.decode(mutated.encodeToByteArray()) }
        assertFailsWith<CharacterCodingException> { ProjectJson.decode(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(("[".repeat(33) + "0" + "]".repeat(33)).encodeToByteArray()) }
    }

    @Test fun anUnpairedSurrogateIsWrittenAsAQuestionMarkWhileAPairSurvives() {
        val text = ProjectJson.encode(Project(title = "a\uD800b\uDC00c🎹")).decodeToString()
        assertTrue("\"title\":\"a?b?c🎹\"" in text, text.take(160))
        assertEquals("plain", replaceUnpairedSurrogates("plain"))
        assertEquals("?x?", replaceUnpairedSurrogates("\uDC00x\uD800"))
    }

    private companion object {
        /** Recorded from the JVM host (desktop target) at the codec's move into core. */
        const val RICH_FINGERPRINT = "34636:bd7213bfcea633c4"
    }
}
