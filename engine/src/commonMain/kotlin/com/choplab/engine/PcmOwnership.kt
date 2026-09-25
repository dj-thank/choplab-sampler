package com.choplab.engine

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Single control producer reserves PCM before publication. Render consumes the reservation and
 * retains one lease while a Program, PAD voice or original-source voice still owns the asset.
 * Monotonic per-slot counters avoid locks and prevent slot reuse before the final render release.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PcmOwnership(private val byteLimit: Long, initialProgram: EngineProgram) {
    private class Slot {
        @Volatile var asset: PcmAsset? = null
        @Volatile var acquired = 0L // producer only
        var consumed = 0L // renderer only
        @Volatile var released = 0L // renderer only
        var liveEpoch = 0L // renderer only
        val state = AtomicInt(0) // 0 free, 1 owned, 2 renderer retirement, 3 producer reservation
    }
    private val slots = Array(MAX_ASSETS) { Slot() }
    private val incoming: Array<PcmAsset?> = arrayOfNulls(EngineFormat.PAD_COUNT + Arrangement.MAX_CLIPS)
    private var epoch = 0L
    var reservationBusy = false
        private set
    val initialSlots: IntArray
    init {
        require(initialProgram.residentBytes <= byteLimit)
        initialSlots = reserve(initialProgram) ?: error("Initial PCM admission failed")
        consume(initialSlots)
    }

    fun reserve(program: EngineProgram): IntArray? {
        for (i in 0 until program.assetCount) incoming[i] = program.asset(i)
        return reserveIncoming(program.assetCount)
    }
    fun reserve(source: OriginalSource?): IntArray? {
        if (source == null) return IntArray(0)
        incoming[0] = source.asset
        return reserveIncoming(1)
    }
    private fun reserveIncoming(count: Int): IntArray? {
        reservationBusy = false
        val indices = IntArray(count)
        val states = IntArray(count)
        var bytes = 0L
        for (slot in slots) if (slot.state.load() != 0) bytes += slot.asset?.residentBytes ?: 0L
        for (i in 0 until count) {
            val asset = incoming[i]!!
            var found = -1
            for (index in slots.indices) if (slots[index].state.load() == 1 && slots[index].asset === asset) {
                var claimed = false
                for (prior in 0 until i) if (indices[prior] == index) { claimed = true; break }
                if (!claimed) { found = index; break }
            }
            if (found < 0) {
                for (index in slots.indices) {
                    if (slots[index].state.load() != 0) continue
                    var claimed = false
                    for (prior in 0 until i) if (indices[prior] == index) { claimed = true; break }
                    if (!claimed) { found = index; break }
                }
            }
            if (found < 0) { clearIncoming(count); return null }
            indices[i] = found
            states[i] = if (slots[found].asset === asset) 1 else 0
            if (states[i] == 0) bytes += asset.residentBytes
        }
        if (bytes > byteLimit) { clearIncoming(count); return null }
        for (i in 0 until count) {
            if (!slots[indices[i]].state.compareAndSet(states[i], 3)) {
                for (prior in 0 until i) slots[indices[prior]].state.store(states[prior])
                reservationBusy = true
                clearIncoming(count)
                return null
            }
        }
        for (i in 0 until count) {
            val slot = slots[indices[i]]
            slot.asset = incoming[i]
            slot.acquired++
            slot.state.store(1)
        }
        clearIncoming(count)
        return indices
    }
    private fun clearIncoming(count: Int) { for (i in 0 until count) incoming[i] = null }

    fun consume(indices: IntArray?) {
        if (indices != null) for (index in indices) slots[index].consumed++
    }
    fun beginRetention() { epoch++ }
    fun retain(index: Int) { if (index >= 0) slots[index].liveEpoch = epoch }
    fun finishRetention() {
        for (slot in slots) {
            if (slot.state.load() != 1) continue
            val keep = if (slot.liveEpoch == epoch) 1L else 0L
            slot.released = slot.consumed - keep
            if (keep == 0L && slot.consumed == slot.acquired && slot.state.compareAndSet(1, 2)) {
                // Producer may have acquired another lease before the CAS. Recheck under this claim.
                if (slot.consumed == slot.acquired) {
                    slot.asset = null
                    slot.state.store(0)
                } else slot.state.store(1)
            }
        }
    }
    companion object { const val MAX_ASSETS = 4096 }
}
