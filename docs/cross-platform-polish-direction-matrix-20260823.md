# ChopLab Android / Windows EXE polish direction matrix — 2026-08-23

## Baseline and evidence boundary

対象は branch `codex/choplab-cross-platform-polish`、base `9a4e9edc2686914c28c91b2d614dfb95281935c2` / tree `4e79be4cbb8923b67076da146c420322c0dd943a` である。Android と Windows は `shared/` の `OtohiroiDeck` と sampler model を使う。既存 UI contract は Android 1080×2424 と Windows 1106×2202 を9領域へ対応づけ、`exact 4 / semantic 4 / adapted 1` としている。画像は appearance evidence であり、実音声、端末、provider、Human の証拠ではない。

2026-08-23 の fresh baseline は `:app:testDebugUnitTest :jvm-core:test :desktop:test` が `BUILD SUCCESSFUL`。Pixel は同日20:03 JSTの `adb devices -l` で未接続だったため、この branch の fresh `DEVICE_PASS` はない。別 task の `codex/choplab-spotify-connect` とその device receipt は別 revision・別所有物であり、この判断へ昇格させない。

## Selection criteria

1. 取り込み／録音から playable PAD まで、利用者が手動で状態を修復しなくてよい。
2. Android と Windows で同じ制作語彙と状態遷移になる。
3. 失敗しても既存制作と autosave を失わない。
4. 公開 controller seam の deterministic test で回帰を検出できる。
5. Pixel、実音、Spotify、公開を必要とせず `LOCAL_PASS` を先に閉じられる。

## Directions

| Direction | User promise | Smallest falsifiable slice | Upside | Main downside | Decision |
|---|---|---|---|---|---|
| A. Production continuity parity | AndroidでもEXEでも、素材を入れた後の工程、Beatに合わせたVOICE、再起動後の制作保全が途切れない | Desktop controller の source-capture route、vocal loop restart、startup autosaveをpublic state/audio-port testでRED→GREEN | 最も大きい実害を小さい差分で解消し、共通4工程の意味を守る | Windows recorder adapterのtest seamをinterface型へ深める必要がある | **Selected primary** |
| B. Responsive visual retune | compact phone、landscape、Windows resizeで主要操作が常に収まる | 360×640、412×820、1280×800、200% DPIの同一state capture | 見た目の印象をさらに改善できる | shared deckは既にportrait/landscapeを分岐し、現在はfresh landscape captureがない | Deferred fallback after A |
| C. Status and recovery copy pass | エラー時に次の一手が分かる | loading/error/retry stateのcopy tableとUI contract | 初心者の迷いを減らす | Aの状態修正前にcopyだけ直すと誤った状態を説明する | Fold into A where touched |
| D. Native audio / latency | EXEでも楽器として低遅延に鳴る | 一つのpresent Windows endpointでlatency/underrun計測 | 最終品質への効果が大きい | current host/device/driverとHuman評価が必要 | Blocked external/device lane |
| E. Broad reskin or feature expansion | 新鮮な外観や機能を増やす | mockup only | 目新しさ | 既存9領域と制作継続の回帰リスクが高く、現問題を解かない | Rejected for this run |

## Selected implementation contract

Primary direction A を `plans/active/cross-platform-production-continuity-20260823.md` で実装する。TDD seam は次の三つに固定する。

- `DesktopSamplerController.state`: source recording stop後に `ProjectLaunchTarget.CHOP` と新しい launch revision を公開する。
- `DesktopSamplerAudioEngine` + `DesktopAudioRecorder`: vocal開始成功時に選択Beat loopを先頭から再始動し、recording stateとloop stateが一致する。
- `AtomicProjectStore`: command-line `.choplab` 起動では古いautosave recoveryだけをskipし、その後の編集autosaveは継続する。

Pixelが再接続した場合だけ、revision-bound APKのhash/signer/package/versionを確認し、データ保持型 `adb install -r` と非録音journeyを別receiptで行う。録音、端末音声取得、データ消去、Spotify認証、push/releaseは対象外。
