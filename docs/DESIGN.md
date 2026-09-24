# 画面と操作の契約

現行はsharedの `OtohiroiDeck` をAndroid/Windowsで共有する4工程です。ここで示す5画面への変更は段階2Bから順に実装します。進捗は [ROADMAP](ROADMAP.md)、機能は [PRODUCT](PRODUCT.md) が正本です。

## 再構築の構成

共通の上部バーに保存・Undo/Redo・再生・BPM。Androidは下部、Windowsは左に「入れる / チョップ / ビート / 歌う / 仕上げ」。各画面に次の一手を1行で示し、主操作を明確にして詳細はsheetへ分けます。設定と書出しは共通入口から到達でき、狭い幅では上部を2段まで使います。

- SOURCE: ライブラリ/ファイル/録音/オンラインの入口、候補と取得済みの区別、取得/分離の進捗・取消。
- CHOP: 元音全体と選択範囲を取り違えない波形、tap/drag/S/E微調整、ライブ/自動chop、PAD割当。
- BEAT: BANK A–Hと16PAD、PAD名を固定したstep grid、tempo/swing/quantize、演奏録音、pattern/song、scratch。総stepと表示列は別。
- VOCAL: 大きな同期歌詞、現在行・次行・count-in、行tap seek/timing入力、take/comp、guideと自分の声の区別。
- MIX: trackのgain/pan/mute/solo/meter、FX、master/stem出力点、長さ/bit depth/LRCを含む書出し。

128PAD×全stepを縮小して並べず、BANK選択・横scroll/pageで触れる大きさを保ちます。見た目だけのNEXT入口を置かず、実装host/screenと専用データ領域へつなぎます。

## 見た目と言葉

焦げorange `#8A3D00`、orange `#FFB15E` / `#FF7A1A`、green `#577D32`、cream `#EFE6D0`、dark `#14110A`を出発点に、柔らかい角丸と読みやすい階層を保ちます。本文14–16sp、主button16sp以上を基本とし、文字contrast4.5:1以上、主要操作48dp以上を確認します。色だけで選択・録音・errorを伝えません。

日本語「おとひろい」、英語「Earth Song」。新UIはja/en resourcesを使い、日英2段ラベルを廃止します。coreのtyped NoticeをUIで翻訳し、文言による制御分岐を作りません。Material Symbolsを採る場合はlicenseをNOTICEへ追加します。AKAI/MPCのロゴ・固有の外観・assetは使いません。

## 維持する操作の意味

- 復旧中はLOADING。新規と復元済みを区別し、読み込んだ制作へstarter kitを勝手に足さない。
- PAD identity、選択、BANKを一度に更新。割当/再生拒否なら成功状態や遷移へ進まない。
- scroll中のPAD誤爆を防ぎ、tap・hold・GATEの所有とcancelを明確にする。pressの所有はnavigation/cancelでも一度だけ解放。現行の基準は [ADR5](adr/ADR-0005-guided-first-screen-flow.md)。
- 空PADの編集やbusy中の破壊操作を受付けず、拒否理由を示す。変更確認は対象内容と期限に結び付け、選択変更後に古い確認を使わない。
- 調整は示しているPADへ適用し、Undoを保つ。scratch終了は有効な先行playbackへ一度だけ復帰し、先行再生がなければ止まる。
- Source/PAD/Beatや録音のstatusを実際の状態に合わせる。metadata、ダウンロード、decode、保存成功を同じ「追加済み」にしない。

## 確認する画面と入力

スマホ縦/横、tablet、Windows、font scale1.0/1.3/2.0、狭い幅/高さで、全操作へ到達・scroll・focus・keyboard・48dp・ラベル切れを確認します。画像はsame stateで比較し、ImageComposeSceneのPNGと入力testをセットにします。

PAD semanticsは完全なBANK/PAD名、割当、play mode、content kindを保持します。sliderの値・増減、checked state、disabled理由、statusの全文にも到達可能にします。Windowsのnative menu/dialogはWindowsで、TalkBackの読み上げ/順序は実機で確認します。PNGやsemantics callbackを人間の操作感・音・読み上げの代用にしません。
