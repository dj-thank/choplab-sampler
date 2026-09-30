package com.choplab.jvm

import com.choplab.core.model.*
import kotlinx.serialization.json.JsonElement

internal fun vocalCompJson(comp: VocalComp) = obj("id" to str(comp.id), "renderedAssetHash" to str(comp.renderedAssetHash),
    "segments" to arr(comp.segments.map { line -> obj("id" to str(line.id), "takeId" to str(line.takeId),
        "startFrame" to num(line.startFrame), "endFrame" to num(line.endFrame), "lyricLineId" to nullable(line.lyricLineId)) }))

internal fun readVocalComp(element: JsonElement): VocalComp {
    val comp = element.obj().fields("id", "renderedAssetHash", "segments")
    return VocalComp(comp.string("id"), comp.string("renderedAssetHash"), comp.list("segments", 4096) {
        val line = it.obj().fields("id", "takeId", "startFrame", "endFrame", "lyricLineId")
        VocalCompSegment(line.string("id"), line.string("takeId"), line.long("startFrame"), line.long("endFrame"), line.optionalString("lyricLineId"))
    })
}
