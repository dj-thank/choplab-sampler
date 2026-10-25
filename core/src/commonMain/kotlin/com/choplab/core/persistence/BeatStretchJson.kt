package com.choplab.core.persistence

import com.choplab.core.model.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

fun beatStretchJson(value: BeatStretch) = obj(
    "kind" to str(value.target.kind.name), "id" to str(value.target.id),
    "sourceAssetHash" to str(value.sourceAssetHash), "renderedAssetHash" to str(value.renderedAssetHash),
    "sourceRange" to obj("start" to num(value.sourceRange.start), "end" to num(value.sourceRange.end)),
    "renderedRange" to obj("start" to num(value.renderedRange.start), "end" to num(value.renderedRange.end)),
    "sourceMilliBpm" to num(value.sourceMilliBpm), "targetMilliBpm" to num(value.targetMilliBpm), "algorithmVersion" to num(value.algorithmVersion),
    "crop" to (value.crop?.let { obj("start" to num(it.range.start), "end" to num(it.range.end), "referenceFrames" to num(it.referenceFrames)) } ?: JsonNull),
)
fun readBeatStretch(element: JsonElement, schema: Int = ProjectLimits.SCHEMA): BeatStretch {
    val value = element.obj().fields("kind", "id", "sourceAssetHash", "renderedAssetHash", "sourceRange", "renderedRange", "sourceMilliBpm", "targetMilliBpm", "algorithmVersion",
        *(if (schema >= 16) arrayOf("crop") else emptyArray()))
    fun range(key: String): FrameRange = value.getValue(key).obj().fields("start", "end").let { FrameRange(it.long("start"), it.long("end")) }
    return BeatStretch(StretchTarget(StretchKind.valueOf(value.string("kind")), value.string("id")), value.string("sourceAssetHash"), range("sourceRange"),
        value.string("renderedAssetHash"), range("renderedRange"), value.int("sourceMilliBpm"), value.int("targetMilliBpm"), value.int("algorithmVersion"),
        if (schema < 16 || value.getValue("crop") == JsonNull) null else value.getValue("crop").obj().fields("start", "end", "referenceFrames").let {
            StretchCrop(FrameRange(it.long("start"), it.long("end")), it.long("referenceFrames"))
        })
}
