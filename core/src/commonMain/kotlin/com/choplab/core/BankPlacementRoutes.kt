package com.choplab.core

import com.choplab.core.model.*

/** Dry placed PAD performances keep the same BANK fader, pan and FX as live PADs. */
class BankPlacementRoutes(private val project: Project, private val freshId: (String) -> String,
                          private val trackName: (Bank) -> String = { it.name }) {
    var tracks: List<Track> = project.tracks
        private set
    var banks: List<Bank> = project.banks
        private set

    fun trackForPad(padId: Int): Track {
        require(padId in project.pads.indices)
        val bank = banks[padId / 16]
        bank.trackId?.let { id -> return tracks.first { it.id == id } }
        // Old unowned BANK tracks retain their existing clips and mix. They are not another BANK's route.
        val track = Track(freshId("track"), trackName(bank), TrackKind.BANK)
        tracks = tracks + track
        banks = banks.map { if (it.id == bank.id) it.copy(trackId = track.id) else it }
        return track
    }
}
