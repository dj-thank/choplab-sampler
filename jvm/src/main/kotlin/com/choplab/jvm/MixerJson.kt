package com.choplab.jvm

import com.choplab.engine.*
import kotlinx.serialization.json.JsonElement

internal fun trackFxJson(fx: TrackFx) = obj("insert" to mixInsertJson(fx.insert),
    "delaySend" to num(fx.delaySend), "reverbSend" to num(fx.reverbSend))

internal fun readTrackFx(value: JsonElement): TrackFx = value.obj().fields("insert", "delaySend", "reverbSend").let {
    TrackFx(readMixInsert(it.getValue("insert")), it.float("delaySend"), it.float("reverbSend"))
}

internal fun mixSettingsJson(mix: MixSettings) = obj(
    "delay" to obj("enabled" to bool(mix.delay.enabled), "frames" to num(mix.delay.frames),
        "feedback" to num(mix.delay.feedback), "returnGain" to num(mix.delay.returnGain)),
    "reverb" to obj("enabled" to bool(mix.reverb.enabled), "decaySeconds" to num(mix.reverb.decaySeconds),
        "damping" to num(mix.reverb.damping), "returnGain" to num(mix.reverb.returnGain)),
    "master" to mixInsertJson(mix.master), "masterGain" to num(mix.masterGain))

internal fun readMixSettings(value: JsonElement): MixSettings {
    val mix = value.obj().fields("delay", "reverb", "master", "masterGain")
    val delay = mix.getValue("delay").obj().fields("enabled", "frames", "feedback", "returnGain")
    val reverb = mix.getValue("reverb").obj().fields("enabled", "decaySeconds", "damping", "returnGain")
    return MixSettings(MixDelay(delay.boolean("enabled"), delay.int("frames"), delay.float("feedback"), delay.float("returnGain")),
        MixReverb(reverb.boolean("enabled"), reverb.float("decaySeconds"), reverb.float("damping"), reverb.float("returnGain")),
        readMixInsert(mix.getValue("master")), mix.float("masterGain"))
}

private fun mixInsertJson(insert: MixInsert) = obj(
    "eq" to obj("lowDb" to num(insert.eq.lowDb), "midDb" to num(insert.eq.midDb), "highDb" to num(insert.eq.highDb)),
    "filter" to obj("mode" to str(insert.filter.mode.name), "cutoffHz" to num(insert.filter.cutoffHz)),
    "compressor" to obj("enabled" to bool(insert.compressor.enabled), "thresholdDb" to num(insert.compressor.thresholdDb),
        "ratio" to num(insert.compressor.ratio), "attackMs" to num(insert.compressor.attackMs),
        "releaseMs" to num(insert.compressor.releaseMs), "makeupDb" to num(insert.compressor.makeupDb)))

private fun readMixInsert(value: JsonElement): MixInsert {
    val insert = value.obj().fields("eq", "filter", "compressor")
    val eq = insert.getValue("eq").obj().fields("lowDb", "midDb", "highDb")
    val filter = insert.getValue("filter").obj().fields("mode", "cutoffHz")
    val comp = insert.getValue("compressor").obj().fields("enabled", "thresholdDb", "ratio", "attackMs", "releaseMs", "makeupDb")
    return MixInsert(MixEq(eq.float("lowDb"), eq.float("midDb"), eq.float("highDb")),
        MixFilter(MixFilterMode.valueOf(filter.string("mode")), filter.float("cutoffHz")),
        MixCompressor(comp.boolean("enabled"), comp.float("thresholdDb"), comp.float("ratio"), comp.float("attackMs"),
            comp.float("releaseMs"), comp.float("makeupDb")))
}
