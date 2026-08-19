package com.choplab.desktop.ui

import com.choplab.desktop.audio.LocalAudioPlayer
import com.choplab.desktop.audio.WavWaveform
import com.choplab.desktop.model.DesktopDeckModel
import com.choplab.desktop.model.DesktopPadSlot
import com.choplab.desktop.model.DesktopWorkflowStage
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JSplitPane
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.KeyStroke
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import kotlin.math.max
import kotlin.math.min

internal object DesktopDeckPalette {
    val background = Color(15, 13, 8)
    val panel = Color(237, 226, 200)
    val panelDark = Color(211, 197, 165)
    val ink = Color(33, 29, 19)
    val lamp = Color(255, 116, 23)
    val green = Color(159, 212, 107)
    val pad = Color(38, 33, 22)
    val padAssigned = Color(56, 48, 29)
    val padLit = Color(255, 178, 94)
    val blackPanel = Color(23, 19, 13)
    val creamText = Color(255, 241, 207)
    val muted = Color(156, 144, 111)
}

private val DECK_FONT = Font("Yu Gothic UI", Font.BOLD, 14)
private val DECK_MONO = Font("Consolas", Font.BOLD, 14)
private val DECK_MONO_SMALL = Font("Consolas", Font.BOLD, 11)

class DesktopDeckPanel(
    private val player: LocalAudioPlayer,
    private val onLoadWav: () -> Unit,
    private val onSpotifyLogin: () -> Unit,
    private val onSpotifyCurrent: () -> Unit,
) : JPanel(BorderLayout()) {
    private val model = DesktopDeckModel()
    private val deckSurface = JPanel(BorderLayout(0, 9))
    private val statusLabel = JLabel()
    private val waveform = DeckWaveformView { progress ->
        showStatus("波形位置 ${"%.0f".format(progress * 100)}% — ここでPADを選べます")
    }
    private val padGrid = DeckPadGrid(model, ::handlePadPress)
    private val padEditor = DesktopPadEditor(model) { refreshPadState() }
    private val stepSequencer = DeckStepSequencer(model) { refreshPadState() }
    private var clearArmed = false
    private var spotifyStatus = "SPOTIFY: OFFLINE"
    private var lastStatusMessage = "ローカルWAVを読み込んでください"
    private var waveformProgress = 0f
    private val playbackTimer = Timer(70) {
        if (model.sourcePlaying && !player.isPlaying) {
            model.stopAll()
            showStatus("元曲の再生が終わりました")
            rebuild()
        } else if (model.sourcePlaying || model.transportPlaying) {
            waveformProgress = (waveformProgress + 0.006f) % 1f
            waveform.playhead = waveformProgress
            waveform.repaint()
        }
    }

    init {
        UIManager.put("Button.disabledText", DesktopDeckPalette.muted)
        background = DesktopDeckPalette.background
        border = EmptyBorder(12, 12, 12, 12)
        isFocusable = true
        getAccessibleContext().accessibleName = "ChopLab おとひろい desktop deck"
        getAccessibleContext().accessibleDescription = "5工程、ソース波形、PAD、arrangeを操作する制作デック"
        deckSurface.background = DesktopDeckPalette.panel
        deckSurface.border = BorderFactory.createCompoundBorder(
            LineBorder(Color(255, 248, 232), 1, true),
            EmptyBorder(10, 10, 10, 10),
        )
        statusLabel.horizontalAlignment = SwingConstants.LEFT
        installKeyboardShortcuts()
        playbackTimer.start()
        rebuild()
    }

    fun loadLocalWav(file: File) {
        runCatching {
            player.load(file)
            model.clearAssignments()
            model.setSourceFile(file.canonicalPath)
            model.setWorkflowStage(DesktopWorkflowStage.PERFORMANCE)
            waveform.setEnvelope(WavWaveform.read(file))
            waveformProgress = 0f
            waveform.playhead = 0f
            clearArmed = false
            showStatus("読み込み済み: ${file.name} — 空PADを叩くと、その瞬間が入ります")
            rebuild()
        }.onFailure { error -> showStatus("音声の読み込み失敗: ${error.message ?: error.javaClass.simpleName}") }
    }

    fun showExternalStatus(message: String) {
        if (message.contains("Spotify CONNECTED")) {
            spotifyStatus = "SPOTIFY: CONNECTED"
        }
        showStatus(message)
        rebuild()
    }

    fun close() {
        playbackTimer.stop()
    }

    private fun installKeyboardShortcuts() {
        val inputMap = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = getActionMap()
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), "open-wav")
        actionMap.put("open-wav", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = onLoadWav()
        })
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "stop-all")
        actionMap.put("stop-all", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = stopAll()
        })
        DesktopWorkflowStage.entries.forEachIndexed { index, stage ->
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_1 + index, InputEvent.CTRL_DOWN_MASK), "stage-$index")
            actionMap.put("stage-$index", object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent?) = selectStage(stage)
            })
        }
    }

    private fun rebuild() {
        padGrid.selectOnly = model.workflowStage == DesktopWorkflowStage.ARRANGE
        padGrid.refresh()
        padEditor.refresh()
        stepSequencer.refresh()

        deckSurface.removeAll()
        deckSurface.add(buildMachineHeader(), BorderLayout.NORTH)
        deckSurface.add(buildBody(), BorderLayout.CENTER)
        deckSurface.add(buildStatusStrip(), BorderLayout.SOUTH)
        removeAll()
        add(deckSurface, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun buildBody(): JPanel {
        val body = JPanel(BorderLayout(0, 9)).apply { background = DesktopDeckPalette.panel }
        body.add(buildWorkflowStrip(), BorderLayout.NORTH)

        val workspaceShell = JPanel(BorderLayout(0, 8)).apply { background = DesktopDeckPalette.panel }
        if (model.workflowStage != DesktopWorkflowStage.ARRANGE && model.workflowStage != DesktopWorkflowStage.FINISH) {
            workspaceShell.add(buildSourceActions(), BorderLayout.NORTH)
        }
        workspaceShell.add(buildWorkspace(), BorderLayout.CENTER)
        body.add(workspaceShell, BorderLayout.CENTER)

        if (model.workflowStage != DesktopWorkflowStage.CAPTURE && model.workflowStage != DesktopWorkflowStage.FINISH) {
            body.add(buildDeckBottom(), BorderLayout.SOUTH)
        } else {
            body.add(buildProductionDock(), BorderLayout.SOUTH)
        }
        return body
    }

    private fun buildMachineHeader(): JPanel = JPanel(BorderLayout(10, 0)).apply {
        background = DesktopDeckPalette.ink
        border = EmptyBorder(7, 10, 7, 10)
        preferredSize = Dimension(0, 54)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { background = DesktopDeckPalette.ink }
        left.add(StatusLamp(model.sourcePlaying || model.transportPlaying))
        left.add(JLabel("OTOHIROI").apply {
            foreground = DesktopDeckPalette.creamText
            font = Font("Consolas", Font.BOLD, 18)
        })
        left.add(JLabel("${model.workflowStage.japaneseLabel} / ${model.workflowStage.englishLabel}").apply {
            foreground = DesktopDeckPalette.muted
            font = DECK_FONT.deriveFont(11f)
        })
        add(left, BorderLayout.WEST)

        add(JLabel("${bankLetter(model.selectedBank)} ${model.bpm} BPM  /  $spotifyStatus").apply {
            foreground = if (spotifyStatus.contains("CONNECTED")) DesktopDeckPalette.green else DesktopDeckPalette.lamp
            font = DECK_MONO_SMALL
            horizontalAlignment = SwingConstants.RIGHT
        }, BorderLayout.CENTER)

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply { background = DesktopDeckPalette.ink }
        actions.add(darkButton("SPOTIFY LOGIN") { onSpotifyLogin() })
        actions.add(darkButton("CURRENT") { onSpotifyCurrent() })
        actions.add(darkButton("音を全停止\nALL STOP") { stopAll() })
        add(actions, BorderLayout.EAST)
    }

    private fun buildWorkflowStrip(): JPanel = JPanel(GridLayout(1, DesktopWorkflowStage.entries.size, 6, 0)).apply {
        background = DesktopDeckPalette.panel
        preferredSize = Dimension(0, 58)
        DesktopWorkflowStage.entries.forEach { stage ->
            val enabled = stageEnabled(stage)
            val button = machineButton(
                label = "${stage.stepNumber}\n${stage.japaneseLabel}",
                action = { selectStage(stage) },
                active = model.workflowStage == stage,
                dark = false,
            )
            button.isEnabled = enabled
            button.toolTipText = "${stage.stepNumber} ${stage.japaneseLabel} / ${stage.englishLabel}"
            add(button)
        }
    }

    private fun buildSourceActions(): JPanel = JPanel(GridLayout(1, 4, 7, 0)).apply {
        background = DesktopDeckPalette.panel
        preferredSize = Dimension(0, 64)
        add(machineButton("曲を読込 / LOAD", action = { onLoadWav() }))
        add(machineButton("マイク録音 / MIC REC", action = {
            showStatus("MIC REC は Windows版の次の音声入力マイルストーンで対応します")
        }))
        add(machineButton("端末を録音 / DEVICE REC", action = {
            showStatus("DEVICE REC は Windows版では未対応です。ローカルWAVを使ってください")
        }))
        add(machineButton(
            if (clearArmed) "PAD・ビート消去\nもう一度" else "チョップ全消去 / CLEAR",
            action = { clearAssignments() },
            active = clearArmed,
        ))
    }

    private fun buildWorkspace(): JPanel = when (model.workflowStage) {
        DesktopWorkflowStage.CAPTURE -> buildCaptureWorkspace()
        DesktopWorkflowStage.ARRANGE -> buildArrangeWorkspace()
        DesktopWorkflowStage.FINISH -> buildFinishWorkspace()
        DesktopWorkflowStage.CHOP,
        DesktopWorkflowStage.PERFORMANCE,
        -> buildPerformanceWorkspace()
    }

    private fun buildCaptureWorkspace(): JPanel {
        val panel = JPanel(GridBagLayout()).apply { background = DesktopDeckPalette.panel }
        val waveformCard = blackPanel(BorderLayout(8, 8)).apply {
            add(waveform, BorderLayout.CENTER)
            add(label("NO SOURCE\nLOAD OR RECORD AUDIO", DesktopDeckPalette.muted, DECK_MONO), BorderLayout.SOUTH)
        }
        val captureCard = blackPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createCompoundBorder(
                LineBorder(DesktopDeckPalette.ink, 2, true),
                EmptyBorder(14, 14, 14, 14),
            )
            add(label("1. 音を入れる", DesktopDeckPalette.green, DECK_MONO), BorderLayout.NORTH)
            add(label("ファイル、マイク、端末音声から1つ選びます\n\nこのPC版の最初の対応はローカルWAVです。", DesktopDeckPalette.creamText, DECK_FONT), BorderLayout.CENTER)
            add(machineButton("曲を読込\nFILE", action = { onLoadWav() }), BorderLayout.SOUTH)
        }
        addGrid(panel, waveformCard, 0, 0, 1, 1, 1.2, 1.0)
        addGrid(panel, captureCard, 1, 0, 1, 1, 0.8, 1.0)
        return panel
    }

    private fun buildPerformanceWorkspace(): JPanel {
        val left = JPanel(BorderLayout(8, 8)).apply { background = DesktopDeckPalette.panel }
        val coach = blackPanel(BorderLayout(8, 0)).apply {
            preferredSize = Dimension(0, 62)
            add(label("☻", DesktopDeckPalette.green, Font("Consolas", Font.BOLD, 28)), BorderLayout.WEST)
            add(label("ここだ、でPADを叩くと、\nその瞬間が入ります", DesktopDeckPalette.green, DECK_FONT), BorderLayout.CENTER)
        }
        left.add(coach, BorderLayout.NORTH)
        left.add(waveform, BorderLayout.CENTER)
        left.add(buildSourceTransport(), BorderLayout.SOUTH)

        val right = JPanel(BorderLayout(8, 8)).apply { background = DesktopDeckPalette.panel }
        right.add(buildPageStrip(), BorderLayout.NORTH)
        right.add(padGrid, BorderLayout.CENTER)
        right.add(label("PAD ${padNumber(model.selectedPad)}  /  ${model.selectedPadSlot.localFile?.let(::fileName) ?: "空"}", DesktopDeckPalette.ink, DECK_FONT.deriveFont(11f)), BorderLayout.SOUTH)

        return splitWorkspace(left, right, 0.56)
    }

    private fun buildArrangeWorkspace(): JPanel {
        padGrid.selectOnly = true
        padGrid.refresh()
        val padChooser = JPanel(BorderLayout(0, 8)).apply {
            background = DesktopDeckPalette.panel
            add(label("パッドを選ぶ", DesktopDeckPalette.ink, DECK_FONT), BorderLayout.NORTH)
            add(padGrid, BorderLayout.CENTER)
        }
        val sequencer = JPanel(BorderLayout(8, 8)).apply {
            background = DesktopDeckPalette.panel
            add(label("ステップシーケンサー (PAD ${padNumber(model.selectedPad)})", DesktopDeckPalette.ink, DECK_FONT), BorderLayout.NORTH)
            add(stepSequencer, BorderLayout.CENTER)
            add(buildBeatControls(), BorderLayout.SOUTH)
        }
        return splitWorkspace(padChooser, sequencer, 0.43)
    }

    private fun buildBeatControls(): JPanel = JPanel(GridLayout(1, 5, 6, 0)).apply {
        background = DesktopDeckPalette.panel
        add(valueStepper("速さ / BPM", model.bpm.toString(), { model.adjustBpm(-1); refreshPadState() }, { model.adjustBpm(1); refreshPadState() }))
        add(valueStepper("跳ね / SWING", "${model.swingPercent}%", { model.adjustSwing(-1); refreshPadState() }, { model.adjustSwing(1); refreshPadState() }))
        add(machineButton(if (model.transportPlaying) "ビート停止\nSTOP" else "ビート再生\nPLAY", { toggleTransport() }, active = model.transportPlaying))
        add(machineButton("● REC", action = { showStatus("REC はデスクトップ版の次のマイルストーンです") }))
        add(machineButton("ALL STOP", action = { stopAll() }))
    }

    private fun buildFinishWorkspace(): JPanel = JPanel(BorderLayout(10, 10)).apply {
        background = DesktopDeckPalette.panel
        val card = blackPanel(BorderLayout(10, 10)).apply {
            border = BorderFactory.createCompoundBorder(
                LineBorder(DesktopDeckPalette.ink, 2, true),
                EmptyBorder(24, 24, 24, 24),
            )
            add(label("5. 完成", DesktopDeckPalette.lamp, Font("Consolas", Font.BOLD, 26)), BorderLayout.NORTH)
            add(label("PADとビートを確認しました。\nローカルWAVの試作を続けるか、次の保存マイルストーンへ進めます。", DesktopDeckPalette.green, DECK_FONT), BorderLayout.CENTER)
            val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { background = DesktopDeckPalette.blackPanel }
            actions.add(machineButton("制作を保存\nSAVE PROJECT", action = { showStatus("SAVE PROJECT は次のローカルプロジェクトマイルストーンです") }))
            actions.add(machineButton("制作を開く\nOPEN PROJECT", action = { showStatus("OPEN PROJECT は次のローカルプロジェクトマイルストーンです") }))
            add(actions, BorderLayout.SOUTH)
        }
        add(card, BorderLayout.CENTER)
    }

    private fun buildSourceTransport(): JPanel = JPanel(BorderLayout(6, 0)).apply {
        background = DesktopDeckPalette.panel
        preferredSize = Dimension(0, 58)
        add(machineButton(
            if (model.sourcePlaying) "元曲を止める\nSTOP SOURCE" else "曲を再生\nPLAY SONG",
            action = { toggleSourcePlayback() },
            active = model.sourcePlaying,
        ), BorderLayout.CENTER)
        add(valueStepper("曲の高さ / KEY", signed(model.sourceKeySemitones), { model.adjustSourceKey(-1); refreshPadState() }, { model.adjustSourceKey(1); refreshPadState() }), BorderLayout.EAST)
    }

    private fun buildPageStrip(): JPanel = JPanel(GridLayout(1, model.pageCount, 6, 0)).apply {
        background = DesktopDeckPalette.panel
        preferredSize = Dimension(0, 56)
        repeat(model.pageCount) { page ->
            val first = page * model.pageSize + 1
            val last = first + model.pageSize - 1
            add(machineButton(
                "${if (model.selectedPage == page) "● " else ""}PAD %02d–%02d\n%d音".format(first, last, model.assignedCountOnPage(page)),
                action = { model.selectPage(page); rebuild() },
                active = model.selectedPage == page,
            ))
        }
    }

    private fun buildDeckBottom(): JPanel = JPanel().apply {
        background = DesktopDeckPalette.panel
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        add(padEditor)
        add(javax.swing.Box.createVerticalStrut(7))
        add(buildBankDock())
        add(javax.swing.Box.createVerticalStrut(7))
        add(buildProductionDock())
    }

    private fun buildBankDock(): JPanel = JPanel(GridLayout(1, 6, 6, 0)).apply {
        background = DesktopDeckPalette.panel
        preferredSize = Dimension(0, 50)
        add(darkButton("■  ALL STOP") { stopAll() })
        add(darkButton("BANK") { showStatus("BANK A–Dを選べます") })
        repeat(model.bankCount) { bank ->
            add(darkButton(bankLetter(bank), active = model.selectedBank == bank) {
                model.selectBank(bank)
                rebuild()
            })
        }
    }

    private fun buildProductionDock(): JPanel = JPanel(GridLayout(1, if (model.workflowStage == DesktopWorkflowStage.ARRANGE) 3 else 2, 7, 0)).apply {
        background = DesktopDeckPalette.panel
        preferredSize = Dimension(0, 56)
        add(machineButton("🎧  聴いてみる", action = { audition() }, active = model.sourcePlaying || model.transportPlaying, dark = false, orange = true))
        add(machineButton(nextActionLabel(), action = { advanceStage() }, dark = false, orange = false))
        if (model.workflowStage == DesktopWorkflowStage.ARRANGE) {
            add(darkButton("☷\nくわしい設定") { showStatus("詳細設定はAndroid版のPAD editor vocabularyに合わせて段階追加します") })
        }
    }

    private fun buildStatusStrip(): JPanel = JPanel(BorderLayout(8, 0)).apply {
        background = DesktopDeckPalette.ink
        border = EmptyBorder(6, 8, 6, 8)
        preferredSize = Dimension(0, 32)
        add(StatusLamp(model.sourcePlaying || model.transportPlaying), BorderLayout.WEST)
        statusLabel.foreground = statusColor(lastStatusMessage)
        statusLabel.font = DECK_FONT.deriveFont(11f)
        statusLabel.text = "${model.workflowStage.japaneseLabel} / ${model.workflowStage.englishLabel}  —  $lastStatusMessage"
        add(statusLabel, BorderLayout.CENTER)
    }

    private fun refreshPadState() {
        padGrid.refresh()
        padEditor.refresh()
        stepSequencer.refresh()
        statusLabel.repaint()
        repaint()
    }

    private fun selectStage(stage: DesktopWorkflowStage) {
        if (!stageEnabled(stage)) {
            showStatus("この工程は、前の工程を先に進めてください")
            return
        }
        model.setWorkflowStage(stage)
        showStatus("${stage.stepNumber} ${stage.japaneseLabel} / ${stage.englishLabel}")
        rebuild()
    }

    private fun stageEnabled(stage: DesktopWorkflowStage): Boolean = model.canEnterStage(stage)

    private fun advanceStage() {
        when (model.workflowStage) {
            DesktopWorkflowStage.CAPTURE -> selectStage(DesktopWorkflowStage.CHOP)
            DesktopWorkflowStage.CHOP -> selectStage(DesktopWorkflowStage.PERFORMANCE)
            DesktopWorkflowStage.PERFORMANCE -> selectStage(DesktopWorkflowStage.ARRANGE)
            DesktopWorkflowStage.ARRANGE -> selectStage(DesktopWorkflowStage.FINISH)
            DesktopWorkflowStage.FINISH -> selectStage(DesktopWorkflowStage.PERFORMANCE)
        }
    }

    private fun nextActionLabel(): String = when (model.workflowStage) {
        DesktopWorkflowStage.CAPTURE -> "次へ：切る  ›"
        DesktopWorkflowStage.CHOP,
        DesktopWorkflowStage.PERFORMANCE,
        -> "次へ：並べる  ›"
        DesktopWorkflowStage.ARRANGE -> "完成へ  ›"
        DesktopWorkflowStage.FINISH -> "叩くへ  ›"
    }

    private fun handlePadPress(globalIndex: Int) {
        model.selectPad(globalIndex)
        if (!padGrid.selectOnly && !model.pad(globalIndex).isAssigned && model.sourceFile != null) {
            model.assignSelectedPad(model.sourceFile!!)
            showStatus("PAD ${padNumber(globalIndex)} に ${fileName(model.sourceFile!!)} を割り当てました")
        }
        val slot = model.pad(globalIndex)
        if (slot.isAssigned && !padGrid.selectOnly) {
            runCatching {
                model.stopAll()
                val file = File(slot.localFile!!)
                if (player.loadedFile?.canonicalFile != file.canonicalFile) player.load(file)
                player.play()
                showStatus("PAD ${padNumber(globalIndex)} を再生中: ${file.name}")
            }.onFailure { error -> showStatus("PAD再生失敗: ${error.message ?: error.javaClass.simpleName}") }
        } else if (padGrid.selectOnly) {
            showStatus("PAD ${padNumber(globalIndex)} を選択しました")
        }
        rebuild()
    }

    private fun toggleSourcePlayback() {
        if (model.sourceFile == null) {
            showStatus("先に曲を読込 / LOADしてください")
            return
        }
        runCatching {
            if (model.sourcePlaying) {
                player.stop()
                model.toggleSourcePlayback()
            } else {
                val source = File(model.sourceFile!!)
                if (player.loadedFile?.canonicalFile != source.canonicalFile) player.load(source)
                player.play()
                model.toggleSourcePlayback()
            }
            showStatus(if (model.sourcePlaying) "元曲を再生中。空PADを叩くと、その瞬間が入ります" else "元曲を停止しました")
            rebuild()
        }.onFailure { error -> showStatus("元曲の操作失敗: ${error.message ?: error.javaClass.simpleName}") }
    }

    private fun toggleTransport() {
        model.toggleTransport()
        showStatus(if (model.transportPlaying) "ビートを再生中" else "ビートを停止しました")
        rebuild()
    }

    private fun audition() {
        when {
            model.sourceFile != null -> toggleSourcePlayback()
            model.selectedPadSlot.isAssigned -> handlePadPress(model.selectedPad)
            else -> showStatus("先にローカルWAVを読み込んでPADを作ってください")
        }
    }

    private fun stopAll() {
        model.stopAll()
        player.stop()
        showStatus("すべて停止しました")
        rebuild()
    }

    private fun clearAssignments() {
        if (!clearArmed) {
            clearArmed = true
            showStatus("PADとビートを消去するにはCLEARをもう一度押してください")
            rebuild()
            return
        }
        model.clearAssignments()
        clearArmed = false
        model.setWorkflowStage(if (model.sourceFile == null) DesktopWorkflowStage.CAPTURE else DesktopWorkflowStage.CHOP)
        showStatus("PADとビートを消去しました")
        rebuild()
    }

    private fun showStatus(message: String) {
        lastStatusMessage = message
        statusLabel.text = message
        statusLabel.foreground = statusColor(message)
        statusLabel.repaint()
    }

    private fun statusColor(message: String): Color =
        if (message.contains("失敗") || message.contains("未対応")) Color(255, 142, 105) else DesktopDeckPalette.creamText

    private fun splitWorkspace(left: JPanel, right: JPanel, dividerRatio: Double): JPanel {
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
            border = null
            dividerSize = 8
            resizeWeight = dividerRatio
            background = DesktopDeckPalette.panel
            isContinuousLayout = true
        }
        return JPanel(BorderLayout()).apply {
            background = DesktopDeckPalette.panel
            add(split, BorderLayout.CENTER)
        }
    }

    private fun addGrid(
        parent: JPanel,
        child: JComponent,
        gridX: Int,
        gridY: Int,
        gridWidth: Int,
        gridHeight: Int,
        weightX: Double,
        weightY: Double,
    ) {
        parent.add(child, GridBagConstraints().apply {
            gridx = gridX
            gridy = gridY
            gridwidth = gridWidth
            gridheight = gridHeight
            weightx = weightX
            weighty = weightY
            fill = GridBagConstraints.BOTH
            insets = Insets(0, 0, 0, 8)
        })
    }

    private fun machineButton(
        label: String,
        action: () -> Unit,
        active: Boolean = false,
        dark: Boolean = false,
        orange: Boolean = false,
    ): JButton = JButton(html(label)).apply {
        isOpaque = true
        background = when {
            orange -> DesktopDeckPalette.lamp
            active -> DesktopDeckPalette.lamp
            dark -> DesktopDeckPalette.ink
            else -> DesktopDeckPalette.panelDark
        }
        foreground = if (dark || orange || active) {
            if (dark) DesktopDeckPalette.creamText else DesktopDeckPalette.ink
        } else {
            DesktopDeckPalette.ink
        }
        font = DECK_FONT
        isFocusPainted = true
        border = BorderFactory.createCompoundBorder(
            LineBorder(if (active || orange) DesktopDeckPalette.lamp else DesktopDeckPalette.ink, 2, true),
            EmptyBorder(4, 8, 4, 8),
        )
        addActionListener { action() }
        toolTipText = label.replace("\n", " / ")
        getAccessibleContext().accessibleName = label.replace("\n", " ")
        getAccessibleContext().accessibleDescription = toolTipText
    }

    private fun darkButton(label: String, active: Boolean = false, action: () -> Unit): JButton =
        machineButton(label, action, active = active, dark = true)

    private fun blackPanel(layout: java.awt.LayoutManager): JPanel = JPanel(layout).apply {
        background = DesktopDeckPalette.blackPanel
        border = BorderFactory.createCompoundBorder(
            LineBorder(Color.BLACK, 2, true),
            EmptyBorder(8, 10, 8, 10),
        )
    }

    private fun label(text: String, color: Color, font: Font): JLabel = JLabel(html(text)).apply {
        foreground = color
        this.font = font
        verticalAlignment = SwingConstants.CENTER
    }

    private fun valueStepper(title: String, value: String, decrease: () -> Unit, increase: () -> Unit): JPanel =
        JPanel(GridLayout(1, 3, 3, 0)).apply {
            background = DesktopDeckPalette.panel
            add(machineButton("−", decrease, dark = true))
            add(JPanel(BorderLayout()).apply {
                background = DesktopDeckPalette.blackPanel
                border = LineBorder(Color.BLACK, 1, true)
                add(label("$title\n$value", DesktopDeckPalette.green, DECK_FONT.deriveFont(10f)), BorderLayout.CENTER)
            })
            add(machineButton("+", increase, dark = true))
        }

    private fun StatusLamp(active: Boolean): JPanel = JPanel().apply {
        preferredSize = Dimension(12, 12)
        minimumSize = preferredSize
        maximumSize = preferredSize
        background = if (active) DesktopDeckPalette.lamp else Color(102, 92, 67)
        border = LineBorder(Color.BLACK, 1, true)
    }
}

