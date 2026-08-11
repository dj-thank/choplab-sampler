# Project state

Last prepared: 2026-08-12

## v0.11 safe handoff, beginner coaching, and landscape deck — 2026-08-12

The fixed journey remains `入れる → チョップ → ビート → 保存`, but the first-beat path is now explicit on the working surfaces. Chop explains waveform seek, empty-PAD capture, assigned-PAD audition, and long-press trim according to the current state. Beat keeps `PAD → 選択音をループ／並べる → 足す／擦る` visible and leaves KEY/TONE/LEVEL directly editable during playback. Whole-Chop repetition is named `選択音をループ`, separate from 16-step pattern placement.

Landscape Chop no longer compresses the stacked portrait deck: the waveform and source controls occupy the left side while a full square 4×4 PAD grid stays on the right. Landscape Beat uses compact BANK/page selection, guidance, direct sound controls, transport, selected-sound loop, recording, Add, Scratch, and Details rows. Portrait keeps the established cream/orange/green hardware-deck language and square PADs. No scroll API was added.

Starting a new source while material work exists now requires an explicit second press. Successful replacement begins a clean project; cancellation, decode failure, an older decode, and delayed microphone, device-capture, or vocal completion cannot mutate a reset or newer project. Autosave now rejects older revisions even when writes complete out of order. Source playback tracks issued and audio-thread-applied generations separately, and the ViewModel preserves the last applied value while start/stop is pending, so neither engine nor UI can publish `playing` before a voice exists. A second tap cancels a pending start, and switching to Beat, Scratch, reset, or another project invalidates it. An old completion cannot clear a newer voice. Scratch speed is finite and bounded at both queue and render boundaries.

Local gate: configured offline validation PASS; 125 unit tests with zero failures/errors/skips; Android Lint PASS with zero errors and 11 advisories; debug assemble PASS; `git diff --check` PASS; UI scroll API scan zero matches. Version `0.11.0` (`versionCode=15`) local APK is 31,516,578 bytes with SHA-256 `37D60CB25D7FC996B68BC83F7FDDCAFA3DE770117ABC1A072A53A8C256B7CC85`; package `com.choplab.sampler`, minSdk 29, targetSdk 36, APK Signature Scheme v2, certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.

Dedicated Pixel 9/API 36 emulator `emulator-5590` received the final local APK through `adb install -r`. Its 5,316,915-byte autosave stayed byte-identical before and after installation at SHA-256 `3962BB989F4B59F8E98AB6D0C38D02DAAC46DBF6CEFDB49AA752552D2614A513`; cold launch reported version 0.11.0, the app process remained alive, and the focused fatal/ANR query returned zero matches. Accepted fixed-layout captures are `work/v011-audit/20-chop-final.png`, `23-chop-landscape-final.png`, `26-beat-landscape-final.png`, and `28-beat-details-landscape.png`.

