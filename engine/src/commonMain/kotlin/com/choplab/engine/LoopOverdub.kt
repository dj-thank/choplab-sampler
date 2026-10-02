package com.choplab.engine

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos

/** Stable document route. The take stores raw PAD audio, before this track's fader and all mixer FX. */
data class LoopOverdubRoute(val trackId: String? = null, val gain: Float = 1f, val pan: Float = 0f) {
    init { require(gain.isFinite() && gain in 0f..8f && pan.isFinite() && pan in -1f..1f) }
    internal val leftGain = gain * if (pan > 0f) cos(pan * PI / 2).toFloat() else 1f
    internal val rightGain = gain * if (pan < 0f) cos(-pan * PI / 2).toFloat() else 1f
}

/**
 * Render owns one accumulator per route and only the forward quantize delay, reserved by the host.
 * Reading a loop sample precedes adding new audio to it: the addition first plays on the next pass.
 * Backward snaps can be added immediately; forward snaps wait until their target was read this pass.
 * SOURCE, HAND, click, existing clips, BANK faders/FX and master never enter these dry accumulators.
 */
class LoopOverdub(val startFrame: Long, val frames: Int, gridFrames: IntArray = intArrayOf(),
                  routes: List<LoopOverdubRoute> = listOf(LoopOverdubRoute())) {
    init { require(startFrame >= 0); memoryBytes(frames, gridFrames, routes.size); require(routes.map { it.trackId }.distinct().size == routes.size) }
    private val grid = gridFrames.copyOf()
    private val routing = routes.toTypedArray()
    val routeCount: Int get() = routing.size
    fun route(index: Int): LoopOverdubRoute = routing[index]
    private val pendingFrames = forwardDelay(frames, grid) + 1
    private var loops = Array(routeCount) { FloatArray(frames * 2) }
    private var pending = Array(routeCount) { FloatArray(pendingFrames * 2) }
    private val output = FloatArray(routeCount * 2)
    private val buses = IntArray(routeCount) { MixerProgram.UNROUTED_BUS }
    private val busRoutes = IntArray(MixerProgram.MAX_BUSES) { -1 }
    private val presses = IntArray(routeCount)
    @Volatile var completed = false
        private set
    @Volatile var elapsedFrames = 0L
        private set
    var acceptedPresses = 0
        private set
    fun acceptedPresses(route: Int): Int = presses[route]
    var interrupted = false
        private set
    var pcmMiss = false
        internal set
    private var stopping = -1
    private var folded = false
    val endFrame: Long get() = startFrame + frames
    internal val recording: Boolean get() = !completed && stopping < 0
    internal fun left(route: Int): Double = output[route * 2].toDouble() * routing[route].leftGain
    internal fun right(route: Int): Double = output[route * 2 + 1].toDouble() * routing[route].rightGain
    internal fun bus(route: Int): Int = buses[route]
    internal fun routeForBus(bus: Int): Int = busRoutes[bus]

    /** Bounded, allocation-free admission; stale routing must not record into a different BANK. */
    internal fun prepareRouting(program: EngineProgram): Boolean {
        updateRouting(program.mixer)
        for (i in routing.indices) if (routing[i].trackId != program.mixer.busId(buses[i])) return false
        for (id in 0 until EngineFormat.PAD_COUNT) {
            val pad = program.pad(id) ?: continue
            val route = routeForBus(pad.mixBus)
            if (route < 0 || routing[route].leftGain != pad.mixLeftGain || routing[route].rightGain != pad.mixRightGain) return false
        }
        return true
    }

    /** On a cancelling program swap, the final fade still addresses buses by stable track identity. */
    internal fun updateRouting(mixer: MixerProgram) {
        busRoutes.fill(-1)
        for (i in routing.indices) {
            val bus = mixer.busIndex(routing[i].trackId)
            buses[i] = bus; busRoutes[bus] = i
        }
    }

    internal fun press(route: Int = 0): Int {
        acceptedPresses++; presses[route]++
        if (grid.isEmpty()) return 0
        val phase = (elapsedFrames % frames).toInt()
        var lo = 0
        var hi = grid.lastIndex
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (grid[mid] <= phase) lo = mid else hi = mid }
        val target = if (phase - grid[lo] < grid[hi] - phase) grid[lo] else grid[hi]
        return (if (target == frames) 0 else target) - phase
    }

    internal fun beginFrame() {
        val index = (elapsedFrames % frames).toInt() * 2
        val arriving = (elapsedFrames % pendingFrames).toInt() * 2
        val gain = if (stopping >= 0) stopping.toFloat() / EngineCore.STEAL_FADE_FRAMES else 1f
        for (route in routing.indices) {
            val loop = loops[route]; val future = pending[route]
            output[route * 2] = loop[index] * gain; output[route * 2 + 1] = loop[index + 1] * gain
            // This phase was just read; forward-snapped additions now belong to its next occurrence.
            add(loop, index, future[arriving], future[arriving + 1])
            future[arriving] = 0f; future[arriving + 1] = 0f
        }
    }

    internal fun capture(left: Float, right: Float, shift: Int, route: Int = 0) {
        if (shift <= 0) {
            val index = ((elapsedFrames + shift) % frames).toInt() * 2
            add(loops[route], index, left, right)
        } else {
            val index = ((elapsedFrames + shift) % pendingFrames).toInt() * 2
            add(pending[route], index, left, right)
        }
    }

    private fun add(samples: FloatArray, index: Int, left: Float, right: Float) {
        val l = samples[index] + left; val r = samples[index + 1] + right
        if (!l.isFinite() || !r.isFinite()) { pcmMiss = true; return }
        samples[index] = l; samples[index + 1] = r
    }

    internal fun advance() { elapsedFrames++; if (stopping > 0 && --stopping == 0) completed = true }
    internal fun stop() { if (!completed && stopping < 0) stopping = EngineCore.STEAL_FADE_FRAMES }
    /** Render owner, including route loss. No disposal or allocation here. */
    internal fun detach() { interrupted = !completed; completed = true }

    /** Worker only after completed publishes the final render; each returned route is still dry. */
    fun samples(route: Int = 0): FloatArray {
        check(completed)
        if (!folded) {
            for (r in routing.indices) for (ahead in 0 until pendingFrames) {
                val at = ((elapsedFrames + ahead) % pendingFrames).toInt() * 2
                val target = ((elapsedFrames + ahead) % frames).toInt() * 2
                add(loops[r], target, pending[r][at], pending[r][at + 1])
                pending[r][at] = 0f; pending[r][at + 1] = 0f
            }
            check(!pcmMiss) { "Invalid loop PCM" }
            folded = true
        }
        return loops[route]
    }

    /** Host only after render has relinquished ownership or admission was refused. */
    fun dispose() { loops = emptyArray(); pending = emptyArray() }

    companion object {
        const val MAX_FRAMES = 2_304_000 // Eight 4/4 bars at 40 BPM, 48 kHz.
        const val MAX_RECORDING_FRAMES = 300L * 48_000
        const val MAX_ROUTES = 8
        private fun forwardDelay(frames: Int, grid: IntArray): Int {
            require(frames in 1..MAX_FRAMES && grid.size <= 257)
            require(grid.isEmpty() || (grid.size >= 2 && grid.first() == 0 && grid.last() == frames))
            var result = 0
            for (i in 1 until grid.size) {
                require(grid[i] > grid[i - 1])
                if (i < grid.lastIndex) result = maxOf(result, (grid[i] - grid[i - 1]) / 2)
            }
            return result
        }
        fun memoryBytes(frames: Int, grid: IntArray = intArrayOf(), routeCount: Int = 1): Long {
            require(routeCount in 1..MAX_ROUTES)
            return (frames.toLong() + forwardDelay(frames, grid) + 1) * 8 * routeCount + 256 * 1024
        }
    }
}
