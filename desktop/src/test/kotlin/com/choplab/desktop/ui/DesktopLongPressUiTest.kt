@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.choplab.desktop.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.choplab.desktop.DesktopSamplerController
import com.choplab.desktop.audio.DesktopAudioRecorder
import com.choplab.desktop.audio.DesktopPreparedLoopSession
import com.choplab.desktop.audio.DesktopSamplerAudioEngine
import com.choplab.desktop.persistence.DesktopProjectFiles
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.ui.OtohiroiDeck
import com.choplab.sampler.ui.theme.ChopLabTheme
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val H13_UI_TIMEOUT_MILLIS = 30_000L

/** Component evidence on the JVM/Skiko input stack, not OS pointer or physical audio evidence. */
class DesktopLongPressUiTest {
    @Test
    fun connectPanelOpensTheImportDestinationAndKeepsSetupOutOfTheMainView() = runBlocking {
        for(width in listOf(760,1100)) {
            val destination=androidx.compose.runtime.mutableStateOf<com.choplab.sampler.source.SourceSection?>(null)
            var logins=0
            val scene=ImageComposeScene(width=width,height=660,density=Density(1f),coroutineContext=coroutineContext) {
                ChopLabTheme {
                    val selected=destination.value
                    if(selected==null) com.choplab.desktop.SpotifyPanel(
                        com.choplab.desktop.provider.SpotifyDesktopState(phase=com.choplab.desktop.provider.SpotifyConnectionPhase.READY,clientIdConfigured=true),
                        4,{destination.value=it},{},{},{},{},
                    ) else com.choplab.sampler.ui.AudioSourceHubContent(
                        state=com.choplab.sampler.source.AudioSourceState(section=selected),canUseAudio=true,
                        onSection={destination.value=it},onQuery={},onSearch={},onDownload={},onPickFiles={},onUse={},onCancel={},onClose={destination.value=null},
                        spotifyContent={com.choplab.sampler.ui.SpotifySourcePicker(
                            com.choplab.sampler.source.SpotifyImportState(configured=true),false,"http://127.0.0.1/callback",
                            {logins++},{},{},{},{})},
                    )
                }
            }
            try {
                fun nodes():List<SemanticsNode> = buildList {
                    fun visit(n:SemanticsNode){add(n);n.children.forEach(::visit)}
                    scene.semanticsOwners.forEach{visit(it.unmergedRootSemanticsNode)}
                }
                suspend fun settle() {repeat(12){scene.render(System.nanoTime()).close();delay(15)}}
                suspend fun clickDescription(text:String) {
                    settle()
                    val n=nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text)==true }
                    val position=n.boundsInRoot.center
                    assertTrue(position.x>0 && position.x<width && position.y>0 && position.y<660)
                    scene.sendPointerEvent(PointerEventType.Move,position,type=PointerType.Mouse)
                    scene.sendPointerEvent(PointerEventType.Press,position,type=PointerType.Mouse,buttons=PointerButtons(isPrimaryPressed=true),button=PointerButton.Primary)
                    settle()
                    scene.sendPointerEvent(PointerEventType.Release,position,type=PointerType.Mouse,buttons=PointerButtons(),button=PointerButton.Primary)
                    settle()
                }
                settle()
                assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text->text.text.contains("Client ID")||text.text.contains("Developer Dashboard") }==true })
                val output=File(requireNotNull(System.getProperty("h13.evidenceDir"))).apply{mkdirs()}
                scene.render(System.nanoTime()).use { image->requireNotNull(image.encodeToData()).use { data->File(output,"desktop-connect-$width.png").writeBytes(data.bytes) } }
                clickDescription("Spotifyのお気に入りから音源を追加")
                assertEquals(com.choplab.sampler.source.SourceSection.SPOTIFY,destination.value)
                val login=nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { text->text.text=="Spotifyにログイン" }==true }
                val clickable=generateSequence(login){it.parent}.first { it.config.getOrNull(SemanticsActions.OnClick)?.action!=null }
                assertTrue(requireNotNull(clickable.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
                assertEquals(1,logins)
                destination.value=null;clickDescription("YouTubeから音源を追加")
                assertEquals(com.choplab.sampler.source.SourceSection.YOUTUBE,destination.value)
                destination.value=null;clickDescription("PCのファイルから音源を追加")
                assertEquals(com.choplab.sampler.source.SourceSection.LIBRARY,destination.value)
                destination.value=null;clickDescription("内部ライブラリを開く")
                assertEquals(com.choplab.sampler.source.SourceSection.LIBRARY,destination.value)
            } finally {scene.close()}
        }
    }

    @Test
    fun audioSourceHubShowsLibraryAndOneTapFavoritesAtCompactAndWideWidths() = runBlocking {
        for(width in listOf(390,960)) {
            var chosen=""
            val track=com.choplab.sampler.source.SourceTrack("テスト曲","テスト奏者","https://open.spotify.com/track/0123456789012345678901",120.0)
            val scene=ImageComposeScene(width=width,height=720,density=Density(1f),coroutineContext=coroutineContext) {
                ChopLabTheme {
                    com.choplab.sampler.ui.AudioSourceHubContent(
                        state=com.choplab.sampler.source.AudioSourceState(section=com.choplab.sampler.source.SourceSection.SPOTIFY),
                        canUseAudio=true,onSection={},onQuery={},onSearch={},onDownload={},onPickFiles={},onUse={},onCancel={},onClose={},
                        spotifyContent={com.choplab.sampler.ui.SpotifySourcePicker(
                            com.choplab.sampler.source.SpotifyImportState(connected=true,configured=true,tracks=listOf(track),message="お気に入りを取得しました"),false,"choplab://spotify/callback",
                            {},{},{},{chosen=it.title},{})},
                    )
                }
            }
            try {
                fun nodes():List<SemanticsNode> = buildList {
                    fun visit(node:SemanticsNode){add(node);node.children.forEach(::visit)}
                    scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
                }
                var card:SemanticsNode?=null
                withTimeout(10000) {
                    while(card==null) {
                        scene.render(System.nanoTime()).close()
                        card=nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text ->text.startsWith("Spotifyのお気に入り ") }==true && it.boundsInRoot.height>0 }
                        if(card==null)delay(20)
                    }
                }
                val node=requireNotNull(card)
                assertTrue(node.boundsInRoot.bottom<=720f)
                assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
                assertEquals("テスト曲",chosen)
                val directory=File(requireNotNull(System.getProperty("h13.evidenceDir"))).apply{mkdirs()}
                scene.render(System.nanoTime()).use { image ->requireNotNull(image.encodeToData()).use { data ->File(directory,"source-favorites-$width.png").writeBytes(data.bytes) } }
            } finally {scene.close()}
        }
    }

    @Test
    fun editedChopJoinsPreviousLoopWhenContinuingToBeat() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.controller.startPadLoop(0)
                fixture.mousePress("PAD 02 割り当て済み", 700)
                fixture.mousePress("この音を回してビートへ", 40)
                assertEquals(0, fixture.controller.state.value.loopingPadIndex)
                assertEquals(PadPlayMode.LOOP, fixture.controller.state.value.pads[1].playMode)
                assertTrue(fixture.controller.state.value.transportPlaying)
                assertTrue(fixture.nodeWithDescription("選択範囲の波形").stateDescription().contains("16000から32000"))
                fixture.capture("chosen-loop-handoff")
            } finally { fixture.close() }
        }
    }

    @Test
    fun rejectedLoopHandoffKeepsTheEditorAndPreviousLoop() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.controller.startPadLoop(0)
                fixture.mousePress("PAD 02 割り当て済み", 700)
                val before = fixture.controller.state.value
                fixture.audio.failNextTrigger = true
                fixture.mousePress("この音を回してビートへ", 40)
                assertEquals(0, fixture.controller.state.value.loopingPadIndex)
                assertEquals(before.pads, fixture.controller.state.value.pads)
                assertEquals(before.canUndo, fixture.controller.state.value.canUndo)
                assertTrue(fixture.hasDescription("切り出した音へ戻る"))
                assertFalse(fixture.hasDescription("ドラムを足す"))
            } finally { fixture.close() }
        }
    }

    @Test
    fun compactBeatKeepsWaveformAndControlsReachable() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext, viewportWidth = 360, viewportHeight = 520, fontScale = 1.3f)
            try {
                fixture.mousePress("工程3", 40)
                fixture.reveal("選択音をループ", 48f)
                fixture.mousePress("選択音をループ", 40)
                assertEquals(0, fixture.controller.state.value.loopingPadIndex)
                fixture.capture("compact-loop-beat")
                fixture.reveal("選択範囲の波形", 80f)
                assertTrue(fixture.nodeWithDescription("選択範囲の波形").boundsInRoot.height >= 80f)
                fixture.capture("compact-loop-waveform")
                for (label in listOf("S 始まり", "E 終わり", "ドラムを足す", "スクラッチ")) {
                    fixture.reveal(label, 48f)
                    val bounds = fixture.nodeWithDescription(label).boundsInRoot
                    assertTrue(bounds.width >= 48f && bounds.height >= 48f && bounds.bottom <= 520f, "$label: $bounds")
                }
            } finally { fixture.close() }
        }
    }

    @Test
    fun selectThenLoopAndAddAnotherSoundWithoutRestartingTheCore() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.mousePress("工程3", 40)
                fixture.mousePress("音を選ぶ A-02", 40)
                assertEquals(null, fixture.controller.state.value.loopingPadIndex)
                assertTrue(fixture.audio.loopRequests.isEmpty())
                fixture.mousePress("選択音をループ", 40)
                assertEquals(1, fixture.controller.state.value.loopingPadIndex)
                val coreStarts = fixture.audio.loopRequests.count { it.globalIndex == 1 }
                fixture.mousePress("音を選ぶ A-01", 40)
                assertEquals(1, fixture.controller.state.value.loopingPadIndex)
                assertEquals(PadPlayMode.ONE_SHOT, fixture.controller.state.value.pads[0].playMode)
                fixture.mousePress("選択音をループ", 40)
                assertEquals(PadPlayMode.LOOP, fixture.controller.state.value.pads[0].playMode)
                assertEquals(1, fixture.controller.state.value.loopingPadIndex)
                assertEquals(coreStarts, fixture.audio.loopRequests.count { it.globalIndex == 1 })
                assertTrue(fixture.hasDescription("重ねている音 A-01を選ぶ"))
                assertTrue(fixture.hasDescription("重ねている音 A-02を選ぶ"))
                fixture.capture("select-loop-add-layers")
                fixture.mousePress("A-01のループを外す", 40)
                assertEquals(PadPlayMode.ONE_SHOT, fixture.controller.state.value.pads[0].playMode)
                assertEquals(1, fixture.controller.state.value.loopingPadIndex)
                assertEquals(coreStarts, fixture.audio.loopRequests.count { it.globalIndex == 1 })
            } finally { fixture.close() }
        }
    }

    @Test
    fun failedRechopStartKeepsSelectionHistoryAndTheCurrentEditor() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.controller.setRangeEnd(20000)
                fixture.mousePress("PAD 02 割り当て済み", 700)
                val before = fixture.controller.state.value
                fixture.audio.failNextSourcePlay = true
                fixture.mousePress("元曲全体のチョップ地図", 40)
                val after = fixture.controller.state.value
                assertEquals(before.selectedPad, after.selectedPad)
                assertEquals(before.rangeEndFrame, after.rangeEndFrame)
                assertEquals(before.canUndo, after.canUndo)
                assertFalse(after.sourcePlaying)
                assertTrue(fixture.hasDescription("選択範囲の波形"))
            } finally { fixture.close() }
        }
    }

    @Test
    fun selectedRangeAutomaticallyFillsTheEditorWithoutZoomOrPrecisionButtons() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext, viewportWidth = 360, viewportHeight = 800, fontScale = 1.3f,
                targetStart = 76000, targetEnd = 80000)
            try {
                fixture.mousePress("PAD 02 割り当て済み", 700)
                val wave = fixture.nodeWithDescription("選択範囲の波形")
                assertTrue(wave.description().contains("S 0:09.500、E 0:10.000"))
                assertTrue(wave.stateDescription().contains("76000から80000フレーム"))
                for (removed in listOf("波形を拡大", "波形を縮小", "トリム精度", "細かく調整", "終わりを")) {
                    assertFalse(fixture.hasDescription(removed), removed)
                }
                for (label in listOf("S 始まり", "E 終わり", "ループを回す")) {
                    val bounds = fixture.nodeWithDescription(label).boundsInRoot
                    assertTrue(bounds.height >= 48f && bounds.bottom <= 800f && bounds.right <= 360f, "$label $bounds")
                }
                val before = fixture.controller.state.value.pads[1]
                fixture.mousePress(wave, 40)
                assertEquals(before, fixture.controller.state.value.pads[1])
                fixture.capture("automatic-range-portrait")
            } finally { fixture.close() }
        }
    }

    @Test
    fun rollingEndRebindsLiveLoopAndRefitsTheDisplayedRange() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.mousePress("PAD 02 割り当て済み", 700)
                fixture.mousePress("ループを回す", 40)
                val dial = fixture.nodeWithDescription("E 終わり")
                requireNotNull(dial.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, -60f)
                fixture.settle(600)
                val after = fixture.controller.state.value.pads[1]
                assertTrue(after.endFrame > 32000)
                assertEquals(16000, after.startFrame)
                assertEquals(1, fixture.controller.state.value.loopingPadIndex)
                assertEquals(after.endFrame, fixture.audio.loopRequests.last().endFrame)
                assertTrue(fixture.nodeWithDescription("選択範囲の波形").stateDescription().contains("16000から${after.endFrame}フレーム"))
                fixture.capture("automatic-range-live-dial")
            } finally { fixture.close() }
        }
    }

    @Test
    fun fullSourceMapRechopsFromTheTappedPositionAndRetainsEarlierCuts() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.controller.setRangeEnd(20000)
                val originals = fixture.controller.state.value.pads.take(2).map { listOf(it.globalIndex, it.audio?.id, it.startFrame, it.endFrame) }
                fixture.mousePress("PAD 02 割り当て済み", 700)
                fixture.mousePress("元曲全体のチョップ地図", 40, fractionX = 0.5f)
                assertTrue(fixture.controller.state.value.sourcePlaying)
                assertEquals(40000, fixture.controller.state.value.sourcePlayheadFrame)
                assertEquals(80000, fixture.controller.state.value.rangeEndFrame)
                assertEquals(originals, fixture.controller.state.value.pads.take(2).map { listOf(it.globalIndex, it.audio?.id, it.startFrame, it.endFrame) })
                fixture.mousePress("PAD 03 空", 40)
                assertEquals(40000, fixture.controller.state.value.pads[2].startFrame)
                assertEquals(originals, fixture.controller.state.value.pads.take(2).map { listOf(it.globalIndex, it.audio?.id, it.startFrame, it.endFrame) })
                fixture.controller.rollPadBoundary(2, com.choplab.sampler.model.PadTrimBoundary.END, -800)
                val trimmedEnd = fixture.controller.state.value.pads[2].endFrame
                fixture.audio.advanceSourceTo(60000)
                fixture.mousePress("PAD 04 空", 40)
                assertEquals(trimmedEnd, fixture.controller.state.value.pads[2].endFrame)
                fixture.controller.setSelectedPadStartFrame(60100)
                fixture.controller.setSelectedPadEndFrame(79000)
                fixture.audio.advanceSourceTo(70000)
                fixture.mousePress("PAD 05 空", 40)
                assertEquals(60100, fixture.controller.state.value.pads[3].startFrame)
                assertEquals(79000, fixture.controller.state.value.pads[3].endFrame)
                fixture.capture("automatic-range-rechop")
            } finally { fixture.close() }
        }
    }

    @Test
    fun drumSoundChangeKeepsThePlayingLoopAndEditedRhythm() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.mousePress("工程3", 40)
                fixture.mousePress("ドラムを足す", 40)
                fixture.mousePress("Bに音色をセット", 40)
                fixture.controller.toggleStep(0)
                fixture.controller.toggleStep(3)
                fixture.controller.duplicateSelectedPatternToOther()
                fixture.controller.toggleStep(15)
                fixture.controller.startPadLoop(1, withPattern = true)
                assertTrue(fixture.controller.setPadLoopLayer(32, true))
                val before = fixture.controller.state.value
                val rhythm = before.activeSteps
                val other = before.patternArrangement.storedStepsBySlot[0]
                val starts = fixture.audio.loopRequests.size
                val sound = before.pads[32].audio?.id
                fixture.mousePress("BOOM BAP ドラムキット", 40)
                fixture.mousePress("リズムを保って音色変更", 40)
                assertEquals(sound, fixture.controller.state.value.pads[32].audio?.id)
                fixture.mousePress("もう一度で音色変更", 40)
                val after = fixture.controller.state.value
                assertTrue(sound != after.pads[32].audio?.id)
                assertEquals(rhythm, after.activeSteps)
                assertEquals(other, after.patternArrangement.storedStepsBySlot[0])
                assertEquals(1, after.loopingPadIndex)
                assertTrue(after.transportPlaying)
                assertTrue(32 in fixture.audio.stoppedPads)
                assertTrue(1 !in fixture.audio.stoppedPads)
                assertEquals(PadPlayMode.ONE_SHOT, after.pads[32].playMode)
                assertEquals(starts, fixture.audio.loopRequests.size)
                fixture.capture("drum-sound-change-preserves-rhythm")
            } finally { fixture.close() }
        }
    }

    @Test
    fun beatKeepsItsLoopRunningAcrossSelectionDrumsAndBoundaryRolls() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.mousePress("工程3", 40)
                fixture.mousePress("音を選ぶ A-02", 40)
                fixture.mousePress("選択音をループ", 40)
                fixture.controller.toggleTransport()
                assertEquals(1, fixture.controller.state.value.loopingPadIndex)
                assertTrue(fixture.controller.state.value.transportPlaying)
                val starts = fixture.audio.loopRequests.size
                fixture.mousePress("音を選ぶ A-02", 40)
                assertEquals(starts, fixture.audio.loopRequests.size)
                fixture.mousePress("ドラムを足す", 40)
                fixture.mousePress("Bに音色をセット", 40)
                assertEquals(1, fixture.controller.state.value.loopingPadIndex)
                assertTrue(fixture.controller.state.value.transportPlaying)
                fixture.mousePress("閉じる", 40)
                val drum = fixture.controller.state.value.pads[32]
                fixture.mousePress("重ねている音 A-02を選ぶ", 40)
                // Input targets the rendered selection, after the flow collector has
                // replaced the former drum's dial semantics and scroll callback.
                withTimeout(5_000) {
                    while (!fixture.nodeWithDescription("S 始まり").stateDescription().contains("16000フレーム")) {
                        fixture.settle(20)
                    }
                }
                assertEquals(1, fixture.controller.state.value.selectedPad)
                val dial = fixture.nodeWithDescription("S 始まり")
                requireNotNull(dial.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, -30f)
                fixture.settle(600)
                assertTrue(fixture.controller.state.value.pads[1].startFrame > 16000,
                    "Selected PAD ${fixture.controller.state.value.selectedPad}, dial ${dial.stateDescription()}")
                assertEquals(drum, fixture.controller.state.value.pads[32])
                assertEquals(1, fixture.controller.state.value.loopingPadIndex)
                assertTrue(fixture.controller.state.value.transportPlaying)
                fixture.capture("loop-first-beat")
                fixture.mousePress("スクラッチ", 40)
                assertEquals(1, fixture.controller.state.value.selectedPad)
                fixture.capture("loop-first-scratch")
                fixture.controller.stopAllSounds()
            } finally { fixture.close() }
        }
    }

    @Test
    fun chopAndDrumKitControlsExplainAndRejectBusyDisplayState() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.displayOverride.value = { it.copy(isLoading = true) }
                fixture.settle()
                for (label in listOf("音声を読込中", "微調整", "音を足す", "スクラッチ")) {
                    assertTrue(fixture.nodeWithDescription(label).config.contains(SemanticsProperties.Disabled), label)
                }
                fixture.capture("audit-chop-loading")
                fixture.mousePress("工程3", 40)
                for (label in listOf("ドラムを足す", "スクラッチ", "選択音をループ", "S 始まり", "E 終わり")) {
                    assertTrue(fixture.nodeWithDescription(label).config.contains(SemanticsProperties.Disabled), label)
                }
                fixture.capture("audit-beat-loading")
                fixture.displayOverride.value = null
                fixture.settle()
                fixture.mousePress("ドラムを足す", 40)
                fixture.displayOverride.value = { it.copy(recordingSession = com.choplab.sampler.model.RecordingSession.Active(
                    com.choplab.sampler.model.RecordingKind.SOURCE_MICROPHONE,
                    com.choplab.sampler.model.RecordingPhase.RECORDING)) }
                fixture.settle()
                val apply = fixture.nodeWithDescription("Bに音色をセット")
                assertTrue(apply.config.contains(SemanticsProperties.Disabled))
                val before = fixture.controller.state.value.pads
                fixture.mousePress(apply, 40)
                assertEquals(before, fixture.controller.state.value.pads)
                fixture.capture("audit-drums-recording")
            } finally { fixture.close() }
        }
    }

    @Test
    fun selectedPadClearDisarmsWhenSelectionChanges() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.controller.toggleStep(0)
                fixture.controller.selectPlayablePad(1)
                fixture.controller.toggleStep(4)
                fixture.controller.selectPlayablePad(0)
                fixture.mousePress("工程3", 40)
                fixture.mousePress("ドラムを足す", 40)
                fixture.mousePress("SOUNDS", 40)
                fixture.mousePress("配置を消す", 40)
                fixture.controller.selectPlayablePad(1)
                fixture.settle()
                assertTrue(fixture.hasDescription("配置を消す"))
                fixture.mousePress("配置を消す", 40)
                assertEquals(setOf(0, 20), fixture.controller.state.value.activeSteps)
                fixture.mousePress("もう一度で削除", 40)
                assertEquals(setOf(0), fixture.controller.state.value.activeSteps)
            } finally { fixture.close() }
        }
    }

    @Test
    fun arrangementCopyDisarmsAfterContentChangesAndTimeout() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.controller.toggleStep(0)
                fixture.controller.duplicateSelectedPatternToOther()
                fixture.controller.toggleStep(4)
                fixture.controller.selectPatternVariation(0)
                fixture.mousePress("工程3", 40)
                fixture.mousePress("配置・曲構成", 40)
                fixture.mousePress("曲にする", 40)
                val originalB = fixture.controller.state.value.patternArrangement.storedStepsBySlot[1]
                fixture.mousePress("AをBへコピー", 40)
                fixture.controller.toggleStep(8)
                fixture.settle()
                assertTrue(fixture.hasDescription("AをBへコピー"))
                fixture.mousePress("AをBへコピー", 40)
                assertEquals(originalB, fixture.controller.state.value.patternArrangement.storedStepsBySlot[1])
                fixture.settle(4200)
                assertTrue(fixture.hasDescription("AをBへコピー"))
                fixture.mousePress("AをBへコピー", 40)
                assertEquals(originalB, fixture.controller.state.value.patternArrangement.storedStepsBySlot[1])
                fixture.mousePress("Bを上書き", 40)
                assertEquals(1, fixture.controller.state.value.patternArrangement.selectedSlot)
                assertEquals(setOf(0, 8), fixture.controller.state.value.activeSteps)
                fixture.capture("audit-arrangement-confirmation")
            } finally { fixture.close() }
        }
    }

    @Test
    fun portraitSaveActionsRemainTouchSized() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext, viewportWidth = 360, viewportHeight = 800, fontScale = 1.3f)
            try {
                fixture.controller.toggleStep(0)
                fixture.mousePress("工程4", 40)
                fixture.capture("audit-save-portrait")
                assertFalse(fixture.hasDescription("ビート配置を消す"))
                assertFalse(fixture.hasDescription("ビートへ戻る"))
                for (label in listOf("ビートを確認", "WAVを書き出す", "制作を保存", "制作を開く", "1つ戻す", "やり直す")) {
                    val bounds = fixture.nodeWithDescription(label).boundsInRoot
                    assertTrue(bounds.height >= 48f, "$label must remain touch-sized: $bounds")
                    assertTrue(bounds.left >= 0 && bounds.right <= 360 && bounds.bottom <= 800, "$label outside viewport: $bounds")
                }
            } finally { fixture.close() }
        }
    }

    @Test
    fun compactLayerEditorKeepsShiftControlsReachable() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext, viewportHeight = 520)
            try {
                fixture.controller.selectPlayablePad(0)
                fixture.controller.toggleStep(0)
                fixture.mousePress("工程3", 40)
                fixture.reveal("ドラムを足す")
                fixture.mousePress("ドラムを足す", 40)
                fixture.capture("pattern-layer-compact-open")
                fixture.mousePress("SOUNDS", 40)
                fixture.capture("pattern-layer-compact-top")
                fixture.scrollDown()
                val button = fixture.nodeWithDescription("1ステップ後へ")
                assertTrue(button.boundsInRoot.height >= 48f)
                assertTrue(button.boundsInRoot.top >= 0f && button.boundsInRoot.bottom <= 520f)
                fixture.mousePress(button, 40)
                assertEquals(setOf(com.choplab.sampler.model.stepKey(0, 1)), fixture.controller.state.value.activeSteps)
                fixture.capture("pattern-layer-compact-shift")
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun liveSourceMouseClickStillCapturesAnEmptyPadThroughTheRealController() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.mousePress("チョップ開始", 40)
                assertTrue(fixture.controller.state.value.sourcePlaying)
                fixture.audio.advanceSourceTo(24_000)
                fixture.mousePress("PAD 03 空", 40)
                fixture.capture("live-source-empty-click")

                assertEquals(2, fixture.controller.state.value.selectedPad)
                assertEquals(24_000, fixture.controller.state.value.pads[2].startFrame)
                assertEquals(80_000, fixture.controller.state.value.pads[2].endFrame)
                assertTrue(fixture.controller.state.value.sourcePlaying)
                assertFalse(fixture.hasDescription("選択範囲の波形"))
                assertTrue(fixture.audio.padRequests.isEmpty(), "Live capture must not request a PAD voice")
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun unassignedPadMouseClickAndHoldOnlySelectWithoutTrimOrAudio() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            for (holdMillis in listOf(40L, 700L)) {
                val fixture = DeckFixture.create(coroutineContext)
                try {
                    fixture.mousePress("PAD 03 空", holdMillis)
                    fixture.capture("unassigned-$holdMillis")

                    assertEquals(2, fixture.controller.state.value.selectedPad)
                    assertFalse(fixture.controller.state.value.pads[2].isAssigned)
                    assertFalse(fixture.hasDescription("選択範囲の波形"))
                    assertTrue(fixture.audio.padRequests.isEmpty())
                    fixture.assertOffscreen()
                } finally {
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun assignedPadOrdinaryMouseClickAuditionsWithoutOpeningTrimOrEditing() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.mousePress("PAD 02 割り当て済み", 40)
                fixture.capture("assigned-short-click")

                assertEquals(1, fixture.controller.state.value.selectedPad)
                assertEquals(16_000, fixture.controller.state.value.pads[1].startFrame)
                assertEquals(32_000, fixture.controller.state.value.pads[1].endFrame)
                assertFalse(fixture.hasDescription("選択範囲の波形"))
                assertEquals(listOf(1), fixture.audio.padRequests)
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun assignedPadLongPressStillOpensTrimWhenAuditionStartupFails() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.audio.failNextTrigger = true

                fixture.mousePress("PAD 02 割り当て済み", 700)

                assertEquals(16_000, fixture.controller.state.value.pads[1].startFrame)
                assertEquals(32_000, fixture.controller.state.value.pads[1].endFrame)
                assertTrue(fixture.hasDescription("選択範囲の波形"))
                assertTrue(fixture.hasDescription("選択範囲の波形"))
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun gateSemanticsClickReleasesItsExactAuditionVoice() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext, targetPlayMode = PadPlayMode.GATE)
            try {
                fixture.semanticsClick(fixture.nodeWithDescription("PAD 02 割り当て済み"))
                fixture.settle(150)

                assertEquals(listOf(1), fixture.audio.padRequests)
                assertEquals(listOf(1 to 1L), fixture.audio.releasedOwnedPads)
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun rapidGateSemanticsClicksReleaseTheirOwnVoices() = runBlocking {
        withTimeout(H13_UI_TIMEOUT_MILLIS) {
            val fixture = DeckFixture.create(coroutineContext, targetPlayMode = PadPlayMode.GATE)
            try {
                val pad = fixture.nodeWithDescription("PAD 02 割り当て済み")
                fixture.semanticsClick(pad)
                fixture.settle(20)
                fixture.semanticsClick(fixture.nodeWithDescription("PAD 02 割り当て済み"))
                fixture.settle(150)

                assertEquals(listOf(1, 1), fixture.audio.padRequests)
                assertEquals(listOf(1 to 1L, 1 to 2L), fixture.audio.releasedOwnedPads)
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

}

private class DeckFixture private constructor(
    val controller: DesktopSamplerController,
    val audio: SilentAudioPort,
    private val scene: ImageComposeScene,
    private val directory: File,
    private val project: File,
    val displayOverride: androidx.compose.runtime.MutableState<((SamplerUiState) -> SamplerUiState)?>,
) : AutoCloseable {
    private val inputTrace = mutableListOf<String>()
    private fun nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) {
            add(node)
            node.children.forEach(::visit)
        }
        scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }

    fun nodeWithDescription(prefix: String): SemanticsNode {
        val matches = nodes().filter { it.description().startsWith(prefix) }
        assertEquals(1, matches.size, "Expected one '$prefix' node; found ${matches.map { it.description() }}")
        return matches.single()
    }

    fun hasDescription(prefix: String): Boolean = nodes().any { it.description().startsWith(prefix) }

    private suspend fun readyNodeWithDescription(prefix: String): SemanticsNode {
        var lastMatches: List<SemanticsNode> = emptyList()
        repeat(100) {
            scene.render(System.nanoTime()).close()
            lastMatches = nodes().filter { it.description().startsWith(prefix) }
            val ready = lastMatches.singleOrNull()?.takeIf { node ->
                val bounds = node.boundsInRoot
                bounds.left.isFinite() &&
                    bounds.top.isFinite() &&
                    bounds.right.isFinite() &&
                    bounds.bottom.isFinite() &&
                    bounds.width > 0f &&
                    bounds.height > 0f &&
                    bounds.left >= 0f &&
                    bounds.top >= 0f &&
                    bounds.right <= 1_100f &&
                    bounds.bottom <= 1_000f
            }
            if (ready != null) return ready
            delay(10)
        }
        error(
            "Timed out waiting for one laid-out '$prefix' node; " +
                "matches=${lastMatches.map { node -> "${node.description()}@${node.boundsInRoot}" }}",
        )
    }

    fun semanticsClick(node: SemanticsNode) {
        val action = requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action)
        assertTrue(action())
    }

    suspend fun mousePress(node: SemanticsNode, holdMillis: Long, fractionX: Float = 0.5f) {
        val bounds = node.boundsInRoot
        val position = Offset(bounds.left + bounds.width * fractionX, bounds.center.y)
        val pressedAt = System.nanoTime()
        inputTrace += "Mouse Move/Press target=${node.description()} bounds=$bounds position=$position holdRequestedMs=$holdMillis"
        scene.sendPointerEvent(PointerEventType.Move, position, type = PointerType.Mouse)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            type = PointerType.Mouse,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        settle(holdMillis)
        inputTrace += "Mouse Release position=$position elapsedMs=${(System.nanoTime() - pressedAt) / 1_000_000}"
        scene.sendPointerEvent(
            PointerEventType.Release,
            position,
            type = PointerType.Mouse,
            buttons = PointerButtons(),
            button = PointerButton.Primary,
        )
        settle(100)
    }

    suspend fun mousePress(description: String, holdMillis: Long, fractionX: Float = 0.5f) {
        mousePress(readyNodeWithDescription(description), holdMillis, fractionX)
    }

    suspend fun reveal(description: String, minimumHeight: Float = 48f) {
        val initial = nodes().singleOrNull { it.description().startsWith(description) }
        if (initial != null && initial.boundsInRoot.height >= minimumHeight) return
        scrollDown(-10000f)
        repeat(24) {
            val node = nodes().singleOrNull { it.description().startsWith(description) }
            if (node != null && node.boundsInRoot.height >= minimumHeight) return
            scrollDown(80f)
        }
        error("Could not scroll $description into view")
    }

    suspend fun scrollDown(distance: Float = 1000f) {
        val scrollNodes = nodes().filter { node ->
            node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                node.config.getOrNull(SemanticsProperties.Role) != androidx.compose.ui.semantics.Role.Button
        }
        val ranges = scrollNodes.mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
        scrollNodes.forEach { it.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, distance) }
        // Semantics ScrollBy animates. Wait for geometry to settle so the next pointer
        // click reaches its button instead of merely cancelling the pending scroll.
        var positions = ranges.map { it.value() }
        var stableFrames = 0
        repeat(100) {
            settle(20)
            val next = ranges.map { it.value() }
            stableFrames = if (next == positions) stableFrames + 1 else 0
            if (stableFrames >= 8) return
            positions = next
        }
        error("Scroll animation did not settle")
    }

    suspend fun settle(durationMillis: Long = 100) {
        val deadline = System.nanoTime() + durationMillis * 1_000_000
        do {
            scene.render(System.nanoTime()).close()
            delay(10)
        } while (System.nanoTime() < deadline)
        scene.render(System.nanoTime()).close()
    }

    fun capture(label: String) {
        val output = File(requireNotNull(System.getProperty("h13.evidenceDir")))
        output.mkdirs()
        val image = scene.render(System.nanoTime())
        try {
            val data = requireNotNull(image.encodeToData())
            try {
                File(output, "$label.png").writeBytes(data.bytes)
            } finally {
                data.close()
            }
        } finally {
            image.close()
        }
        val state = controller.state.value
        File(output, "$label.txt").writeText(
            buildString {
                appendLine("platform=JVM ImageComposeScene; input=Mouse; headless=${GraphicsEnvironment.isHeadless()}")
                appendLine("os=${System.getProperty("os.name")} ${System.getProperty("os.version")}; java=${System.getProperty("java.version")}; renderer=${System.getProperty("skiko.renderApi")}")
                appendLine("uiRuntime=${ImageComposeScene::class.java.protectionDomain.codeSource.location}")
                appendLine("selectedPad=${state.selectedPad}; targetRange=${state.pads[1].startFrame}..${state.pads[1].endFrame}")
                appendLine("silentPadRequests=${audio.padRequests}; realAudioImplementation=false")
                inputTrace.forEach(::appendLine)
                nodes().forEach { node ->
                    val description = node.description()
                    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
                    if (description.isNotEmpty() || text.isNotEmpty()) {
                        appendLine("${node.boundsInRoot}: description=$description; state=${node.stateDescription()}; text=$text")
                    }
                }
            },
            Charsets.UTF_8,
        )
    }

    fun assertOffscreen() {
        assertTrue(GraphicsEnvironment.isHeadless())
        assertFalse(Window.getWindows().any { it.isVisible }, "The H13 fixture must never create a visible window")
    }

    fun assertInsideScene(node: SemanticsNode) {
        val bounds = node.boundsInRoot
        assertTrue(bounds.width > 0f && bounds.height > 0f, "Rendered control must have non-empty bounds: $bounds")
        assertTrue(
            bounds.left >= 0f && bounds.top >= 0f && bounds.right <= 1_100f && bounds.bottom <= 1_000f,
            "Rendered control must fit inside its actual offscreen viewport: $bounds",
        )
    }

    override fun close() {
        try {
            scene.close()
        } finally {
            try {
                controller.close()
            } finally {
                try {
                    Files.deleteIfExists(project.toPath())
                } finally {
                    Files.deleteIfExists(directory.toPath())
                }
            }
        }
    }

    companion object {
        suspend fun create(
            context: CoroutineContext,
            targetStart: Int = 16_000,
            targetEnd: Int = 32_000,
            targetPlayMode: PadPlayMode = PadPlayMode.ONE_SHOT,
            viewportHeight: Int = 1_000,
            viewportWidth: Int = 1_100,
            fontScale: Float = 1f,
        ): DeckFixture {
            check(GraphicsEnvironment.isHeadless()) { "Use :desktop:desktopLongPressUiTest, not an interactive launcher" }
            val temporaryRoot = File(System.getProperty("java.io.tmpdir"))
            val directory = Files.createTempDirectory(temporaryRoot.toPath(), "h13-input-").toFile()
            val audio = PcmAudio(
                name = "H13 synthetic source",
                samples = ShortArray(80_000) { frame -> ((frame % 80 - 40) * 150).toShort() },
                sampleRate = 8_000,
            )
            val input = SamplerUiState(
                currentAudio = audio,
                rangeStartFrame = 0,
                rangeEndFrame = audio.frameCount,
                selectedBank = 0,
                selectedPad = 0,
                activeSteps = emptySet(),
                pads = List(SamplerConfig.PAD_COUNT) { index ->
                    when (index) {
                        0 -> PadModel(0, audio, 8_000, 12_000)
                        1 -> PadModel(1, audio, targetStart, targetEnd, playMode = targetPlayMode)
                        else -> PadModel(index)
                    }
                },
            )
            val project = DesktopProjectFiles.save(File(directory, "fixture.choplab"), input)
            val audioPort = SilentAudioPort()
            val controller = DesktopSamplerController(
                player = audioPort,
                microphone = ForbiddenRecorder(),
                systemAudio = ForbiddenRecorder(),
                autosaveStore = null,
            )
            val displayOverride = androidx.compose.runtime.mutableStateOf<((SamplerUiState) -> SamplerUiState)?>(null)
            var scene: ImageComposeScene? = null
            try {
                controller.openProject(project)
                withTimeout(5_000) {
                    while (controller.state.value.isLoading) delay(10)
                }
                check(controller.state.value.pads[1].startFrame == targetStart && controller.state.value.pads[1].endFrame == targetEnd) {
                    "Synthetic project did not load through the public controller"
                }
                val readyScene = ImageComposeScene(width = viewportWidth, height = viewportHeight, density = Density(1f, fontScale), coroutineContext = context) {
                    ChopLabTheme {
                        OtohiroiDeck(
                            state = controller.state.collectAsState().value.let { displayOverride.value?.invoke(it) ?: it },
                            onImportAudio = { error("Native file picker is outside H13") },
                            onToggleMicrophoneRecording = { error("Recording is outside H13") },
                            onToggleVocalRecording = { error("Recording is outside H13") },
                            onToggleSystemAudioRecording = { error("Recording is outside H13") },
                            onExportBeat = { error("Export is outside H13") },
                            onOpenProject = { error("Native project picker is outside H13") },
                            onSaveProject = { error("Native project picker is outside H13") },
                            viewModel = controller,
                        )
                    }
                }
                scene = readyScene
                return DeckFixture(controller, audioPort, readyScene, directory, project, displayOverride).also { it.settle() }
            } catch (failure: Throwable) {
                runCatching { scene?.close() }.onFailure(failure::addSuppressed)
                runCatching { controller.close() }.onFailure(failure::addSuppressed)
                runCatching { Files.deleteIfExists(project.toPath()) }.onFailure(failure::addSuppressed)
                runCatching { Files.deleteIfExists(directory.toPath()) }.onFailure(failure::addSuppressed)
                throw failure
            }
        }
    }
}

private fun SemanticsNode.description(): String =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

private fun SemanticsNode.stateDescription(): String =
    config.getOrNull(SemanticsProperties.StateDescription).orEmpty()

/** No Java Sound implementation, line, Clip, device query, or provider exists in this port. */
private class SilentAudioPort : DesktopSamplerAudioEngine {
    private var ownership = 0L
    val padRequests = mutableListOf<Int>()
    val releasedOwnedPads = mutableListOf<Pair<Int, Long>>()
    var failNextTrigger = false
    var failNextSourcePlay = false
    var failNextLoopStart = false
    private var sourceFrames = 0
    @Volatile private var sourcePosition = 0
    @Volatile private var sourcePlaying = false
    override val isSourcePlaying get() = sourcePlaying
    override fun loadPcm(audio: PcmAudio, pitchSemitones: Float) {
        sourceFrames = audio.frameCount
        sourcePosition = 0
        sourcePlaying = false
    }
    override fun playFrom(frame: Int) {
        if (failNextSourcePlay) { failNextSourcePlay = false; error("test source unavailable") }
        check(frame in 0 until sourceFrames)
        sourcePosition = frame
        sourcePlaying = true
    }
    fun advanceSourceTo(frame: Int) {
        check(sourcePlaying && frame in 0 until sourceFrames)
        sourcePosition = frame
    }
    override fun seekSource(frame: Int) { sourcePosition = frame }
    override fun sourceFramePosition() = sourcePosition
    override fun padFramePosition(index: Int): Int? = null
    override fun stop() { sourcePlaying = false }
    override fun triggerPad(pad: PadModel, forceLoop: Boolean): Long {
        if (failNextTrigger) {
            failNextTrigger = false
            error("test output unavailable")
        }
        padRequests += pad.globalIndex
        if (forceLoop) loopRequests += pad
        return ++ownership
    }
    val loopRequests = mutableListOf<PadModel>()
    override fun prepareExclusiveLoopSession(loopPad: PadModel, companionPads: List<PadModel>): DesktopPreparedLoopSession =
        DesktopPreparedLoopSession {
            if (failNextLoopStart) {
                failNextLoopStart = false
                throw com.choplab.desktop.audio.DesktopLoopSessionStartupException(IllegalStateException("test loop unavailable"))
            }
            object : com.choplab.desktop.audio.DesktopStartedLoopSession {
                override fun retirePriorPlayback() {
                    sourcePlaying = false
                    loopRequests += loopPad
                    padRequests += loopPad.globalIndex
                    padRequests += companionPads.map { it.globalIndex }
                }
                override fun abandonCandidates() = Unit
            }
        }
    override fun releasePad(index: Int) = Unit
    override fun releasePadIfOwned(index: Int, ownership: Long) {
        releasedOwnedPads += index to ownership
    }
    val stoppedPads = mutableListOf<Int>()
    override fun stopPad(index: Int) { stoppedPads += index }
    override fun stopAll() { sourcePlaying = false }
    override fun close() { sourcePlaying = false }
}

private class ForbiddenRecorder : DesktopAudioRecorder {
    override val isRecording = false
    override fun start(file: File): Result<Unit> = error("Recording is forbidden in H13")
    override fun stop(): Result<File> = Result.failure(IllegalStateException("No H13 recording"))
    override fun close() = Unit
}