private class DeckPadGrid(
    private val model: DesktopDeckModel,
    private val onPadPress: (Int) -> Unit,
) : JPanel(GridLayout(4, 4, 8, 8)) {
    var selectOnly: Boolean = false

    init {
        background = DesktopDeckPalette.panel
        border = EmptyBorder(2, 2, 2, 2)
        preferredSize = Dimension(500, 440)
        getAccessibleContext().accessibleName = "4 x 4 PAD grid"
        getAccessibleContext().accessibleDescription = "PADを選択、再生、ローカルWAVへ割り当てます"
    }

    fun refresh() {
        removeAll()
        model.visibleSlots().forEach { slot ->
            add(DeckPadButton(slot, model) { onPadPress(slot.globalIndex) })
        }
        revalidate()
        repaint()
    }
}

private class DeckPadButton(
    private val slot: DesktopPadSlot,
    private val deckModel: DesktopDeckModel,
    private val onActivate: () -> Unit,
) : JButton() {
    init {
        isFocusable = true
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        toolTipText = "PAD ${padNumber(slot.globalIndex)}${if (slot.isAssigned) " / ${fileName(slot.localFile!!)}" else " / 空"}"
        getAccessibleContext().accessibleName = toolTipText
        getAccessibleContext().accessibleDescription = if (slot.isAssigned) {
            "割り当て済みのPAD。EnterまたはSpaceで再生します"
        } else {
            "空のPAD。ソース読込後にEnterまたはSpaceで割り当てます"
        }
        preferredSize = Dimension(112, 94)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                requestFocusInWindow()
                onActivate()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER || event.keyCode == KeyEvent.VK_SPACE) onActivate()
            }
        })
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val selected = deckModel.selectedPad == slot.globalIndex
            val background = if (slot.isAssigned) DesktopDeckPalette.padAssigned else DesktopDeckPalette.pad
            g.paint = GradientPaint(0f, 0f, background.brighter(), 0f, height.toFloat(), background.darker())
            g.fillRoundRect(0, 0, width - 1, height - 1, 13, 13)
            g.color = if (selected) DesktopDeckPalette.lamp else Color.BLACK
            g.stroke = BasicStroke(if (selected) 3f else 2f)
            g.drawRoundRect(1, 1, width - 3, height - 3, 13, 13)

            g.color = if (selected) Color.WHITE else DesktopDeckPalette.creamText
            g.font = Font("Consolas", Font.BOLD, max(20, min(34, height / 3)))
            drawCentered(g, padNumber(slot.indexInBank), width / 2, height / 3)

            g.color = if (slot.isAssigned) DesktopDeckPalette.creamText else Color(160, 150, 132)
            g.font = DECK_FONT.deriveFont(11f)
            val centerText = when {
                !slot.isAssigned -> "空"
                width < 80 -> ""
                else -> fileName(slot.localFile!!).take(13)
            }
            drawCentered(g, centerText, width / 2, height / 2 + 8)

            if (slot.isAssigned) {
                g.color = DesktopDeckPalette.green
                val centerX = width / 2
                val baseY = height - 18
                val triangle = intArrayOf(centerX - 8, centerX - 8, centerX + 8)
                val triangleY = intArrayOf(baseY - 8, baseY + 8, baseY)
                g.fillPolygon(triangle, triangleY, 3)
            }
            if (isFocusOwner) {
                g.color = DesktopDeckPalette.creamText
                g.stroke = BasicStroke(1.5f)
                g.drawRoundRect(5, 5, width - 11, height - 11, 10, 10)
            }
        } finally {
            g.dispose()
        }
    }
}

