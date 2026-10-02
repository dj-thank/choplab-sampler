package com.choplab.jvm

import com.choplab.core.model.*
import kotlinx.serialization.json.JsonElement

internal fun beatStretchJson(value: BeatStretch) = obj(
    "kind" to str(value.target.kind.name), "id" to str(value.target.id),
    "sourceAssetHash" to str(value.sourceAssetHash), "renderedAssetHash" to str(value.renderedAssetHash),
    "sourceRange" to obj("start" to num(value.sourceRange.start), "end" to num(value.sourceRange.end)),
    "renderedRange" to obj("start" to num(value.renderedRange.start), "end" to num(value.renderedRange.end)),
    "sourceMilliBpm" to num(value.sourceMilliBpm), "targetMilliBpm" to num(value.targetMilliBpm), "algorithmVersion" to num(value.algorithmVersion),
)
internal fun readBeatStretch(element: JsonElement): BeatStretch {
    val value = element.obj().fields("kind", "id", "sourceAssetHash", "renderedAssetHash", "sourceRange", "renderedRange", "sourceMilliBpm", "targetMilliBpm", "algorithmVersion")
    fun range(key: String): FrameRange = value.getValue(key).obj().fields("start", "end").let { FrameRange(it.long("start"), it.long("end")) }
    return BeatStretch(StretchTarget(StretchKind.valueOf(value.string("kind")), value.string("id")), value.string("sourceAssetHash"), range("sourceRange"),
        value.string("renderedAssetHash"), range("renderedRange"), value.int("sourceMilliBpm"), value.int("targetMilliBpm"), value.int("algorithmVersion"))
}
