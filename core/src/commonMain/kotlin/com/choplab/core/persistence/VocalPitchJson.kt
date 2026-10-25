package com.choplab.core.persistence

import com.choplab.core.model.*
import com.choplab.engine.PitchCorrectionSettings
import com.choplab.engine.PitchScale
import kotlinx.serialization.json.JsonElement

fun vocalPitchJson(value: VocalPitchCorrection) = obj(
    "id" to str(value.id), "clipId" to str(value.clipId), "sourceAssetHash" to str(value.sourceAssetHash),
    "sourceRange" to obj("start" to num(value.sourceRange.start), "end" to num(value.sourceRange.end)),
    "renderedAssetHash" to str(value.renderedAssetHash), "algorithmVersion" to num(value.algorithmVersion),
    "normalizedOriginalHash" to nullable(value.normalizedOriginalHash),
    "settings" to obj("key" to num(value.settings.key), "scale" to str(value.settings.scale.name),
        "amount" to num(value.settings.amount), "retuneMs" to num(value.settings.retuneMs), "vibrato" to num(value.settings.vibrato)),
)

fun readVocalPitch(element: JsonElement, schema: Int = ProjectLimits.SCHEMA): VocalPitchCorrection {
    val value = element.obj().fields("id", "clipId", "sourceAssetHash", "sourceRange", "renderedAssetHash", "algorithmVersion", "settings",
        *(if (schema >= 16) arrayOf("normalizedOriginalHash") else emptyArray()))
    val range = value.getValue("sourceRange").obj().fields("start", "end")
    val settings = value.getValue("settings").obj().fields("key", "scale", "amount", "retuneMs", "vibrato")
    return VocalPitchCorrection(value.string("id"), value.string("clipId"), value.string("sourceAssetHash"),
        FrameRange(range.long("start"), range.long("end")), value.string("renderedAssetHash"),
        PitchCorrectionSettings(settings.int("key"), PitchScale.valueOf(settings.string("scale")), settings.float("amount"),
            settings.float("retuneMs"), settings.float("vibrato")), value.int("algorithmVersion"),
        if (schema >= 16) value.optionalString("normalizedOriginalHash") else null)
}