private class DeckWaveformView(
    private val onSeek: (Float) -> Unit,
) : JPanel() {
    var playhead: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            repaint()
        }
    private var envelope = FloatArray(0)

    init {
        background = DesktopDeckPalette.blackPanel
        isFocusable = true
        getAccessibleContext().accessibleName = "ソース波形"
        getAccessibleContext().accessibleDescription = "クリック、または左右キーで再生位置を移動します"
        border = BorderFactory.createCompoundBorder(
            LineBorder(Color.BLACK, 2, true),
            EmptyBorder(6, 8, 6, 8),
        )
        preferredSize = Dimension(620, 230)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                requestFocusInWindow()
                val progress = (event.x.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                playhead = progress
                onSeek(progress)
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                val next = when (event.keyCode) {
                    KeyEvent.VK_LEFT -> playhead - 0.02f
                    KeyEvent.VK_RIGHT -> playhead + 0.02f
                    KeyEvent.VK_HOME -> 0f
                    KeyEvent.VK_END -> 1f
                    else -> return
                }
                playhead = next
                onSeek(playhead)
            }
        })
    }

    fun setEnvelope(values: FloatArray) {
        envelope = values
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val innerWidth = (width - 16).coerceAtLeast(1)
            val center = height / 2
            if (envelope.isEmpty()) {
                g.color = Color(48, 45, 34)
                g.fillRect(8, center - 1, innerWidth, 2)
                g.color = DesktopDeckPalette.muted
                g.font = DECK_MONO_SMALL
                drawCentered(g, "NO SOURCE / LOAD OR RECORD AUDIO", width / 2, center)
                if (isFocusOwner) {
                    g.color = DesktopDeckPalette.creamText
                    g.stroke = BasicStroke(2f)
                    g.drawRoundRect(3, 3, width - 7, height - 7, 7, 7)
                }
                return
            }
            g.color = DesktopDeckPalette.green
            val amplitude = max(12, height / 2 - 20).toFloat()
            envelope.forEachIndexed { index, value ->
                val x = 8 + (index.toFloat() / (envelope.size - 1).coerceAtLeast(1) * innerWidth).toInt()
                val bar = max(1, (value * amplitude).toInt())
                g.drawLine(x, center - bar, x, center + bar)
            }
            g.color = DesktopDeckPalette.lamp
            g.stroke = BasicStroke(2f)
            for (marker in 1..8) {
                val x = 8 + marker * innerWidth / 9
                g.drawLine(x, 20, x, height - 20)
                g.font = DECK_MONO_SMALL
                drawCentered(g, marker.toString(), x, 16)
            }
            val headX = 8 + (playhead * innerWidth).toInt()
            g.color = DesktopDeckPalette.creamText
            g.drawLine(headX, 5, headX, height - 5)
            g.fillOval(headX - 5, 2, 10, 10)
            g.color = DesktopDeckPalette.green
            g.font = DECK_FONT.deriveFont(11f)
            drawCentered(g, "タップでシーク", width / 2, height - 7)
            g.color = DesktopDeckPalette.muted
            drawAligned(g, "00:00.0", 12, height - 7, false)
            drawAligned(g, "SOURCE", width - 12, height - 7, true)
            if (isFocusOwner) {
                g.color = DesktopDeckPalette.creamText
                g.stroke = BasicStroke(2f)
                g.drawRoundRect(3, 3, width - 7, height - 7, 7, 7)
            }
        } finally {
            g.dispose()
        }
    }
}

