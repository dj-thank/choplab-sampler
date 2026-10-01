package com.choplab.jvm

import com.choplab.core.model.*
import com.choplab.engine.PitchCorrectionSettings
import com.choplab.engine.PitchScale
import kotlinx.serialization.json.JsonElement

internal fun vocalPitchJson(value: VocalPitchCorrection) = obj(
    "id" to str(value.id), "clipId" to str(value.clipId), "sourceAssetHash" to str(value.sourceAssetHash),
    "sourceRange" to obj("start" to num(value.sourceRange.start), "end" to num(value.sourceRange.end)),
    "renderedAssetHash" to str(value.renderedAssetHash), "algorithmVersion" to num(value.algorithmVersion),
    "settings" to obj("key" to num(value.settings.key), "scale" to str(value.settings.scale.name),
        "amount" to num(value.settings.amount), "retuneMs" to num(value.settings.retuneMs), "vibrato" to num(value.settings.vibrato)),
)

internal fun readVocalPitch(element: JsonElement): VocalPitchCorrection {
    val value = element.obj().fields("id", "clipId", "sourceAssetHash", "sourceRange", "renderedAssetHash", "algorithmVersion", "settings")
    val range = value.getValue("sourceRange").obj().fields("start", "end")
    val settings = value.getValue("settings").obj().fields("key", "scale", "amount", "retuneMs", "vibrato")
    return VocalPitchCorrection(value.string("id"), value.string("clipId"), value.string("sourceAssetHash"),
        FrameRange(range.long("start"), range.long("end")), value.string("renderedAssetHash"),
        PitchCorrectionSettings(settings.int("key"), PitchScale.valueOf(settings.string("scale")), settings.float("amount"),
            settings.float("retuneMs"), settings.float("vibrato")), value.int("algorithmVersion"))
}