PR [#20](https://github.com/dj-thank/choplab-sampler/pull/20) merged as `1e0446a29ba245383149de9bfab7863bd69b87e8`. Branch run `31522964955`, PR run `31522968714`, main run `31523293224`, tag verification `31523626784`, and release run `31523626790` all passed. Annotated tag `v0.11.0-preview.1` resolves to the merge commit and the [public prerelease](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.11.0-preview.1) is available.

The reverse-downloaded public APK is 30,723,019 bytes with SHA-256 `04F7284DB3EF90F37561259BF1E0DBCDE59D4AD6A06A448B8729A942AC902B39`; GitHub asset digest and checksum sidecar match. It reports package `com.choplab.sampler`, versionCode 15, versionName 0.11.0, minSdk 29, targetSdk 36, APK Signature Scheme v2, and certificate SHA-256 `E2A9863BAAB8940BD1716D088118C1E766867CCEA48641678192F7B187F2CD1F`. That CI certificate differs from the installed local-build certificate, so the public APK was not installed over retained app data.

The physical Pixel 9a `5A121JEBF08094` was not present in either the final ADB or Windows USB inventory; data-preserving device install and physical audio interaction therefore remain pending. Subjective latency, sustained multi-touch/audio quality, microphone ambience, production signing, and `HUMAN_GO` are not claimed.

## Simple Chop and project isolation — 2026-08-12

The primary flow is now `入れる → チョップ → ビート → 保存`. Entering Chop starts the loaded source from the beginning, selects `A MELODY`, keeps direct live key controls beside transport, and exposes a compact A/B/C/D bank strip plus `01–16` / `17–32` pages. Empty PADs capture the current source position; assigned PADs play their existing chop and can be long-pressed for start/end trim. The main surface links directly to Beat, drums/voice layering, and Scratch without exposing the old 4/8/16 split and fine-control stack.

Importing a different source now starts a separate project state: old A/B/C/D PAD assignments, beat steps, loop/scratch references, slice markers, and edit history are removed before the new source is autosaved. `RESET ALL` uses the same complete blank-state boundary and a confirmed action. Source replacement and reset are covered by deterministic host tests; the physical Pixel project was not erased just to repeat this destructive proof.

Scratch now switches explicitly between the source range and the selected assigned PAD, offers Fine/Normal/Wide gesture sensitivity, and reports active source or PAD scratch consistently. The selected PAD trim editor presents one waveform with independent START and END controls and preview.

Local gate: offline validation PASS; 103 unit tests with zero failures/errors; Android Lint PASS; debug APK assemble PASS; scroll API scan zero matches. Version `0.10.0` (`versionCode=14`) local APK is 30,641,099 bytes, SHA-256 `2AD63450619685094DBFAB4B5E49E10AD4A51432181995767091023F8AF28E9C`. It was installed in place on physical Pixel 9a `5A121JEBF08094` without uninstalling or clearing app data, then copied to the phone's Downloads folder with the same hash. The phone changed foreground apps during UI inspection, so exact final-screen interaction, subjective audio quality, scratch latency, and Human GO remain unclaimed.

PR [#18](https://github.com/dj-thank/choplab-sampler/pull/18) merged as `74944a1c806b312d19364fcb11dfa6d4759cd5a0`. Branch run `31511983934`, PR run `31511988332`, main run `31512350479`, tag verification `31512681213`, and release run `31512681328` all passed. Annotated tag `v0.10.0-preview.1` resolves to the merge commit and the [public prerelease](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.10.0-preview.1) is available. The reverse-downloaded public APK is 30,641,099 bytes with SHA-256 `83F641A154A0287BAA29230F863257CB0C91698F65F7FF2BFE045A1CBB12FD25`; GitHub asset digest, checksum sidecar, PC download, and Pixel Downloads copy match. The public APK reports package `com.choplab.sampler`, versionCode 14, versionName 0.10.0, minSdk 29, targetSdk 36, and APK Signature Scheme v2. Its CI debug certificate differs from the installed local build, so the exact public APK was not installed over retained user data.

## v0.9.3 playable Beat selection — 2026-08-11

Beatと「音を重ねる」は、空PAD・空ページ・空BANKを編集対象へ切り替えず、現在の再生可能PADを保持して日本語の案内を表示する。Beatへ入った時点で選択PADが空なら、現在BANK内、次に全BANKから既存音を選び直す。Chop/PADSの空PAD割り当て操作は従来どおり維持した。

空BANKのBeatレーンは `空 / EMPTY` と表示し、ステップを押しても直前に選んだ別BANKの音へ誤配置しない。純粋状態遷移とレーン対象決定をホストテストで固定し、全98テスト、Lint、assemble、オフライン検証、`git diff --check`、スクロールAPI 0件がPASSした。ローカルAPKは31,360,414 bytes、SHA-256 `3587D5CCC3BCB216D9E8FA231267420F785206388E4396F8389E023E13C34C20`。

Pixel 9/API 36エミュレーターへデータを消さず `versionCode=13` / `versionName=0.9.3` を上書き導入した。既存プロジェクト復元後、Beat入場で空選択から `A-04`へ復帰し、実波形・KEY/TONE・ループが有効になった。空 `A-06` と空 `PAD 17–32` のタップはいずれも `A-04`を保持し、対応する案内を表示した。runtime UI階層は非スクロールで、アプリプロセスは継続した。物理Pixelへの導入と公開Releaseはこの時点では未実施。

PR [#15](https://github.com/dj-thank/choplab-sampler/pull/15) は `27d1c7ce3e1487ac23311a48674014b4edad4e22` としてmergeされた。branch run `31496922708`、PR run `31496975115`、main run `31497276645`、tag verification `31497582713`、release run `31497582655` はすべてPASS。annotated tag `v0.9.3-preview.1` はmerge commitへ解決され、[public prerelease](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.9.3-preview.1) が公開された。

逆ダウンロードした公開APKは30,591,947 bytes、SHA-256 `2B1A8453830CC7D2BBB6DE2CFB8064054EE208A14C22B4108171F889F841B600`。GitHub asset digestとchecksum sidecarが一致し、APK Signature Scheme v2、package `com.choplab.sampler`、`versionCode=13`、`versionName=0.9.3`、`minSdk=29`、`targetSdk=36` を確認した。CIとローカルのdebug署名鍵が異なるため、既存ローカル版を保持するエミュレーターへの公開APK上書きは `INSTALL_FAILED_UPDATE_INCOMPATIBLE` で拒否された。データ削除は行っておらず、物理Pixelも未接続のため、公開APKの実端末導入は未達として分離する。

## v0.9.2 accessibility semantics — 2026-08-11

The fixed, no-scroll interface now exposes its visual selection state to accessibility services for workflow tabs, machine toggles, PADs, sound rails, and Beat-bank selectors. Beat PAD announcements use the configured 32-PAD bank size instead of a hard-coded 16, so PAD 20 is no longer announced as PAD 4. Beat step states are announced in plain Japanese (`選択音`, `別の音`, `オフ`) instead of Kotlin enum identifiers.

Focused host tests cover both 32-PAD addressing and every translated Beat step state. The full local gate passes with 85 tests, zero lint errors, offline validation, and a 31,362,206-byte APK (SHA-256 `0F279F715AF9341BD47FA1FCB3463F1D98607EA0291B84618EC111F8C25283F2`). A Pixel 9/API 36 emulator restored the existing project, opened the fixed Beat view without scrolling, selected empty A-20 without crashing, and exposed `BANK A メロディー PAD 20`, plain-Japanese step states, and no enum identifiers in the runtime accessibility hierarchy. Physical TalkBack navigation remains a human/device check rather than a claimed pass.

Public release `v0.9.2-preview.1` is attached to merge commit `294720c42dcab6ac2152ac6466c61a60f436597c`. Tag verification and release workflows passed. The reverse-downloaded 30,575,563-byte public APK, GitHub digest, and checksum sidecar all match SHA-256 `BCE8A07E57E25255C57816DA21D9067A88C7B41A94E6485CA92D7A32C7B0BC5F`.

## v0.9.1 clarity audit follow-up — 2026-08-11

The post-v0.9.0 emulator audit produced a focused clarity pass without Figma or scroll containers:

- the machine header now shows only the current stage and caption; transient action/recovery status remains in the bottom status strip instead of leaking into unrelated stages;
- CHOP no longer repeats the three Capture input buttons, giving the waveform materially more editing room while input remains one top-level tap away;
- each fixed PAD page reports its assigned count (`5音`) or empty state (`空`), so page 17–32 cannot look like lost data;
- source Scratch always treats waveform taps as slice selection, provides an explicit non-destructive `SOURCE RANGE` choice that preserves chop markers, and ends source scratch on every dialog-dismiss path;
- the Layer Studio loop control is tall enough to show its full START/STOP label.

Validation at this checkpoint: 83 unit tests with zero failures/errors/skips, Android Lint PASS with zero errors, debug APK assemble PASS, and Pixel 9/API 36 emulator screenshots accepted for improved CHOP, PADS, Layer SOUNDS, and source Scratch. Local APK: 30,575,559 bytes, SHA-256 `5F5059DDC6C1EFC7BA1F1FFDCED37F7BACCC81AAA7731437F0C616231E227546`. Physical audio/TalkBack/multi-touch remain unclaimed.

## v0.9.0 four-stage workflow and safe playback local/device evidence — 2026-08-11

Version `0.9.0` (`versionCode=10`) responds to the latest hands-on feedback without Figma:

- the top-level journey is now `入れる → チョップ → ビート → 完成`; `切る` and `鳴らす` are explicit submodes of one Chop stage, so there is no numbered 1→3 jump;
- BANK roles are visible everywhere as A Melody, B Drums, C One Shots, and D Voice;
- every BANK now holds 32 PADs, shown as fixed `01–16` / `17–32` pages; a newly loaded source always targets the first empty A Melody PAD, while schema-4 projects with 16-PAD banks migrate into page one without index drift;
- the performance view keeps the editable source waveform, manual/automatic chop and PAD assignment controls directly above sixteen visible role-colored square sample pads; live capture is an explicit `LIVE CHOP` mode, so normal PAD performance remains audible while the source song plays;
- the Beat view is a fixed four-lane 16-step board with playhead, selected-sound rail, source/loop waveform, transport, loop controls, and direct KEY/TONE/volume editing;
- starting a PAD loop removes an existing audition voice for that PAD first, so previewing and then looping does not stack the same sound twice;
- Layer Studio can place Melody, Drums, One Shots, or Voice with quarter/eighth/sixteenth presets, and Scratch uses a selectable range on the original source waveform;
- sample slicing uses only empty PADs, a full bank refuses replacement, and replacing BANK B with a built-in drum kit requires an explicit second press when sounds already exist;
- manual project save validates a local archive and commits an app-owned safety copy before writing the selected destination; autosave uses a synchronized validated pending write plus three bounded generations;
- source playback now restarts from frame zero after reaching the final frame instead of immediately ending on the next Play press.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 81 tests, zero failures/errors/skips;
- Gradle `lintDebug` and `assembleDebug`: PASS;
- `git diff --check`: PASS; UI source scroll API scan: zero matches;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,716,854 bytes, SHA-256 `F27FAB5034687E165554578C8F859E12A096FC7C05A93DED0BA499C3070AC867`.

Focused Pixel 9 / API 36 emulator evidence:

- an exact 5,316,915-byte schema-4 Pixel autosave (SHA-256 `3962BB989F4B59F8E98AB6D0C38D02DAAC46DBF6CEFDB49AA752552D2614A513`) restored its source waveform, chop markers, PADs, and pattern under schema 5;
- CHOP, PADS page 17–32, BEAT with direct KEY controls, Layer Studio SOUNDS, and original-source-range Scratch all fit the 1080 × 2424 portrait screen without scrolling;
- a fixed-size assertion left over from 16-PAD banks caused one BEAT navigation crash during validation; the assertion now targets the 16-PAD visible page, and the same navigation remained alive afterward.

Focused physical Pixel 9a evidence:

- the final local APK installed in place as `versionCode=10`, `versionName=0.9.0`, launched with the previous project intact, and was copied to `/sdcard/Download/ChopLab-0.9.0-ui-safe-playback.apk`; PC/device APK hashes matched exactly;
- the four-stage Chop/Pads and four-lane Beat layouts fit the portrait screen with no scrolling; the condensed PADS waveform stayed clearly visible, and `MANUAL` plus a waveform tap added a numbered chop boundary on device;
- after a completed source had left its playhead at the end, `SOURCE PLAY` changed to `SOURCE STOP`, confirming restart-from-zero behavior;
- with BANK B selected, source still playing, and `LIVE CHOP OFF`, tapping assigned B-01 left the autosave SHA-256 unchanged at `7367C2026579C76FF7C3EE3FC5278D8600B3062DB853DEB46139CFC400D99140`, kept `SOURCE STOP` visible, and left the app process alive. The host routing test independently confirms this path selects performance playback rather than capture.
- the latest local APK was installed in place while the phone remained locked and copied to `/sdcard/Download/ChopLab-0.9.0-latest.apk`; device/PC SHA-256 matched `F27FAB5034687E165554578C8F859E12A096FC7C05A93DED0BA499C3070AC867`. Latest-screen and subjective-audio checks remain intentionally unclaimed until the phone is unlocked.

This establishes `LOCAL_PASS` and focused local-build `DEVICE_PASS`. It does not yet claim `PUBLIC_PASS`, subjective audio/latency quality, physical multi-touch stress, microphone overdub, production signing/update continuity, or `HUMAN_GO`.

## v0.8.0 drum, vocal, and scratch workstation local/device evidence — 2026-08-11

Version `0.8.0` (`versionCode=9`) adds one fixed `LAYER STUDIO` without using Figma or introducing scroll containers:

- PAD cells are the largest centered squares that fit both the existing 4×4 and Arrange 8×2 grids;
- five original deterministic drum kits each provide 16 named KICK/SNARE/HAT/PERC one-shots, a professional list selector, BANK A〜C targeting, and a starter beat;
- microphone overdub restarts the active beat loop, stores the decoded take in BANK D, starts vocal takes once with the loop, and includes them in offline export;
- a large touch jog uses signed speed for forward/reverse scratch playback and stops on release;
- schema 4 persists PAD content roles and selected kit, while schema 1–3 remain readable;
- third-party artist recordings and unofficial branded kits are excluded. Candidate CC0 intake and provenance checks are documented in `docs/research/legal-drum-sample-sources.md`.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 66 tests, zero failures/errors/skips;
- Gradle `lintDebug`: PASS, zero errors (10 Android/toolchain advisories reported);
- Gradle `assembleDebug`: PASS;
- `git diff --check`: PASS;
- UI source scan: zero scroll API matches;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 31,164,002 bytes, SHA-256 `DBD637B102E6133C9C7D55EF97F7DD01D7CF65298E2CDF96E2C213033C8A73E9`.

Focused physical Pixel 9a evidence on Android 17 / arm64-v8a:

- the final exact APK installed as `versionCode=9`, `versionName=0.8.0`, launched with a live process, and was copied to `/sdcard/Download/ChopLab-0.8.0-drum-vocal-scratch.apk`;
- device and PC APK SHA-256 matched exactly;
- Android rejected the first in-place update because the installed public build used a different debug signature. The existing 4,960,607-byte autosave was copied byte-for-byte to `/sdcard/Download/ChopLab-autosave-before-drum-vocal-scratch.choplab` with SHA-256 `ACE63AE664334728BB6D7FB432261035DDBA5B5EB49E3F04647C2D25A8AE4DB0`; inspection confirmed that archive was already truncated/corrupt and the old app also reported recovery failure;
- after the authorized package replacement, DUSTY JAZZ was applied to BANK B, real synthesized waveforms and starter-step markers appeared, square 8×2 PADs remained on-screen, and the improved DRUMS/VOICE/SCRATCH panels were captured without scrolling;
- the resulting schema 4 autosave restored successfully after a cold app restart.

Public evidence for the same source state:

- PR [#8](https://github.com/dj-thank/choplab-sampler/pull/8) merged as `d99a27f4bdb3aa609500bb1334aa782382fe25f8`; branch push run [31457463895](https://github.com/dj-thank/choplab-sampler/actions/runs/31457463895), PR run [31457485138](https://github.com/dj-thank/choplab-sampler/actions/runs/31457485138), and main run [31457675077](https://github.com/dj-thank/choplab-sampler/actions/runs/31457675077) passed;
- annotated tag `v0.8.0-preview.1` resolves to that merge commit; tag verification [31457697955](https://github.com/dj-thank/choplab-sampler/actions/runs/31457697955) passed;
- release workflow [31457697961](https://github.com/dj-thank/choplab-sampler/actions/runs/31457697961) passed build/package and publish jobs. [ChopLab v0.8.0-preview.1](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.8.0-preview.1) is public and marked as a prerelease;
- reverse-downloaded public `ChopLab-v0.8.0-preview.1-debug.apk`: 30,477,259 bytes, SHA-256 `D3C26D20023A9D25B19E316D1C77A44D067DCA7717DDA3BDA2F82067A58EC1A8`. It matched the GitHub asset digest and attached checksum file, and reports package `com.choplab.sampler`, `versionCode=9`, `versionName=0.8.0`;
- the exact public APK was copied to the Pixel 9a at `/sdcard/Download/ChopLab-v0.8.0-preview.1-debug.apk`, where its SHA-256 matched the reverse download. The installed app remains the same-source locally signed build, so this does not claim an exact-public-APK install smoke.

This establishes `LOCAL_PASS`, `PUBLIC_PASS`, and focused local-build install/launch, kit-application, fixed-layout, and schema-4 recovery `DEVICE_PASS`. It does not establish ambient microphone recording on the user's phone, subjective drum/loop/scratch sound quality, measured latency/xRuns, sustained thermal behavior, TalkBack/haptic quality, production signing/update continuity, exact-public-APK installation, or `HUMAN_GO`.

The final two-axis review found and resolved two release blockers before publication: BANK D now refuses a seventeenth vocal take instead of overwriting D-01, and scratch-voice allocation was moved off the realtime audio thread. Scratch speed/frame atomics are handled once per block and an idle gesture returns speed to zero. The remaining duplicated realtime/offline layer scheduling is an accepted internal maintainability item, not a v0.8 behavior gap.

## Public v0.7.0 whole-chop beat-loop evidence — 2026-08-11

- PR [#6](https://github.com/dj-thank/choplab-sampler/pull/6) merged as `9d09228c7d19cdd709b7c864e21eddaa69715d67` after branch push run [31400890047](https://github.com/dj-thank/choplab-sampler/actions/runs/31400890047) and PR run [31400928956](https://github.com/dj-thank/choplab-sampler/actions/runs/31400928956) both passed.
- Main Android verification [run 31401298050](https://github.com/dj-thank/choplab-sampler/actions/runs/31401298050) passed before tagging.
- Annotated tag `v0.7.0-preview.1` resolves to the same merge commit; tag Android verification [run 31401606925](https://github.com/dj-thank/choplab-sampler/actions/runs/31401606925) passed.
- Release workflow [run 31401606890](https://github.com/dj-thank/choplab-sampler/actions/runs/31401606890) passed build/package and publish jobs. [ChopLab v0.7.0-preview.1](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.7.0-preview.1) is public and marked as a prerelease.
- Public `ChopLab-v0.7.0-preview.1-debug.apk`: 30,346,187 bytes, SHA-256 `3393A60EBB8FDD3CE76CD459150049807D63DC39CF62BB4CF213365FB5FD1CB2`. The reverse-downloaded APK matched both the GitHub asset digest and attached `.sha256` file.
- The exact public APK was copied to the connected Pixel 9a at `/sdcard/Download/ChopLab-v0.7.0-preview.1-debug.apk`, where its SHA-256 matched the PC download.
- Android correctly rejected an in-place update from the local debug signature. Before replacing only `com.choplab.sampler`, the current 12,003,628-byte autosave was backed up, its ZIP entries were read successfully, and device/PC SHA-256 matched `75C8BB8E5FFC8E6FA0006212E4A869593A2C8D680B44DD5DA7474A862CC45B42`.
- The exact public APK was then installed as `versionCode=8`, `versionName=0.7.0`; the same autosave was restored with the same digest. A cold launch showed `Without You.mp3` and `前回の自動保存を復元しました`, the focused fatal query returned zero matches, and the previously focused Neefo activity was brought back to the foreground.
- The reverse-downloaded public APK and verified autosave backup remain under the task `outputs` folder.

This establishes `LOCAL_PASS`, `PUBLIC_PASS`, and focused install/launch plus state-migration `DEVICE_PASS` for the exact public v0.7.0 preview APK. It does not establish subjective loop-seam quality, sustained latency/thermal behavior, physical multi-touch layering, TalkBack/haptic quality, production signing/update continuity, or `HUMAN_GO`.

## Whole-chop beat loop local/device evidence — 2026-08-10

Version `0.7.0` (`versionCode=8`) separates a continuous beat loop from step-grid placement:

- `4 並べる` now gives one beginner path: `1 PADを選ぶ → 2 ビートをループ → 3 音を重ねる`;
- `ビートをループ` repeats the selected PAD's start-inclusive/end-exclusive audio range continuously instead of retriggering it on quarter/eighth/sixteenth steps;
- one project beat-loop PAD is active at a time, duplicate infinite voices are prevented, and both the same primary control and `ALL STOP` stop playback;
- the real loop waveform stays visible while another BANK is selected, with a live loop-position line and percentage;
- when another BANK is selected during playback, the primary control still names the active loop PAD and remains an unambiguous `ループ停止 / STOP` action;
- `4つ打ち / 8分 / 16分` remain available only as optional `配置プリセット` in `細かく調整`, for layering other PADs without conflating sequencing with the base loop;
- offline export starts LOOP PADs at frame zero and renders them continuously, while project archive schema 3 preserves the new mode and still reads schema 1/raw-PCM and schema 2/WAV projects;
- portrait and landscape keep the existing fixed console with no scrolling API.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 53 tests, zero failures/errors/skips;
- Gradle `lintDebug`: PASS with zero errors and nine pre-existing toolchain/platform advisories;
- Gradle `assembleDebug`: PASS;
- `git diff --check`: PASS;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,942,730 bytes, SHA-256 `AD0E2079574DB72B28C928439F3CF3C45BB59322C2ADECBD8E3637F67A1C945A`.

Focused physical Pixel 9a evidence:

- installed the exact final local APK as `versionCode=8`, `versionName=0.7.0`, then copied the same bytes to `/sdcard/Download/ChopLab-v0.7.0-local-debug.apk`; device and PC SHA-256 both matched `AD0E2079574DB72B28C928439F3CF3C45BB59322C2ADECBD8E3637F67A1C945A`;
- the existing project restored after cold launch and its assigned source/PADs remained available;
- the final exact APK started A-04 from its saved LOOP PAD state and exposed a live 26–29% waveform position, `A-04の音声全体を繰り返し中`, and `ループ停止 / STOP` before the user returned another app to the foreground;
- on the immediately preceding v0.7.0 candidate, starting A-04 changed the accessible waveform state from 23% to 75%; after moving to empty BANK B and returning to Arrange, A-04's real waveform, live loop percentage and enabled STOP action remained visible, stopping from BANK B reported `ビートループを停止しました`, and the focused log contained no fatal exception match. The subsequent review fixes affected only allocation-free engine release loops and the export precondition, not this UI path.

This establishes `LOCAL_PASS` and a focused install/launch/control-state `DEVICE_PASS` for the exact local v0.7.0 APK. It does not yet establish CI/Release identity, `PUBLIC_PASS`, subjective loop-seam quality, sustained latency/thermal behavior, physical multi-touch layering, TalkBack/haptic quality, production signing/update continuity, or `HUMAN_GO`.

## Public v0.6.0 preview evidence — 2026-08-10

- PR [#5](https://github.com/dj-thank/choplab-sampler/pull/5) merged as `db0845d9de8129dae14d813eab10ad1cda88a0de` after both branch verification runs `31391730072` and `31391734337` passed.
- Main Android verification [run 31392024199](https://github.com/dj-thank/choplab-sampler/actions/runs/31392024199) passed before tagging.
- Annotated tag `v0.6.0-preview.1` resolves to the same merge commit; tag Android verification [run 31392345400](https://github.com/dj-thank/choplab-sampler/actions/runs/31392345400) passed.
- Release workflow [run 31392343101](https://github.com/dj-thank/choplab-sampler/actions/runs/31392343101) passed both build/package and publish jobs. The public prerelease is [ChopLab v0.6.0-preview.1](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.6.0-preview.1).
- Public `ChopLab-v0.6.0-preview.1-debug.apk`: 30,313,419 bytes, SHA-256 `1E57FB66FDA11E3C4A69B2646A7CA340F67067A13AC5B11E505C40A5011B3B90`. The downloaded APK matched the Release digest and attached `.sha256`, verified with APK Signature Scheme v2, and reported package `com.choplab.sampler`, `versionCode=7`, `versionName=0.6.0`.
- The exact public APK installed fresh and cold-launched on the Pixel 9 AVD. `MainActivity` became top-resumed and the focused log query found no fatal exception.
- On the connected Pixel 9a, Android rejected the in-place update because the prior public debug signature differed from the new CI debug signature. Before replacing only `com.choplab.sampler`, the 12,003,624-byte autosave was backed up, its archive entries were read successfully, and device/PC SHA-256 matched at `63C3B7EA9183B9C88FADE32AD98125C6A625A1163B1921B9F62720C6494842E7`.
- The public APK was then installed as `versionCode=7`, `versionName=0.6.0`; the same autosave was restored with the same digest. A cold launch displayed `前回の自動保存を復元しました`, no focused fatal exception was detected, and the previously focused app was reopened.
- The exact public APK remains on the phone at `/sdcard/Download/ChopLab-v0.6.0-preview.1-debug.apk` and on the PC under the task `outputs` folder.

This establishes `LOCAL_PASS`, focused `EMULATOR_PASS`, `PUBLIC_PASS`, and install/launch plus verified autosave-migration `DEVICE_PASS` for the exact public v0.6.0 preview APK. It does not establish sustained physical multi-touch performance, TalkBack/haptic quality, long-session audio latency, production signing/update continuity, implemented AI assistance, or `HUMAN_GO`.

## Arrange quick flow and progressive controls — 2026-08-10

Version `0.6.0` (`versionCode=7`) makes the existing repeat presets discoverable and reduces the default Arrange control density without removing advanced editing:

- the default `4 並べる` screen now presents one explicit path: `1 PADを選ぶ → 2 反復を選ぶ → 3 ビートを聴く`;
- the repeat area has its own permanent orange outline and heading. An empty PAD explains that an audio-filled PAD must be selected; an assigned PAD asks, for example, `A-01を何拍ごとに鳴らす？`;
- `4つ打ち / 8分 / 16分` use beginner meanings (`1拍ごと / 半拍ごと / 細かく`) while retaining the exact existing repeat-grid behavior;
- PLAY/STOP, next-BANK sound layering and one `細かく調整` entry remain on the quick screen;
- REC/CLEAR, BPM/Swing, manual 16-step editing and KEY/TONE/LEVEL move to the reversible `細かく調整` view;
- portrait and landscape use the same quick/fine hierarchy and remain fixed, without a scroll API;
- `docs/AI_ASSIST_VISION.md` records a future one-entry, non-destructive, local-first AI proposal workflow. No AI feature is claimed as implemented.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 46 tests, zero failures/errors/skips;
- Gradle `lintDebug`: PASS;
- Gradle `assembleDebug`: PASS;
- UI source scan: zero `verticalScroll`, `horizontalScroll`, or `rememberScrollState` matches;
- `git diff --check`: PASS;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,904,558 bytes, SHA-256 `F503EA14A5E89B26465F32A75A223F2E0AAB087015C9E7ADD5BEB3052621AEF8`.

Focused Pixel 9 AVD evidence on Android 16/API 36, x86_64, 1080 × 2424 px at density 420:

- captured the v0.5.0 Arrange baseline, then the same empty-PAD viewport with the new three-step quick hierarchy and inspected a side-by-side comparison;
- recorded a 3.1-second emulator source, assigned A-01, selected `4つ打ち`, and observed steps 1/5/9/13, the orange active preset and matching timeline markers;
- started beat playback from `3 ビートを聴く` and observed the moving waveform playhead, current-step readout and STOP state with no focused fatal exception;
- opened `細かく調整` and confirmed REC/CLEAR, BPM/Swing, all 16 manual steps and KEY/TONE/LEVEL remain reachable, with a visible return to quick creation;
- rotated to 2424 × 1080 landscape and confirmed PAD, waveform, repeat question/presets and the three primary actions remained visible without clipping.
- measured each portrait repeat preset at 127 px on the 420 dpi test device, slightly over the 48 dp minimum touch target.

This establishes `LOCAL_PASS` and focused `EMULATOR_PASS` for the control-hierarchy change. It does not yet establish public CI/Release identity, installation of version `0.6.0` on the physical phone, physical touch/TalkBack/haptic quality, any AI capability, or `HUMAN_GO`.

## Arrange waveform and repeat workflow — 2026-08-10

Version `0.5.0` (`versionCode=6`) turns `4 並べる` into a visible one-bar beat workspace without removing the original live-chop flow:

- the selected PAD's real PCM slice is down-sampled into a bounded waveform and drawn over 16 beat divisions;
- a high-contrast moving playhead, `いま xx / 16` readout and matching sequencer-cell outline show the current playback position;
- four labelled BANK rows show every sounding layer at each step, with the selected PAD marker separated from other BANK activity;
- `4つ打ち / 8分 / 16分` presets replace only the selected PAD's steps, preserve all other PAD/BANK layers, and remain Undo/Redo-compatible;
- Arrange uses a compact 8 × 2 PAD selector in portrait while the live `叩く` stage keeps its 4 × 4 PAD layout;
- `音を重ねる BANK →` selects an already-audible layer; when the same PAD in the next BANK is empty, `音を足す BANK →` moves directly to `叩く` on that BANK/PAD so a new sound can be captured;
- KEY shows truthful semitone offset plus `原キー / 高い / 低い` without pretending to detect the imported song's musical key, and TONE cycles through `暗い / なじむ / 原音` while the continuous editor remains available;
- landscape Arrange was reorganized into waveform/repeat/steps and transport/timing/sound columns so the new controls do not clip.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 44 tests, zero failures/errors/skips;
- Gradle `lintDebug`: zero issues;
- Gradle `assembleDebug`: PASS;
- UI source scan: zero `verticalScroll`, `horizontalScroll`, or `rememberScrollState` matches;
- `git diff --check`: PASS;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,829,070 bytes, SHA-256 `3A0487513A6455A98AAC835B2E53ECDB1AB1FFDEF80911CFFDD840651D5C7E31`.

Focused Pixel 9 AVD evidence on Android 16/API 36, x86_64, 1080 × 2424 px at density 420:

- imported a 30-second WAV, created four slices, assigned A-01..04, placed A-01 as a four-on-the-floor pattern, then assigned B-01..04 and layered B-01 as eighth notes;
- portrait playback showed the selected real waveform, moving high-contrast playhead, A/B marker rows, active 8分 preset, KEY/TONE labels and all controls without scrolling or visible clipping;
- landscape playback/edit layout showed waveform, A/B layers, repeat presets, all 16 steps, transport, BPM/Swing, KEY and TONE/LEVEL without visible clipping;
- selecting empty C-01 through `音を足す BANK C →` moved directly to `叩く`, selected BANK C / PAD C-01, and displayed the instruction to press a PAD while the source plays;
- installed package reported `versionCode=6`, `versionName=0.5.0`, `minSdk=29`, `targetSdk=36`; `MainActivity` was top-resumed and the focused error-log query found no fatal exception.

Public and physical-device evidence:

- PR [#4](https://github.com/dj-thank/choplab-sampler/pull/4) merged as `48c645e8b6a0f96c9acf2a7249f26648e8430689`;
- main Android verification [run 31386734837](https://github.com/dj-thank/choplab-sampler/actions/runs/31386734837): PASS;
- tag `v0.5.0-preview.1` resolves to the same merge commit; tag Android verification [run 31387028904](https://github.com/dj-thank/choplab-sampler/actions/runs/31387028904): PASS;
- release workflow [run 31387028918](https://github.com/dj-thank/choplab-sampler/actions/runs/31387028918): PASS; [public release](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.5.0-preview.1) published from the public repository;
- public `ChopLab-v0.5.0-preview.1-debug.apk`: 30,297,035 bytes, SHA-256 `DB3EC8CC7B23C7DFB82547FBFC10DFEC59A11BFE11AF707AD24DC2CEBF16C4F1`; the downloaded APK matched both the Release asset digest and attached `.sha256`;
- the downloaded public APK installed and cold-launched on the Pixel 9 AVD as `versionCode=6`, `versionName=0.5.0`, with no focused fatal exception;
- on the connected Pixel 9a, Android correctly rejected an in-place update because the previous local debug signature differed from the CI debug signature. Before replacing only `com.choplab.sampler`, the 4,494,933-byte autosave was backed up and verified as SHA-256 `7AAB7315A7922C7075F07DF561204CB5D7C4BE9E0B59CA3E67F73B12A8884140`. The public APK was then installed, the same autosave restored with the same digest, `MainActivity` launched as version `0.5.0` without a focused fatal exception, and the previously focused app was reopened;
- the public APK remains in the phone's Download folder; the temporary Arrange audit WAV was removed.

This establishes `LOCAL_PASS`, focused `EMULATOR_PASS`, `PUBLIC_PASS`, and install/launch plus state-migration `DEVICE_PASS` for the exact public APK. It does not establish sustained physical multi-touch performance, subjective haptic quality, long-session audio latency, production signing/update continuity, or `HUMAN_GO`.

## MVP project persistence and edit recovery — 2026-08-10

Version `0.4.0` (`versionCode=5`) adds a bounded persistence slice to the existing mono AudioTrack MVP without changing the original HTML live-chop flow:

- manual `.choplab` save/open from `完成`, with current source, shared PCM16 WAV assets, slice ranges, all 64 PAD assignments and parameters, sequence, BPM/Swing and source KEY;
- schema 2 writes standard WAV entries while the reader migrates schema 1 raw-PCM archives and rejects unknown newer schemas with an update message;
- app-owned autosave after 900 ms of edit inactivity, written through a synced temporary file and two recoverable generations; a valid pending generation can also recover an interrupted replacement;
- at most 40 Undo/Redo entries for slice, PAD, sequence and timing edits, with repeated slider updates coalesced into one operation;
- fail-closed archive checks for schema, normalized entry names, path traversal, duplicate audio IDs/entries, manifest size, total PCM size, unknown entries, malformed/truncated WAV, invalid ranges and invalid references;
- transient playback, recording and loading states are never restored as active.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 37 tests, zero failures/errors/skips;
- Gradle `lintDebug`: zero errors, nine warnings;
- Gradle `assembleDebug`: PASS;
- UI source scan: zero `verticalScroll`, `horizontalScroll`, or `rememberScrollState` matches;
- `git diff --check`: PASS;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,357,226 bytes, SHA-256 `B0E9DF2E9D50E2AD3CBE1DF4C9CF8AA1F6C3296DA181BB57F25535024B205D0A`.

Focused Pixel 9 AVD evidence on Android 16/API 36, x86_64, 1080 × 2424 px at density 420:

- APK installed as `versionCode=5`, `versionName=0.4.0`, `minSdk=29`, `targetSdk=36`; `MainActivity` was top-resumed and no focused fatal exception was found;
- the final APK opened the previous schema 1 autosave at 92 BPM, then imported `choplab-ui-smoke.wav` and wrote a schema 2 autosave whose archive contained `audio/0.wav` with `RIFF/WAVE` headers;
- the expanded `完成` action area showed SAVE PROJECT, OPEN PROJECT, UNDO and REDO without clipping or scrolling;
- after the final APK change, a source-backed 92 BPM schema 2 project was manually saved through DocumentsUI, BPM was changed to 93, and opening the saved project restored 92 BPM with no focused fatal exception;
- saved an empty 92 BPM project through Android DocumentsUI, changed BPM to 93, restored 92 with Undo, restored 93 with Redo, then reopened the saved project and observed 92 BPM;
- changed the reopened project to 94 BPM, waited for autosave, force-stopped/relaunched the app, and observed 94 BPM plus the autosave-restored status;
- intentionally truncated only the emulator app's latest autosave to four bytes, force-stopped/relaunched, and observed recovery from the previous 92 BPM generation with no fatal exception;
- the temporary DocumentsUI `.choplab` and WAV test files were removed from emulator Downloads after verification.

Public evidence:

- PR [#3](https://github.com/dj-thank/choplab-sampler/pull/3) merged as `a1f8716339cf42660f8f9c1e7b0a3ade0cd97a46`;
- main Android verification [run 31360839715](https://github.com/dj-thank/choplab-sampler/actions/runs/31360839715): PASS;
- tag `v0.4.0-preview.1` resolves to the same merge commit; tag Android verification [run 31361047407](https://github.com/dj-thank/choplab-sampler/actions/runs/31361047407): PASS;
- release workflow [run 31361047377](https://github.com/dj-thank/choplab-sampler/actions/runs/31361047377): PASS; [public release](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.4.0-preview.1) published;
- public `ChopLab-v0.4.0-preview.1-debug.apk`: 30,215,115 bytes, SHA-256 `ACCC866289D261DBC7694A2F02A24C90E2EF1DCEFDB250DF2DDB80C1C9C12FF2`; downloaded checksum matched the attached `.sha256`;
- the downloaded public APK was installed fresh on the Pixel 9 AVD, imported the WAV source, produced schema 2 `audio/0.wav` with `RIFF/WAVE`, restored autosave after restart, exposed SAVE/OPEN/UNDO/REDO without scrolling, and showed no focused fatal exception.

This establishes `LOCAL_PASS`, focused `EMULATOR_PASS`, CI build evidence and public-release artifact identity for the MVP persistence slice. It does not establish physical `DEVICE_PASS`, process-death durability under real storage pressure, large-audio performance, a stable production signing/update path, or `HUMAN_GO`.

## Guided five-stage sampler workflow — 2026-08-10

The Android application now presents the fixed `入れる / 切る / 叩く / 並べる / 完成` journey while preserving the original HTML workflow inside `叩く`: load or record audio, play the source, and press a PAD at the desired instant to create a live chop. `切る` retains the precision waveform tools, `並べる` retains the 16-step sequencer, and `完成` accurately exposes the implemented four-bar mono WAV export.

Version `0.3.0` (`versionCode=4`) adds:

- a Japanese-first five-stage rail with short English production captions;
- beginner guidance for source sampling and the starter `1・5・9・13` step pattern;
- selected-PAD KEY note names and semitone controls, plus direct TONE/LEVEL sliders on regular portrait layouts;
- the existing reverse, one-shot/gate, choke and PAD clear controls under `叩く` → `詳細`;
- a finish summary with assigned PAD count, audible step count, BPM, beat preview, confirmed pattern clear and four-bar export;
- safe restoration of legacy `CHOP / PAD / SEQ / SOURCE` saved mode names without `valueOf` crashes.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 23 tests, zero failures/errors/skips;
- Gradle `lintDebug`: zero errors, nine warnings;
- Gradle `assembleDebug`: PASS;
- UI source scan: zero `verticalScroll`, `horizontalScroll`, or `rememberScrollState` matches;
- `git diff --check`: PASS;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,616,083 bytes, SHA-256 `718814700DF1929D53CC90B2B0A10A7230E677C598E226080114ACC8D87348D2`.

Emulator evidence on a headless Pixel 9 AVD, Android 16/API 36, x86_64, 1080 × 2424 px at density 420:

- final APK installed as `versionCode=4`, `versionName=0.3.0`, `minSdk=29`, `targetSdk=36`;
- `MainActivity` reached `topResumedActivity`; the focused post-launch log query found no fatal exception;
- microphone permission and a three-second emulator recording were used to verify capture → live source playback → PAD 01/02 assignment;
- PAD A-02 KEY changed from C3 to C#3, TONE to 32%, and LEVEL to 75%; steps 1/5/9/13 changed to `オン`;
- `完成` showed two assigned PADs, four audible steps, 92 BPM, enabled beat preview and enabled four-bar WAV export;
- post-review detail screens showed Japanese-first PARAM/PLAY, PITCH/TONE/LEVEL, reverse, one-shot/gate, choke and confirmed PAD-clear labels without clipping; `完成` now includes transport state;
- visual comparison against both selected generated targets completed after adding the direct sliders and arrange TIP; `design-qa.md` records `final result: passed`.

Public evidence for the exact merged UI commit `a882ec633d6b9ad849a8c900171fbbd1006f29d1`:

- public PR `#2`: `https://github.com/dj-thank/choplab-sampler/pull/2`, merged after the PR verification run `31357128321` passed;
- main Android verification run `31357298769`: PASS;
- tag Android verification run `31357435542`: PASS;
- tag: `v0.3.0-preview.1`;
- release workflow run `31357435588`: build/package and publication PASS;
- public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.3.0-preview.1`;
- public APK: `ChopLab-v0.3.0-preview.1-debug.apk`, 30,116,752 bytes;
- public APK SHA-256: `E5C79BF01F62C5445E23798CF0603B46305E37BC932F3B9AE94C580E3E4E7219`;
- downloaded release APK and published `.sha256` file matched byte-for-byte by digest.

This establishes `PUBLIC_PASS` for the public repository, CI, Release publication, downloadable APK identity, and the focused `EMULATOR_PASS` above. No physical phone is connected, so it does not establish `DEVICE_PASS`, physical touch comfort, haptic quality, microphone fidelity, latency, or `HUMAN_GO`.

## Fixed no-scroll production console — 2026-08-10

The application source now uses a fixed `CHOP / PAD / SEQ / SOURCE` console instead of the former vertically scrolling card stack. The four workspaces preserve the existing sampler engine and expose live chop, 4 BANK × 16 PAD performance, per-PAD editing, 16-step sequencing, capture/import, slicing, assignment, and WAV export without top-level vertical or horizontal scrolling.

Local evidence for version `0.2.0` (`versionCode=3`):

- `DeckLayoutPolicyTest`: four portrait/landscape and compact/regular policy tests pass;
- Gradle `testDebugUnitTest`: 18 tests, zero failures/errors;
- Gradle `lintDebug`: zero errors, nine warnings;
- Gradle `assembleDebug`: PASS;
- UI source scan: no `verticalScroll`, `horizontalScroll`, or `rememberScrollState` usage;
- local debug APK: `app/build/outputs/apk/debug/app-debug.apk`;
- local debug APK SHA-256 after compact-landscape and accessibility hardening: `CDB02CFFA5F693F2550F41260558D04E259F31AC917E998ED16CDE12D07E8ABD` (30,433,927 bytes).

The current-run Android SDK Platform 36 and Build Tools 36.0.0 were installed locally after accepting their SDK licenses, allowing a real local Android compile and APK build rather than source-only validation.

No phone is connected for this milestone. Local validation establishes `LOCAL_PASS`, but does not claim `DEVICE_PASS`, screenshot parity, touch comfort, clipping-free rendering, audio E2E, or `HUMAN_GO`. Previous Pixel 9a evidence below applies only to the older `v0.1.1-preview.1` artifact.

Public evidence for the exact UI commit `e0896adf8ff96439556d551d1cae4b9d1927f868`:

- main Android verification run `31352372588`: PASS;
- tag Android verification run `31352511018`: PASS;
- tag: `v0.2.0-preview.1`;
- release workflow run `31352511062`: build/package and publication PASS;
- public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.2.0-preview.1`;
- public APK: `ChopLab-v0.2.0-preview.1-debug.apk`, 30,034,832 bytes;
- public APK SHA-256: `B1CC4F6B014F507F3F928AF10CA0EB25E41EA58E50CCEC771CDE43A9A0F62C26`.

This establishes `PUBLIC_PASS` for repository visibility, CI build, and downloadable artifact identity. It still does not establish `DEVICE_PASS` for the new fixed console because no phone was available.

## Canonical 「おとひろい」 UI — 2026-08-10

The user-supplied 505-line HTML prototype is now treated as the canonical top-screen specification. Source changes add:

- the cream hardware-deck visual system, Japanese `おとひろい` identity, orange sampling lamp, green waveform, 4 × 4 PAD layout, and one-row 16-step sequencer in Compose;
- full-source playback, waveform seek, atomic source playhead reporting, ±12-semitone source pitch-by-rate, and source/beat transport exclusion;
- live chop capture: while the source is playing, pressing a visible PAD assigns the latency-compensated playhead and reflows same-audio PAD end frames within the current bank;
- the existing 4 BANK, advanced waveform selection, microphone/system capture, per-PAD editing, quantized record, Swing, and WAV export under an expandable details section.

Observed locally before Android publication:

- pure Kotlin JUnit: `OK (14 tests)` including two new live-chop boundary tests;
- `scripts/validate_project.sh`: PASS using the preserved portable JDK 17 / Kotlin 2.3.21 environment;
- `git diff --check`: PASS.

Public GitHub Actions run `31321170535` passed offline validation, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, and artifact upload for commit `1fc8fc8`. The CI APK SHA-256 is `BDAE4725031940B8452331D61BA689905AE1C947E77C615BAF0586EB7BAD32F5` (29,920,144 bytes).

The exact CI APK was installed on the connected Pixel 9a after a content-free `run-as` inspection found only cache and `files/profileInstalled`; Android rejected in-place update because GitHub runner debug signatures differ, so the old preview was uninstalled first. `MainActivity` launched with no immediate fatal exception. Visual inspection confirmed the canonical deck, header, source controls, waveform, banks, and PAD proportions without visible clipping. A generated 30-second mono 48 kHz sine test then verified `曲を読込` → `曲を再生` → PAD 01 during sampling → stop, with `PAD 01 割り当て済み` and marker `01` at approximately `0:00.8`. The temporary test WAV was removed from the device afterward.

This is a focused preview smoke, not latency measurement or complete microphone/system-capture/export validation. The source pitch slider, waveform seek, lower-page sequencer controls, advanced editor, and lifecycle stress remain unverified on this APK.

Final packaging for this UI milestone:

- version commit: `d273fe9997afa34c23868be0477b57fddcd198ae` (`versionCode=2`, `versionName=0.1.1`);
- final main CI: `31321683089`, all Android verification steps PASS;
- tag: `v0.1.1-preview.1`;
- release workflow: `31321828427`, build/package and publish jobs PASS;
- public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.1.1-preview.1`;
- public APK: `ChopLab-v0.1.1-preview.1-debug.apk`, 29,920,144 bytes;
- public APK SHA-256: `F4C1C47066771ABF4FD47AB1F72C06A442A30FA7B6EE13B1ADC6777C416EFB6A`.

The exact public Release APK was installed on the Pixel 9a after a second content-free data check again found only cache/profile files. Package inspection reports `versionCode=2`, `versionName=0.1.1`, `minSdk=29`, and `targetSdk=36`; `MainActivity` is top-resumed and the immediate logcat query found no fatal exception.

## Public preview packaging — 2026-08-09

The repository is now prepared for public preview publication at:

- `https://github.com/dj-thank/choplab-sampler`
- public-release branch: `agent/public-choplab-release`
- release workflow: `.github/workflows/release.yml`

The successful GitHub Actions run `31319111062` verified the public branch with:

- `scripts/validate_project.sh`: PASS.
- `:app:testDebugUnitTest`: PASS.
- `:app:lintDebug`: PASS with warnings but no errors.
- `:app:assembleDebug`: PASS.
- debug APK artifact: `choplab-debug-and-reports`.
- downloaded APK SHA-256: `07A53C695D7A229816E0FC0F53C4B5C9F270C705228DE7320008B4074785FE67`.
- public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.1.0-preview.1`.
- release workflow run `31319529630`: `build-and-package` and `publish-release` PASS.
- public Release APK: `ChopLab-v0.1.0-preview.1-debug.apk`.
- public Release APK SHA-256: `4E6220484F5991B34792CBCFCC5B251460893D9433DD2BE06A6B4635BCBEA513`.

The CI artifact was first installed onto the connected physical Pixel 9a. The public Release APK uses a different debug signing key, so Android correctly rejected an in-place update; a read-only app-data check found only cache/compiled-profile files and no project files. The old preview was then uninstalled and the public Release APK was installed successfully. Package `com.choplab.sampler` reports version `0.1.0`, `minSdk=29`, `targetSdk=36`; `MainActivity` launched and the immediate logcat check found no fatal exception. This is launch/install evidence only, not a complete audio workflow or latency result.

## Target-machine verification — 2026-08-09

Observed on Windows 11 with workspace-local Temurin JDK 17, Kotlin 2.3.21, Gradle 9.5.0, Android command-line tools 22.0, and adb 37.0.1:

- `scripts/validate_project.sh` passed after the domain changes.
- Six pure Kotlin JUnit test classes passed: `OK (12 tests)`.
- The Gradle wrapper starts successfully on JDK 17.
- The local Windows shell does not have `local.properties` or Platform 36 / Build Tools 36.0.0 installed, so the Android Gradle tasks were not run locally. The public GitHub Actions run listed above supplied the Android SDK and passed the Android tests, Lint, and debug APK build.
- adb sees a physical Pixel 9a as `device` on Android 16 / API 36 / `arm64-v8a`.
- `com.choplab.sampler` is installed from the successful CI debug artifact and launched once without an immediate fatal exception.
- Portable JDK/SDK/Kotlin/Gradle storage and Gradle/build/test output paths now resolve through verified NTFS junctions to `F:`. The offline validation and adb device check passed again after relocation.

Source-only foundations added in this checkpoint:

- Immutable, stereo-capable `PcmBuffer` validation and bounded versioned project models, including metadata and pattern-event limits.
- Legacy MVP state-to-project adapter.
- Pure pad-range assignment command shared by the ViewModel and host tests.
- Playback and pattern-render service interfaces for incremental legacy/native coexistence.

These foundations are host-tested but are not yet a user-visible Pro implementation. The public CI debug build and initial device launch are verified; complete device audio workflow, permissions, lifecycle, and latency tests remain open.

## Buildable baseline

The active Gradle project is the MVP under the repository root.

Implemented in the baseline:

- Android 10 / API 29 minimum.
- Audio import through SAF and MediaCodec.
- Microphone recording.
- Android Playback Capture for sources that allow capture.
- Mono PCM internal representation.
- Waveform range selection, zoom, scroll, manual/equal/transient chopping, zero-crossing snap.
- 4 banks × 32 pads, presented as two fixed 16-pad pages per bank.
- Auto-next pad/slice assignment.
- AudioTrack-based low-latency playback.
- Per-pad pitch-by-rate, tone, gain, reverse, one-shot/gate, choke.
- 16-step sequencing, BPM, swing, quantized recording.
- Four-bar mono WAV export.

The offline project validation script passed when this workspace was prepared:

```text
PASS: project-level offline validation completed
```

A complete Android SDK/NDK build was not run locally. The public Android debug build is verified in GitHub Actions; native NDK/Oboe targets remain unimplemented and therefore are not claimed.

## Intended Pro target

The requested target adds:

- Oboe/AAudio native audio engine.
- stereo-aware project migration beyond the current mono MVP archive and real-device lifecycle durability;
- deeper history policies beyond the current bounded MVP Undo/Redo;
- Stereo import, playback, processing, and export.
- Pitch-independent time stretch.
- ADSR.
- LFO.
- Pad and master effects.
- USB/Bluetooth/virtual MIDI, velocity, CC learn, clock and transport.
- Multiple patterns and Song mode.
- Master and stem export.

## Reference material

`reference/pro-v0.2/` contains partial source and design documents for the Pro target. It includes:

- `SamplerCore.cpp`
- `NativeBridge.cpp`
- `OfflineRenderer.kt`
- `ProjectArchive.kt`
- `MidiController.kt`
- architecture and feature documents

These artifacts are not wired into the root Gradle project and have missing dependencies. They are design input, not a completed implementation.

## Immediate next milestone

1. Keep the public preview CI and tag-release path green.
2. Continue `plans/active/choplab-pro-integration.md` as the native/pro migration plan.
3. Define a versioned stereo-capable domain model and pure tests.
4. Add NDK/CMake/Oboe infrastructure and a minimal native tone/single-sample proof before replacing the existing engine.
5. Migrate one vertical feature at a time while keeping the application buildable.

## Evidence policy

Update this file only with observed facts. Record exact commands, dates, device/API levels, ABI, and output paths. Separate:

- source implemented;
- host/unit tested;
- Gradle built;
- emulator tested;
- physical-device tested;
- latency measured.

## Workspace preparation checks

See `docs/PREPARATION_VALIDATION.md`. Offline validation and configuration syntax checks passed locally; the public GitHub Actions run established the current Android debug build status.
