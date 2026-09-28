package com.choplab.core.ai

import com.choplab.core.model.*
import com.choplab.engine.Tempo

data class PreparedVocalLine(val line: LyricLine, val asset: Asset, val speed: Double, val trimmedFrames: Long,
                             val rawCacheHit: Boolean, val processedCacheHit: Boolean)

/** The worker publishes content-addressed files, never a Project. Explicit Apply owns the document commit. */
interface VocalSynthesisPort {
    suspend fun voices(): TtsResult<FrozenList<TtsVoice>>
    suspend fun prepare(row: FlowRow, tempo: Tempo, voice: TtsVoice, settings: TtsSettings,
                        regenerate: Boolean = false): TtsResult<PreparedVocalLine>
    fun close()
}
