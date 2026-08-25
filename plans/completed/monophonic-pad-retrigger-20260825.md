# Monophonic PAD retrigger / accidental double-audio removal

## Outcome

同じ物理PADから同じ素材が二重に鳴らないよう、Android realtime、Windows Java Sound、offline WAV renderを一つのvoice ownership契約へ揃える。異なるPAD同士の意図的な重ね演奏、choke group、元曲／loop／transport／scratchの既存排他制御は維持する。

## Boundary

- Root / owner: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-creative-improvement-20260825` / current root task
- Baseline: `dfe9a223309cd4f439ffa348039428117161d2a1`
- Target gate: `LOCAL_PASS`
- No device, ADB, recording, Spotify/provider, account, push, PR, release, public, or Human action
- Archive schema、素材PCM、保存済みproject bytesは変更しない

## Root cause and contract

1. LOOP対象がVOCAL PADの場合、loop本体の開始後に全VOCALを開始する旧経路が同じPADも再投入していた。
2. 通常の同一PAD retriggerはvoice pool／Java Sound Clip／offline rendererで既存voiceを置換せず、長いONE SHOTが重なり得た。
3. Windowsはloop owner停止時に開始時VOICE companionを止めず、長いtakeが残り得た。
4. 新契約は `same PAD = replace/restart`、`different PAD = layer`。loop ownerと開始時VOICE companionsは一つのloop sessionとして対称に開始・停止する。

## TDD evidence

- RED: `SamplerEngineVoiceTest.samePadRetriggerRetiresEveryOlderCopyButKeepsDifferentPadsLayered` — missing ownership seamでcompile failure。
- RED: `PlaybackLayeringPolicyTest.selectedVocalLoopIsNotStartedAgainAsItsOwnCompanionLayer` — missing companion policyでcompile failure。
- RED: `PatternRendererTest.repeatedEventRestartsTheSamePadInsteadOfDoublingItsVoice` — step 1後のPCM levelがstep 0と一致せずbehavioral failure。
- RED follow-up: Windowsでloop owner停止後も開始時VOICE companionが残ることをcontroller testでbehavioral failureとして確認。
- GREEN: 同じloop sessionのowner／companionsを開始・停止とも対称化し、全focused testがPASS。

## Validation result

- Product source: `be52047124cf502feec8275f8e74451d400872c8`, tree `6159ef8f08bd133ca23e0c9b6dddc7bfbc705da2`, parent `dfe9a223309cd4f439ffa348039428117161d2a1`。
- Clean full gate: 191 actionable tasks PASS。exact product commitでfinal 184-task read-backもPASS。
- Shared Android host 34、shared Desktop 34、Android unit 239、JVM-core 54、Desktop 80。failures/errors/skips 0。
- Debug/release Lint: errors 0、warnings 7。debug／androidTest／unsigned release APKとWindows app-imageをofflineでbuild。
- Python policy 36 PASS、public-surface 408 PASS、Android XML 6 PASS、wrapper hash／UTF-8／executable mode／`git diff --check` PASS。
- Audio-thread bytecode read-back: `startVoice`と同一PAD retire helperにnew-allocation命令なし。
- iOS previewは既存の`stopPlayers()`がSourceと全PAD playerを毎回排他停止するため、この重ね演奏契約の変更対象外。Swift/macOS実行は未実施。

## Files

- `shared/.../model/PlaybackLayeringPolicy.kt` and common test
- `app/.../SamplerViewModel.kt`, `SamplerEngine.kt`, voice tests
- `desktop/.../DesktopSamplerController.kt`, `DesktopSamplerAudioEngine.kt`, `JavaSoundWavPlayer.kt`
- `jvm-core/.../PatternRenderer.kt`, renderer tests
- plan registry, `docs/PROJECT_STATE.md`, `docs/FEATURE_MATRIX.md`, `docs/VALIDATION.md`

## Stop / rollback

- 異なるPADのレイヤー、choke、loop継続、offline timing/parityのいずれかが回帰したら統合を止める。
- 単独product commit `be520471` を通常の `git revert` で戻せる。schema migrationやデータ変換はない。
