package com.choplab.desktop.model

/**
 * The first desktop surface: a bounded 4 x 4 pad bank containing local-file references.
 * Audio data is deliberately not stored in this model.
 */
class DesktopPadModel(
    val rows: Int = 4,
    val columns: Int = 4,
) {
    init {
        require(rows > 0 && columns > 0) { "Pad surface must have positive dimensions" }
    }

    val padCount: Int = rows * columns

    private val filesBySlot = arrayOfNulls<String>(padCount)

    fun assign(slot: Int, localFile: String) {
        checkSlot(slot)
        require(localFile.isNotBlank()) { "Local audio file must not be blank" }
        filesBySlot[slot] = localFile
    }

    fun fileFor(slot: Int): String? {
        checkSlot(slot)
        return filesBySlot[slot]
    }

    fun assignedSlots(): Set<Int> = filesBySlot.indices.filterTo(linkedSetOf()) { filesBySlot[it] != null }

    fun emptySlots(): Set<Int> = filesBySlot.indices.filterTo(linkedSetOf()) { filesBySlot[it] == null }

    private fun checkSlot(slot: Int) {
        if (slot !in filesBySlot.indices) {
            throw IndexOutOfBoundsException("Pad slot $slot is outside 0..${padCount - 1}")
        }
    }
}
