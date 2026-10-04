# ADR-0010: iPadOS対応を再開する

- Status: Accepted requirement; implementation and platform acceptance remain in ROADMAP
- Date: 2026-10-05

## Decision

ユーザーの「所有するiPadでchoplab-samplerを使いたい」という指定により、iPadOSを開発対象へ再追加する。最初の実機受入対象はiPad A16 / Wi-Fi、申告されたOSはiPadOS 26.6。端末は通常、動作確認時にユーザーの手元にある。個人の端末情報、署名Team、UDIDや接続情報は私有メモで扱う。

[ADR6](ADR-0006-android-windows-focus.md) の対象外判断をiPadOSについて更新し、Android・Windows・[Mac](ADR-0008-mac-function-parity.md)の既存機能とデータを保持する。製品機能は [PRODUCT](../PRODUCT.md)、画面は [DESIGN](../DESIGN.md)、進捗は [ROADMAP](../ROADMAP.md) を正本とする。4工程、大きな4×4 PAD、案2の連動配置、SOURCE/HANDの独立を維持する。

現行の `ui → core → engine` をKotlin/Nativeへ接続し、Apple用hostがOSの音声・ファイル・権限・ライフサイクルを実装する。JVMの保存/ZIP/codec/音声driverや外部processをそのままiPadへ持ち込まない。共有128MiB PCM予算、元bytes・stereo・frame範囲、1回の明示確定=1Undo、取消/遅着の所有権、schema15と自動保存の保護を維持する。ストレージ容量から空きRAMや推論可否を推定しない。

旧保存点のSwiftUI/AVFoundation試作は参考資料とする。ビートsequence・制作保存/再開・WAV書出しがなく、起動時に前回の素材を消す旧実装を現行版の代用として復旧しない。旧署名なしSimulator `.app` は実機へインストールできるIPAではない。

## Implementation order

1. opt-inのNative targetをengine/core/uiへ揃え、iOSの画像decoderとframeworkを用意する。既定Android/JVMのtarget・task・配布を保持する。実Appleコンパイルは別に確認する。
2. Appleアプリ入口、実音声driver、取込/codec、内容アドレス資産、厳密な制作archive・自動保存・WAV出力を接続する。ローカル素材→チョップ→PAD/曲配置→24bit WAV→保存/再開と、編集のUndo/Redoを先に検証する。現行契約どおりUndo履歴自体は制作archiveへ保存せず、制作を開いた後の新しい編集からUndo/Redoを積む。
3. マイク・count-in・punch/comp・練習/pitch/coach・mix/FX・TTS・分離・library/providerを各adapterの能力と契約に従って接続する。未接続機能はavailabilityで明示し、fakeや無音の成功で埋めない。基本経路の試験だけで全制作機能完成としない。
4. 所有iPadへ署名した実機用アプリを導入し、実音・権限・中断/route変更・復元・VoiceOverと日常の制作を確認する。

## Build and installation

Apple SDKに対応するMac/Xcodeと署名設定が必要。ユーザーがMacを使えるか、署名担当のTeam、対応Xcode/SDKは未確認。現在の作業環境はLinuxであり、Native target宣言やLinux上の共有コード検査をAppleビルド成功へ読み替えない。

個人の実機試験にはXcodeのPersonal Teamを使えるが、[Appleの説明](https://developer.apple.com/help/account/basics/about-your-developer-account/)ではprovisioningが7日で期限切れになり、再build/再installが必要。日常利用の更新方法もこの制約を含めて決める。[TestFlight](https://developer.apple.com/testflight/)はApple Developer Program/App Store Connect側の署名・upload・必要なbeta reviewを経て配布する。受領者はiPadのTestFlightから導入できる。作成とupload、招待、実機導入の成功はそれぞれ別に記録する。

現在のAndroid/Windows公開releaseやMac previewのidentity・鍵・公開artifact集合を変更しない。iPad用bundle ID、profile領域、署名・配布経路はApple hostの実装時に確定する。

## Verification and rollback

Nativeコンパイル/テスト、Apple package/署名、実機導入、実音/入力、provider、人の受入を分ける。最低限、左右が異なる合成音、SOURCE/HAND/曲の独立、欠落PCMと容量不足の拒否、Stop/取消/遅着、元hashと全資産、保存終了/再開、Undo/Redoを同じ候補で照合する。回転・可変幅・文字倍率・VoiceOverと音声route中断を対象iPadで確認する。

rollbackは限定変更のrevertまたはopt-inを外すこと。利用者の制作・素材・既存アプリ・署名を削除して戻さない。端末情報の申告、framework生成、Simulatorや画像だけではインストール・実音・全機能の受入は成立しない。
