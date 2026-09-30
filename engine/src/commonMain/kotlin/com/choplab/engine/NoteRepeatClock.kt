package com.choplab.engine

/** Held-note timing starts at the press. Playback and captured phrases use the same rational clock. */
internal class NoteRepeatClock {
    private var tempo = Tempo()
    private var ticks = 240
    private var elapsed = 0L
    private var nextTick = 0L
    private var nextTarget = 0L

    fun reset(tempo: Tempo, ticks: Int) {
        require(validTicks(ticks))
        this.tempo = tempo
        this.ticks = ticks
        elapsed = 0; nextTick = 0; nextTarget = 0
    }

    fun framesUntilPulse(): Int {
        val distance = nextTarget - elapsed
        return if (distance <= 0) 0 else ((distance + tempo.milliBpm - 1) / tempo.milliBpm).toInt()
    }

    fun pulse(): Boolean {
        if (elapsed < nextTarget) return false
        nextTick += ticks
        // Every supported rate divides a beat. Carry the fractional frame remainder across beats,
        // keeping all arithmetic bounded even if a PAD remains held for days.
        if (nextTick >= EngineFormat.PPQ) {
            nextTick -= EngineFormat.PPQ
            elapsed -= EngineFormat.PPQ * SequenceClock.UNITS_PER_TICK
        }
        nextTarget = SequenceClock.targetNumerator(nextTick, tempo.swingPermille)
        return true
    }

    fun advance(frames: Int) { elapsed += frames.toLong() * tempo.milliBpm }

    companion object {
        fun validTicks(ticks: Int) = ticks == 960 || ticks == 480 || ticks == 320 || ticks == 240 || ticks == 160 || ticks == 120
    }
}
