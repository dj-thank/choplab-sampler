# 製品契約

おとひろい / Earth Song（内部名ChopLab）は、素材から自分のビートと歌を作るAndroid 10+ / Windowsアプリです。端末内で基本制作が完結し、クラウドAIは任意にします。実装状況と受入の正本は [ROADMAP](ROADMAP.md)。本書の「計画」は実装済みの主張ではありません。

## 0.18.0で引き継ぐもの

- 4工程「入れる / チョップ / ビート / 保存」と、Android/Windows共通Compose画面。
- ファイル・マイク・許可された端末音・ライブラリ取込、Spotifyの曲情報/お気に入りとYouTube音源取得。WindowsのローカルchooserはWAVを基準とし、Androidは対応MIMEをOSでdecodeする。
- ライブチョップ、範囲調整、ゼロクロス吸着、128PAD（A–D各32、16PADずつ表示）、ONE SHOT/GATE/LOOP、pitch/tone/gain/reverse/choke、scratch。
- 16stepのA/Bパターン、4小節のSong順、ループと追加レイヤー、5種の合成ドラム、基本のボーカル重ね録り、WAV書出し。
- `ProductionCommand` / `ProductionSession` の編集・副作用・Undo、自動保存3世代、検証付き `.choplab` / `.choplib`、内容アドレスのローカルライブラリ、ドラム分離。

これらは基準sourceの機能範囲です。両OSの実音、実マイク、OAuth、TalkBackや全機種での同等品質を一括して保証しません。0.18.0 writerはschema7、readerは1–7です。

## 再構築の体験と要求

| 画面 | 必須の操作 | 段階 |
|---|---|---|
| 入れる / SOURCE | ファイル・マイク・端末音・ライブラリ・選択したオンライン曲、曲情報と取得品質、4パート分離 | 2B / 3 / 9 |
| チョップ / CHOP | 波形タップ・範囲ドラッグ・S/E微調整、ライブチョップ、等分/アタック検出の自動チョップ、PAD割当 | 2A / 2B |
| ビート / BEAT | A–H×16PAD、ステップ、ライブ録音、テンポ/スウィング、曲構成、位置scratch＋CUT | 2B / 4 |
| 歌う / VOCAL | 歌詞、同期表示、録音・テイク・コンピング、TTSガイド、ピッチ補正、練習 | 5 / 6 / 8 / 10 |
| 仕上げ / MIX | ミキサー、FX、master/stems/WAV/LRC/制作ファイルの書出し | 7 |

保存・Undo/Redo・再生・BPMを共通にし、設定で音声デバイス、遅延の推定/実測、補正、AI接続、言語を扱います。範囲の詳細は [DESIGN](DESIGN.md)、[AUDIO](AUDIO.md)、[AI](AI.md) で定義します。

- **制作継続**: 起動時は最新の有効な自動保存を原子的に復元。復旧中を空の制作と表示しない。新規だけにstarterを入れ、復元/手動読込やキット変更で既存の配置を失わない。
- **PADとビート**: 128の容量を保ちページ概念を廃止。BANKの名前・色・役割は自由。pan/velocity/envelopeを追加。1–8小節、4/4・16分で総16–128step、表示列16/32/64は曲長と別。複数pattern/repeat、quantize、note repeat、録音1回=1Undo。
- **波形と再生**: 音源・PAD選択・範囲は一貫して保持。runtime操作の成功後に状態を確定し、拒否や停止失敗で成功表示へ進めない。ONE SHOT/GATEのscroll誤爆防止を残す。長い音は全PADへ複製しない。
- **録音と歌詞**: 同時録音は一つ。1–2小節count-in、pre-roll、punch-in/out、テイク保存、行ごとの非破壊comp、同期歌詞の行タップ/タイミング編集、LRC/拡張LRC、区間loop・slow練習。入力と伴奏の時刻を明示し、route変更・割込みで古い補正を無効にする。
- **音と書出し**: 48kHz stereo floatの共通engineを目標に、元音声bytesとheadroomを保持。24bit既定/16bit任意と最終量子化時のディザ。選択長・tail・stemsの出力点を明示し、暗黙の4小節切捨てをなくす。
- **保存**: schema10へ刷新し、旧編集状態の完全互換は約束しない。音声救出を実fixtureで検証して元を残す。Previewは旧アプリ私有領域へ直接アクセスできると仮定しない。

## 取込の契約

取込の状態は「曲情報/候補 → 取得中 → decode検証 → ライブラリ保存完了 → 利用可能」です。エラー・取消・project切替の遅い完了は既存制作を壊さず、元bytesの保存と派生PCM cacheを分けます。

段階3ではSpotify接続後の自動一括取込を選択式へ変更し、YouTube Music/動画検索、共有URL、ジャケット・曲名・artist・album・長さ・codec/rate/bitrateを表示します。公式性は確認できた根拠だけを表示し、一致度は校正前の確率に見せません。候補確認を既定とし、取得形式IDだけで高音質と断定しません。

SpotifyはmetadataとAPI機能、YouTubeは別サービスの音声であり、注意表示だけで連携や配布が適合したとはしません。[Spotify policy](https://developer.spotify.com/policy) と [YouTube terms](https://www.youtube.com/static?template=terms) を採用時の具体的フローと照合します。未解決の連携は一般配布へ有効化せず、ファイル/ライブラリの制作を継続可能にします。DRM・capture-policyの回避は実装しません。

## 保存する価値と見送り

ライブチョップ、Undo、合成ドラム、復旧、入力/ZIPの上限、channel identity、取消/遅着の拒否、アクセス可能な操作を維持します。見た目や件数だけを引き継ぐ移植にはしません。

iOSは [ADR6](adr/ADR-0006-android-windows-focus.md) により対象外です。DDJ-200/MIDI、歌唱AI、本人音声によるガイド、自動ASR timing、store配布、cloud同期は将来候補。scratch/mixerのadapter境界は残しますが、1.0要件に混ぜません。
