# iPadOS host

`apple` は共有 `ui → core → engine` を実際の UIKit アプリへ接続します。
既定の Gradle graph には含めず、`-PchoplabIosPreview=true` で有効にします。
Swift の入口は `app/AppDelegate.swift`、共有画面の入口は `MainViewController.kt` です。
この host の最小 OS は iPadOS 18 です。デコーダーは明示的な `AVAudioFile.close()` を使います。
4 工程・大きい PAD・独立した SOURCE/HAND と mixer/FX は共有 UI/EngineCore を使います。

## 接続している機能

| 機能 | 実装と境界 |
|---|---|
| 再生 | AVAudioEngine/AVAudioPlayerNode、48 kHz stereo float、独立した SOURCE/HAND/曲。出力がない場合は編集のみで、再生要求は失敗を返す |
| 素材 | UIKit のコピー取込、WAV PCM16/24/float32、その他は AVAudioFile が実際に開ける mono/stereo format。元 bytes をそのまま保持 |
| Chop/PAD/曲 | 等分・attack chop、テンポ/キー候補の解析、PAD 変換、step/pattern、note repeat、内蔵ドラム音源、共有 mixer/FX |
| 保存 | 全 bytes SHA-256、サイズ・音声メタデータ、隠しファイルを含む quota、リンク拒否、厳格 ZIP、現行共通 schema、3 世代の原子的 autosave |
| 出力 | EngineCore のオフライン描画から 24-bit WAV。Files の Documents 保存と共有 sheet |
| 再開 | 必要資産を検証して復元。全 autosave が読めないときは保全して通知。開き直した後の新編集から Undo/Redo |
| 構想 | 独立した version 1 ノート。原文・訂正・案・判断を保存、採用案を制作タイトル/歌詞へ明示適用し制作の Undo で戻す。音声入力と AI provider は未接続 |

PCM は一つの 128 MiB 予算で resident/copy/resample/作業領域/出力を確保前に予約します。
長い素材の paged PCM は未接続で、収まらない処理は失敗を返します。元ファイルは失いません。
マイク、録音 count-in、punch/comp、練習/pitch/coach、TTS、分離、library/provider、
stem export、system audio capture は未接続です。共有 UI の availability は false/null で、
成功した録音や音源を作ったようには扱いません。全制作機能の完成を意味しません。

## ローカル確認

JDK 21 と Android SDK を設定して repository root で実行します。Windows は `gradlew.bat` を使います。

```sh
./gradlew --no-daemon --max-workers=1 --console=plain -PchoplabIosPreview=true :apple:desktopTest
python3 scripts/check_repo.py
```

`desktopTest` は共通 WAV/予算・起動時資源回収の単体試験です。Apple API・実アプリの成功を証明しません。
Apple の確認には ARM64 Mac、対応 Xcode/iOS SDK と iPad Simulator が必要です。

```sh
./gradlew --no-daemon --max-workers=1 --console=plain -PchoplabIosPreview=true \
  :apple:linkDebugFrameworkIosArm64 :apple:linkDebugFrameworkIosSimulatorArm64 \
  :apple:iosSimulatorArm64Test --device "$CHOPLAB_SIMULATOR" \
  --tests 'com.choplab.apple.AppleCodecTest' --tests 'com.choplab.apple.AppleStorageTest' \
  --tests 'com.choplab.apple.AppleStudioFlowTest' --tests 'com.choplab.apple.AppleResourceOwnershipTest' \
  --tests 'com.choplab.apple.AppleInceptionStoreTest'
python3 apple/scripts/build_simulator.py --device "$CHOPLAB_SIMULATOR"
```

`.github/workflows/ipad-native-foundation.yml` は共有 Native 試験に加え、
このアプリの unsigned Simulator build、install、画面構成後の起動/再起動 readback、
ARM64/iOS Simulator identity と Compose リソース、package bytes/hash を検査します。
`dist/ipad-native-foundation-evidence/outcomes.json` と `app-results.json` を両方確認します。
失敗時の artifact の存在や、共有 framework 試験だけをアプリの成功とは扱いません。
`AppleStorageTest` は合成左右音源の取込→chop→PAD→曲→24-bit WAV→保存→再開→新編集 Undo/Redo と
破損・unknown schema・ZIP 不整合・quota・無出力・PCM 予算拒否を実行します。
`AppleStudioFlowTest` は production の `IosBackend` と `Studio` actor を起動し、
実 AVAudioEngine route が得られると SOURCE/HAND/曲を操作し、実 player の buffer 消費を確認します。
出力が得られない Native テスト runner では再生の拒否を検査し、export/archive/autosave の終了再開まで進みます。
テスト成功を音声出力の成功とは扱わず、`native-audio-capabilities.json` の `livePlayback` を確認します。
アプリ自身の出力状態は `app-capabilities.json` に別記録します。起動確認だけでは再生確認になりません。
両方に失敗段階・error domain/code・sample rate/channels を記録し、端末名や識別子は含めません。
予期しない driver 例外は利用不能とみなさず失敗にします。偽出力へ置き換えず、聴感は実機で確認します。
`AppleResourceOwnershipTest` は起動途中の取消、初回再描画前の終了、公開失敗時の資源回収を検査します。
autosave の復元が古い有効世代へ戻っても新編集を保存し、上書き先の読めない世代は別名で保全します。

構想は同じ profile 内の `inception-v1/` へ保存し、音楽制作の autosave/archive へ混入しません。
SHA-256 付き UTF-8 封筒、32 ノート/各 256 KiB/総 8 MiB、revision CAS、プロセス内排他と OS file lock を使います。
読めない既存版・未知 version・一時コピーは保全して失敗を返し、完全に検証できた一時コピーだけを回収します。
`AppleInceptionStoreTest` は再開、同時保存競合、容量不足、同サイズ破損、未知 version、UTF-8/パス、
削除と保存失敗の保全、実 controller から制作への適用/Undo を Native 上で確認します。

## 署名と所有 iPad での確認

CI の `ChopLab-iPad-Simulator.zip` は実機へ導入できる IPA ではありません。
署名担当が `apple/ChopLab.xcodeproj` を Xcode で開き、`ChopLab` scheme と所有 iPad を選択し、
Signing & Capabilities の Team とこの Preview 用 bundle ID を設定して build/run します。
鍵・provisioning profile・Team の秘密情報は repository や CI artifact へ追加しません。
保存領域はアプリ内 `Application Support/ChopLab/next-v10`、持出しは Documents です。
別アプリの既存保存領域や署名を変更しません。

実機担当は次の 5 点を source revision・実アプリ hash・OS/route と一緒に記録します。

1. 左右が異なる所有素材を Files から取り込み、元 bytes の hash を保存/再開後も照合する。
2. SOURCE を再生したまま HAND を操作・解除し、SOURCE と曲の位置が独立して保たれることを聴く。
3. PAD と曲配置から 24-bit WAV を書き出して共有し、終了→再起動→新編集 Undo/Redo を確認する。
4. 出力 route の喪失・割込・背景化で停止し、再接続操作後に意図せず自動再生しないことを確認する。
5. 回転・可変幅・大きい文字・VoiceOver を実際に操作し、未接続機能と失敗表示を確認する。

Simulator の起動は実機音声・マイク権限・VoiceOver・provider・署名配布・人の受入の証拠にはしません。
rollback は変更 commit の revert または opt-in の解除で行い、素材・制作は削除しません。
