package com.choplab.desktop.model

enum class DesktopWorkflowStage(
    val stepNumber: Int,
    val japaneseLabel: String,
    val englishLabel: String,
) {
    CAPTURE(1, "入れる", "CAPTURE"),
    CHOP(2, "切る", "CHOP"),
    PERFORMANCE(3, "叩く", "PLAY"),
    ARRANGE(4, "並べる", "BEAT"),
    FINISH(5, "完成", "DONE"),
}

data class DesktopPadSlot(
    val globalIndex: Int,
    val bankIndex: Int,
    val indexInBank: Int,
    val indexInPage: Int,
    val localFile: String?,
) {
    val isAssigned: Boolean
        get() = !localFile.isNullOrBlank()
}

/** Desktop presentation state matching the Android deck's bank/page vocabulary. */
class DesktopDeckModel(
    val bankCount: Int = 4,
    val padsPerBank: Int = 32,
    val pageSize: Int = 16,
    val rows: Int = 4,
    val columns: Int = 4,
) {
    init {
        require(bankCount > 0) { "Deck must have at least one bank" }
        require(padsPerBank > 0 && padsPerBank % pageSize == 0) {
            "Pads per bank must be a positive multiple of page size"
        }
        require(pageSize == rows * columns) { "Page size must match the visible pad grid" }
    }

    val padCount: Int = bankCount * padsPerBank
    val pageCount: Int = padsPerBank / pageSize

    var selectedBank: Int = 0
        private set
    var selectedPage: Int = 0
        private set
    var selectedPad: Int = 0
        private set
    var workflowStage: DesktopWorkflowStage = DesktopWorkflowStage.CAPTURE
        private set
    var sourceFile: String? = null
        private set
    var sourcePlaying: Boolean = false
        private set
    var transportPlaying: Boolean = false
        private set
    var sourceKeySemitones: Int = 0
        private set
    var selectedPadKeySemitones: Int = 0
        private set
    var selectedPadTonePercent: Int = 72
        private set
    var selectedPadLevelPercent: Int = 90
        private set
    var bpm: Int = 92
        private set
    var swingPercent: Int = 54
        private set

    private val filesByPad = arrayOfNulls<String>(padCount)
    private val stepsByPad = Array(padCount) { BooleanArray(16) }

    fun visibleSlots(): List<DesktopPadSlot> {
        val start = selectedBank * padsPerBank + selectedPage * pageSize
        return (start until start + pageSize).map(::slot)
    }

    fun pad(globalIndex: Int): DesktopPadSlot {
        require(globalIndex in 0 until padCount) { "Pad $globalIndex is outside the deck" }
        return slot(globalIndex)
    }

    val selectedPadSlot: DesktopPadSlot
        get() = slot(selectedPad)

    fun selectBank(bankIndex: Int) {
        require(bankIndex in 0 until bankCount) { "Bank $bankIndex is outside the deck" }
        selectedBank = bankIndex
        selectedPad = bankIndex * padsPerBank + selectedPage * pageSize + selectedPadSlot.indexInPage
    }

    fun selectPage(pageIndex: Int) {
        require(pageIndex in 0 until pageCount) { "Page $pageIndex is outside the bank" }
        selectedPage = pageIndex
        selectedPad = selectedBank * padsPerBank + pageIndex * pageSize + selectedPadSlot.indexInPage
    }

    fun selectPad(globalIndex: Int) {
        require(globalIndex in 0 until padCount) { "Pad $globalIndex is outside the deck" }
        selectedPad = globalIndex
        selectedBank = globalIndex / padsPerBank
        selectedPage = (globalIndex % padsPerBank) / pageSize
    }

    fun setWorkflowStage(stage: DesktopWorkflowStage) {
        workflowStage = stage
    }

    fun canEnterStage(stage: DesktopWorkflowStage): Boolean = when (stage) {
        DesktopWorkflowStage.CAPTURE -> true
        DesktopWorkflowStage.CHOP,
        DesktopWorkflowStage.PERFORMANCE,
        -> sourceFile != null
        DesktopWorkflowStage.ARRANGE,
        DesktopWorkflowStage.FINISH,
        -> assignedCount() > 0
    }

    fun setSourceFile(path: String?) {
        sourceFile = path?.takeIf(String::isNotBlank)
        if (sourceFile == null) sourcePlaying = false
    }

    fun toggleSourcePlayback(): Boolean {
        if (sourceFile == null) return false
        sourcePlaying = !sourcePlaying
        return true
    }

    fun stopAll() {
        sourcePlaying = false
        transportPlaying = false
    }

    fun toggleTransport(): Boolean {
        transportPlaying = !transportPlaying
        return transportPlaying
    }

    fun assignLocalFile(globalIndex: Int, localFile: String) {
        require(localFile.isNotBlank()) { "Local audio file must not be blank" }
        require(globalIndex in 0 until padCount) { "Pad $globalIndex is outside the deck" }
        filesByPad[globalIndex] = localFile
    }

    fun assignSelectedPad(localFile: String) = assignLocalFile(selectedPad, localFile)

    fun clearAssignments() {
        filesByPad.fill(null)
        stepsByPad.forEach { java.util.Arrays.fill(it, false) }
    }

    fun assignedCountOnPage(pageIndex: Int): Int {
        require(pageIndex in 0 until pageCount) { "Page $pageIndex is outside the bank" }
        val start = selectedBank * padsPerBank + pageIndex * pageSize
        return (start until start + pageSize).count { filesByPad[it] != null }
    }

    fun assignedCount(): Int = filesByPad.count { it != null }

    fun toggleStep(globalIndex: Int, stepIndex: Int) {
        require(globalIndex in 0 until padCount) { "Pad $globalIndex is outside the deck" }
        require(stepIndex in 0 until 16) { "Step $stepIndex is outside the sequencer" }
        stepsByPad[globalIndex][stepIndex] = !stepsByPad[globalIndex][stepIndex]
    }

    fun isStepActive(globalIndex: Int, stepIndex: Int): Boolean {
        require(globalIndex in 0 until padCount) { "Pad $globalIndex is outside the deck" }
        require(stepIndex in 0 until 16) { "Step $stepIndex is outside the sequencer" }
        return stepsByPad[globalIndex][stepIndex]
    }

    fun activeStepsForSelectedPad(): Set<Int> = activeStepsForPad(selectedPad)

    fun activeStepsForPad(globalIndex: Int): Set<Int> {
        require(globalIndex in 0 until padCount) { "Pad $globalIndex is outside the deck" }
        return stepsByPad[globalIndex].indices.filterTo(linkedSetOf()) { stepsByPad[globalIndex][it] }
    }

    fun adjustSourceKey(delta: Int) {
        sourceKeySemitones = (sourceKeySemitones + delta).coerceIn(-24, 24)
    }

    fun adjustSelectedPadKey(delta: Int) {
        selectedPadKeySemitones = (selectedPadKeySemitones + delta).coerceIn(-24, 24)
    }

    fun adjustTone(delta: Int) {
        selectedPadTonePercent = (selectedPadTonePercent + delta).coerceIn(0, 100)
    }

    fun setSelectedPadTonePercent(value: Int) {
        selectedPadTonePercent = value.coerceIn(0, 100)
    }

    fun adjustLevel(delta: Int) {
        selectedPadLevelPercent = (selectedPadLevelPercent + delta).coerceIn(0, 100)
    }

    fun setSelectedPadLevelPercent(value: Int) {
        selectedPadLevelPercent = value.coerceIn(0, 100)
    }

    fun adjustBpm(delta: Int) {
        bpm = (bpm + delta).coerceIn(40, 240)
    }

    fun adjustSwing(delta: Int) {
        swingPercent = (swingPercent + delta).coerceIn(0, 100)
    }

    private fun slot(globalIndex: Int): DesktopPadSlot {
        val bankOffset = globalIndex % padsPerBank
        return DesktopPadSlot(
            globalIndex = globalIndex,
            bankIndex = globalIndex / padsPerBank,
            indexInBank = bankOffset,
            indexInPage = bankOffset % pageSize,
            localFile = filesByPad[globalIndex],
        )
    }
}
