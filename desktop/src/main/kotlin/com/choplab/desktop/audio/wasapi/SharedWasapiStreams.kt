package com.choplab.desktop.audio.wasapi

import com.choplab.jvm.PcmMemoryBudget

/**
 * Production factory: WASAPI buffers compete with programs, SOURCE, cache and worker PCM in the same ledger.
 * Construction opens no device. The host still chooses the mode, obtains capture permission and handles failures
 * explicitly; this factory does not replace the current Java Sound route or start a fallback.
 */
fun createWasapiStreams(): WasapiStreams = WasapiStreams(SharedWasapiPcmMemory())

internal class SharedWasapiPcmMemory(private val memory: PcmMemoryBudget = PcmMemoryBudget.shared) : WasapiPcmMemory {
    override suspend fun reserve(bytes: Long): WasapiPcmReservation {
        val reservation = memory.reserve(bytes)
        return object : WasapiPcmReservation {
            override fun shrinkTo(bytes: Long) = reservation.shrinkTo(bytes)
            override fun close() = reservation.close()
        }
    }
}