private class DesktopPadEditor(
    private val model: DesktopDeckModel,
    private val onChanged: () -> Unit,
) : JPanel(BorderLayout(0, 6)) {
    private val title = JLabel()
    private val keyValue = JLabel()
    private val toneValue = JLabel()
    private val levelValue = JLabel()
    private val toneSlider = JSlider(0, 100)
    private val levelSlider = JSlider(0, 100)
    private var refreshing = false

    init {
        background = DesktopDeckPalette.panel
        border = BorderFactory.createCompoundBorder(
            LineBorder(DesktopDeckPalette.panelDark, 1, true),
            EmptyBorder(5, 5, 5, 5),
        )
        title.background = DesktopDeckPalette.blackPanel
        title.foreground = DesktopDeckPalette.lamp
        title.font = DECK_FONT.deriveFont(12f)
        title.isOpaque = true
        title.border = EmptyBorder(6, 10, 6, 10)
        title.getAccessibleContext().accessibleName = "選択PADエディター"
        title.getAccessibleContext().accessibleDescription = "選択中PADのKEY、TONE、LEVELを調整します"
        add(title, BorderLayout.NORTH)
        val columns = JPanel(GridLayout(1, 3, 7, 0)).apply { background = DesktopDeckPalette.panel }
        columns.add(keyColumn())
        columns.add(sliderColumn("音色 / TONE", toneSlider, toneValue) { value -> model.setSelectedPadTonePercent(value); onChanged() })
        columns.add(sliderColumn("音量 / LEVEL", levelSlider, levelValue) { value -> model.setSelectedPadLevelPercent(value); onChanged() })
        toneSlider.getAccessibleContext().accessibleName = "音色 TONE"
        toneSlider.getAccessibleContext().accessibleDescription = "選択PADの音色状態を0から100で調整します"
        levelSlider.getAccessibleContext().accessibleName = "音量 LEVEL"
        levelSlider.getAccessibleContext().accessibleDescription = "選択PADの音量状態を0から100で調整します"
        add(columns, BorderLayout.CENTER)
        refresh()
    }

    fun refresh() {
        refreshing = true
        val slot = model.selectedPadSlot
        title.text = "PAD ${padNumber(model.selectedPad)}  /  ${slot.localFile?.let(::fileName) ?: "空"}"
        keyValue.text = signed(model.selectedPadKeySemitones)
        toneValue.text = "${model.selectedPadTonePercent}%"
        levelValue.text = "${model.selectedPadLevelPercent}%"
        toneSlider.value = model.selectedPadTonePercent
        levelSlider.value = model.selectedPadLevelPercent
        refreshing = false
    }

    private fun keyColumn(): JPanel = parameterColumn("音の高さ / KEY").apply {
        add(JPanel(GridLayout(1, 3, 4, 0)).apply {
            background = DesktopDeckPalette.panel
            add(darkButton("−") { model.adjustSelectedPadKey(-1); onChanged() })
            add(valueLabel(keyValue))
            add(darkButton("+") { model.adjustSelectedPadKey(1); onChanged() })
        })
    }

    private fun sliderColumn(title: String, slider: JSlider, value: JLabel, onValue: (Int) -> Unit): JPanel =
        parameterColumn(title).apply {
            add(valueLabel(value))
            slider.background = DesktopDeckPalette.panel
            slider.foreground = DesktopDeckPalette.lamp
            slider.addChangeListener { if (!refreshing) onValue(slider.value) }
            add(slider)
        }

    private fun parameterColumn(title: String): JPanel = JPanel(BorderLayout(0, 4)).apply {
        background = DesktopDeckPalette.panel
        border = EmptyBorder(4, 7, 4, 7)
        add(JLabel(title, SwingConstants.CENTER).apply {
            foreground = DesktopDeckPalette.ink
            font = DECK_FONT
            getAccessibleContext().accessibleName = title
        }, BorderLayout.NORTH)
    }

    private fun valueLabel(label: JLabel): JPanel = JPanel(BorderLayout()).apply {
        background = DesktopDeckPalette.panel
        label.horizontalAlignment = SwingConstants.CENTER
        label.foreground = Color(55, 105, 27)
        label.font = Font("Consolas", Font.BOLD, 28)
        add(label, BorderLayout.CENTER)
    }

    private fun darkButton(label: String, action: () -> Unit): JButton = JButton(label).apply {
        background = DesktopDeckPalette.ink
        foreground = DesktopDeckPalette.creamText
        font = Font("Consolas", Font.BOLD, 18)
        isFocusPainted = true
        border = LineBorder(Color.BLACK, 2, true)
        addActionListener { action() }
        getAccessibleContext().accessibleName = label
        getAccessibleContext().accessibleDescription = "選択PADのKEYを調整します"
    }
}

