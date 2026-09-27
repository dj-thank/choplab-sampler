package com.choplab.sampler.next

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.choplab.core.Location
import com.choplab.core.model.ProjectLimits
import com.choplab.jvm.HostDecoder
import com.choplab.jvm.WavCodec
import com.choplab.sampler.audio.AudioDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The existing MediaCodec decoder behind the shared import: MP3/AAC/FLAC/Ogg and other platform codecs
 * become a 16-bit WAV scratch file that the shared WAV import verifies and stores. The 16-bit output is the
 * existing decoder's quality; float decoding belongs to the import rebuild (stage 3).
 */
class AndroidAudioDecoding(context: Context, private val documents: AndroidDocuments) : HostDecoder {
    private val decoder = AudioDecoder(context.applicationContext)

    override suspend fun decodeToWav(location: Location, target: Path) {
        val audio = decoder.decode(documents.uri(location))
        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).buffered().use { output ->
            WavCodec.writePcm16(output, audio.samples, audio.sampleRate, audio.channelCount)
        }
    }
}

/** Compatibility label until host wording uses the rate-specific frame limit. PCM admission uses prefetch. */
val maximumSourceSeconds: Int = (ProjectLimits.MAX_FRAMES / 48_000).toInt()

/** What the platform says about a picked file before anything is copied or decoded. */
enum class SourceCheck { ACCEPTED, TOO_LONG, UNREADABLE }

fun checkSource(context: Context, uri: Uri): SourceCheck {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, uri, null)
        val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return SourceCheck.UNREADABLE
        val micros = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48_000
        if (rate !in 8_000..192_000) SourceCheck.UNREADABLE
        else if (micros > ProjectLimits.MAX_FRAMES * 1_000_000L / rate) SourceCheck.TOO_LONG else SourceCheck.ACCEPTED
    } catch (_: Exception) {
        SourceCheck.UNREADABLE
    } finally {
        extractor.release()
    }
}
