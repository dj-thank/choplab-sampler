package com.choplab.sampler.model

const val MAX_BEAT_LOOP_LAYERS = 8

fun SamplerUiState.loopLayerChangeBlockedReason(index: Int, enabled: Boolean): String? {
    playbackStartBlockedReason(this)?.let { return it }
    val pad = pads.getOrNull(index)
    if (pad?.isAssigned != true) return "音の入ったPADを選んでください"
    if (pad.contentKind == PadContentKind.VOCAL) return "声は録音レイヤーとして重なります"
    if (!enabled) return if (loopingPadIndex == index) "核のループは全体停止で止めます" else null
    if (pad.playMode == PadPlayMode.LOOP) return null
    val loops = pads.filter { it.isAssigned && it.playMode == PadPlayMode.LOOP }
    if (loops.size >= MAX_BEAT_LOOP_LAYERS) return "重ねるループは8音までです"
    if (pad.chokeGroup > 0 && loops.any { it.chokeGroup == pad.chokeGroup }) {
        return "同じ同時停止グループの音です。再生設定でグループを分けてください"
    }
    return null
}

fun SamplerUiState.withLoopLayer(index: Int, enabled: Boolean): SamplerUiState {
    val pad = pads[index]
    if (!enabled && pad.playMode != PadPlayMode.LOOP) return this
    val mode = if (enabled) PadPlayMode.LOOP else PadPlayMode.ONE_SHOT
    if (pad.playMode == mode) return this
    return copy(
        pads = pads.toMutableList().also { it[index] = pad.copy(playMode = mode) },
        statusMessage = "${bankRoleFor(pad.bankIndex).letter}-%02d".format(pad.indexInBank + 1) +
            if (enabled) " をループで重ねています" else " の重ねるループを外しました",
    )
}

/** The core is monitored separately; all configured loops restart with it. */
fun List<PadModel>.loopCompanionPadIndicesForLoopStart(owner: Int, includeVocals: Boolean = true): List<Int> {
    val protectedGroups = asSequence()
        .filter { it.isAssigned && (it.globalIndex == owner || it.playMode == PadPlayMode.LOOP) }
        .map(PadModel::chokeGroup).filter { it > 0 }.toSet()
    val loops = filter { it.isAssigned && it.globalIndex != owner && it.playMode == PadPlayMode.LOOP }
    val vocals = if (includeVocals) filter {
        it.isAssigned && it.globalIndex != owner && it.playMode != PadPlayMode.LOOP &&
            it.contentKind == PadContentKind.VOCAL && it.chokeGroup !in protectedGroups
    } else emptyList()
    return (loops + vocals).map(PadModel::globalIndex)
}