private class DeckStepSequencer(
    private val model: DesktopDeckModel,
    private val onChanged: () -> Unit,
) : JPanel() {
    private var focusedStep = 0

    init {
        background = DesktopDeckPalette.blackPanel
        isFocusable = true
        getAccessibleContext().accessibleName = "16ステップシーケンサー"
        getAccessibleContext().accessibleDescription = "左右キーと上下キーでステップを選び、EnterまたはSpaceで切り替えます"
        border = BorderFactory.createCompoundBorder(
            LineBorder(Color.BLACK, 2, true),
            EmptyBorder(12, 12, 12, 12),
        )
        preferredSize = Dimension(560, 300)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                requestFocusInWindow()
                val cellWidth = width.toFloat() / 8f
                val cellHeight = height.toFloat() / 2f
                val column = (event.x / cellWidth).toInt().coerceIn(0, 7)
                val row = (event.y / cellHeight).toInt().coerceIn(0, 1)
                focusedStep = row * 8 + column
                model.toggleStep(model.selectedPad, row * 8 + column)
                onChanged()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                val next = when (event.keyCode) {
                    KeyEvent.VK_LEFT -> focusedStep - 1
                    KeyEvent.VK_RIGHT -> focusedStep + 1
                    KeyEvent.VK_UP -> focusedStep - 8
                    KeyEvent.VK_DOWN -> focusedStep + 8
                    KeyEvent.VK_HOME -> 0
                    KeyEvent.VK_END -> 15
                    KeyEvent.VK_ENTER,
                    KeyEvent.VK_SPACE,
                    -> {
                        model.toggleStep(model.selectedPad, focusedStep)
                        onChanged()
                        return
                    }
                    else -> return
                }
                focusedStep = next.coerceIn(0, 15)
                repaint()
            }
        })
    }

    fun refresh() = repaint()

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val gap = 5
            val left = 12
            val top = 14
            val cellWidth = (width - left * 2 - gap * 7) / 8
            val cellHeight = (height - top * 2 - gap) / 2
            (0 until 16).forEach { step ->
                val row = step / 8
                val column = step % 8
                val x = left + column * (cellWidth + gap)
                val y = top + row * (cellHeight + gap)
                val active = model.isStepActive(model.selectedPad, step)
                g.color = if (active) DesktopDeckPalette.green else DesktopDeckPalette.pad
                g.fillRoundRect(x, y, cellWidth, cellHeight, 9, 9)
                g.color = if (active) Color(50, 85, 20) else DesktopDeckPalette.panelDark
                g.stroke = BasicStroke(2f)
                g.drawRoundRect(x, y, cellWidth, cellHeight, 9, 9)
                g.color = if (active) DesktopDeckPalette.ink else DesktopDeckPalette.creamText
                g.font = Font("Consolas", Font.BOLD, max(16, min(28, cellHeight / 2)))
                drawCentered(g, (step + 1).toString(), x + cellWidth / 2, y + cellHeight / 2 + 8)
                if (isFocusOwner && focusedStep == step) {
                    g.color = DesktopDeckPalette.lamp
                    g.stroke = BasicStroke(3f)
                    g.drawRoundRect(x + 2, y + 2, cellWidth - 4, cellHeight - 4, 7, 7)
                }
            }
            g.color = DesktopDeckPalette.lamp
            g.stroke = BasicStroke(2f)
            val playheadX = left + 7 * (cellWidth + gap) + cellWidth / 2
            g.drawLine(playheadX, 6, playheadX, height - 6)
        } finally {
            g.dispose()
        }
    }
}

private fun html(text: String): String = "<html><div style='text-align:center'>" +
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") +
    "</div></html>"

private fun bankLetter(bank: Int): String = ('A'.code + bank.coerceIn(0, 3)).toChar().toString()

private fun padNumber(globalIndex: Int): String = "%02d".format((globalIndex % 32) + 1)

private fun fileName(path: String): String = File(path).name

private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

private fun drawCentered(g: Graphics2D, text: String, centerX: Int, baseline: Int) {
    val metrics: FontMetrics = g.fontMetrics
    g.drawString(text, centerX - metrics.stringWidth(text) / 2, baseline)
}

private fun drawAligned(g: Graphics2D, text: String, x: Int, baseline: Int, right: Boolean) {
    val drawX = if (right) x - g.fontMetrics.stringWidth(text) else x
    g.drawString(text, drawX, baseline)
}
