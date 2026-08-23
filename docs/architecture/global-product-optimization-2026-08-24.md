# ChopLab 全体最適化方針 — 2026-08-24

## 結論

ChopLab は画面や機能を横へ増やす前に、`Source -> Chop -> PAD performance -> Pattern -> Save/Recover -> Export` を一つの **Production** として扱う共有セマンティック層を深くする。

Android、Windows、将来の iPhone は、同じ `ProductionCommand` を同じ規則で受理し、同じ状態遷移と必要な `ProductionEffect` を得る。OS 固有コードは、音声、録音、文書、権限、ライフサイクルという capability の実行だけを所有する。

この方向は MPC や Cubase の画面を再現する判断ではない。MPC から採用するのは「一つの 4x4 PAD surface が文脈に応じた役割を持つ」という機能モデルであり、ChopLab の価値は、少ない入口のまま素材から完成物まで意味が途切れないことに置く。

## 現行ベースライン

- 正本: `origin/main@ab68d2d9eaf2e5b9021a131f9ecc34d5063825bf`
- tree: `b3bd8be4a7cb96b3b6de54f9ff067d6457810a5f`
- 実装 worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-global-optimization-20260824`
- 既存の dirty canonical checkout は変更しない。
- v0.17.0 の source、Windows 日常利用 app-image、PR/merged-main CI は成立済み。
- binary GitHub Release は安定 Android 署名 secrets が無いため fail-closed。製品アーキテクチャ改善と署名境界は分離する。

## 制作体験のシステム地図

```text
Capture / Import
      |
      v
Source selection -- waveform cache / bounds / safe decoding
      |
      v
Chop creation ---- boundary policy / audition / PAD assignment
      |
      v
4x4 PAD surface -- performance role / trim role / step role
      |
      v
Pattern / Song --- tempo / timing / arrangement
      |
      +-----------> realtime playback
      |
      +-----------> offline export
      |
      v
Project snapshot -- history / autosave / recovery / portability

Target: every migrated command crosses one semantic spine.
Platform adapters execute audio, file, permission, and lifecycle effects.
Evidence gates remain LOCAL -> DEVICE -> PROVIDER -> PUBLIC -> HUMAN.
```

## 現在の主ボトルネック

共有 Compose deck は一つだが、`SamplerDeckController` は約 50 操作を持ち、そのほぼ全てを Android `SamplerViewModel`（約 2,258 行）と Windows `DesktopSamplerController`（約 978 行）が別々に実装している。

これは既に意味の差を生んでいる。

- Android の Source range / slice marker は最小 Chop 長と zero crossing snap を守るが、Windows は単純な clamp / list 更新である。
- Android の通常 PAD play mode は ONE_SHOT / GATE、Beat loop は別操作だが、Windows は通常切替で LOOP に入れる。
- 同じ slice 選択が Android では session-only、Windows では Undo 履歴と autosave を作る。
- Pattern の不適格 PAD に対するメッセージと同期処理が platform ごとに異なる。
- `SamplerUiState` は永続制作内容、画面選択、ロード状態、録音状態、audio-thread から観測した再生状態を同居させている。

このまま Song、MIDI、effects、AI assist を追加すると、機能数より速く parity と回帰面積が増える。

## 評価軸

各方向を 1（弱い）から 5（強い）で評価する。保守リスクは値が高いほど安全である。

| 方向 | 制作継続性 | OS parity | 音声信頼性 | 変更局所性 | 段階導入 | 合計 |
|---|---:|---:|---:|---:|---:|---:|
| A. UI / モードを先に増やす | 2 | 1 | 1 | 1 | 3 | 8 |
| B. native engine を全面先行する | 2 | 3 | 5 | 2 | 1 | 13 |
| C. DAW breadth を一括実装する | 3 | 1 | 2 | 1 | 1 | 8 |
| D. release/signing だけを最優先する | 1 | 2 | 1 | 4 | 5 | 13 |
| E. shared command/effect spine + capability ladder | 5 | 5 | 4 | 5 | 5 | 24 |
| F. 現状維持で platform controller を個別改善 | 3 | 2 | 3 | 2 | 4 | 14 |

選択は E。B は realtime/offline の音の同一性が必要になる段階で E の下へ接続する。D は配布上の独立ブロッカーとして解消するが、製品全体の主計画にはしない。

## 目標境界

### 1. Production state

保存、Undo、export の入力になる制作内容だけを表す。Source asset references、Chops、PAD settings、Patterns、Song、tempo、mix/effects を含む。

### 2. Session state

選択 PAD、選択 slice、開いている stage、progress、エラーなど、現在の作業セッションを支えるが音楽作品そのものではない状態を表す。

### 3. Runtime state

実際に適用済みの再生、録音、transport、playhead、route/focus、pending command を表す。Project archive や Undo snapshot へ暗黙に混入させない。

### 4. ProductionCommand

UI、キーボード、MIDI、将来の AI proposal が送る共通の意図。command は platform API を含まない。

### 5. ProductionEffect

受理された command が要求する `StopPad`、`RefreshPad`、`RefreshPattern`、project I/O などの capability action。adapter は effect の成功・失敗を runtime/session state へ戻す。

## 段階的 capability ladder

### Horizon 1 — semantic spine

- Source range / slice editing、PAD performance mode、selection/history policy を shared command reducer へ移す。
- `PROJECT`、`SESSION`、`NONE` mutation を区別する。
- Android / Windows が同じ contract tests を通る。
- 大規模な controller 一括置換は行わない。

### Horizon 2 — ProductionSession

- edit history、project revision、autosave admission、operation epoch を一つの application module へ集約する。
- persistent / session / runtime state の投影を明示する。
- platform controller は effects と lifecycle の adapter へ縮める。

### Horizon 3 — audio truth

- realtime と offline の event compiler / DSP primitives を共有する。
- stereo identity、time stretch、ADSR/LFO、voice stealing、route loss を tolerance と negative path 付きで検証する。
- native engine は parity harness を満たすまで legacy engine と置換しない。

### Horizon 4 — arrange and mix

- 複数 Pattern、Song sections、velocity、mix/effects、MIDI を同じ Production model へ追加する。
- UI は capability が成立したものだけ段階表示する。

### Horizon 5 — assist and release operations

- AI は `ProductionCommand` の proposal を返し、preview / accept / Undo を必須にする。
- release artifact、schema、signing、device matrix を revision-bound receipt で追跡する。

## 成功指標

- Time to first sound: 新規・復旧・project open の各入口から最初の発音までの操作数と時間。
- Production continuity: 正常終了、強制終了、失敗 autosave 後に最後の valid Production へ戻れる割合。
- Semantic parity: shared command contract の Android / Windows 差分ゼロ。
- Change amplification: 一つの編集規則変更に必要な platform-specific 実装箇所数。
- Audio equivalence: realtime / offline event と出力の tolerance 内一致。
- Safety: data loss、stale async apply、二重再生、録音混入、project corruption の negative-path pass。
- Delivery: clean commit から各 artifact、device、public readback までの gate 別 lead time。

## 初回 tracer の受入条件

1. `ProductionCommand` reducer が Source range、slice marker、slice selection、PAD performance-mode の意味を一つにする。
2. project edit と session-only change が履歴・autosave上で区別される。
3. zero crossing、最小 Chop 長、end-exclusive、録音/loading block、loop ownership release を shared tests が反証する。
4. Android と Windows controller は reducer outcome と effects を実行し、個別の重複ロジックを削除する。
5. Android/JVM/Desktop/common tests、Lint、packages が既存動作を壊さない。
6. LOCAL_PASS 後にのみ GitHub PR へ進み、merged-main checks を read back する。

## 非目標と停止条件

- MPC/Cubase の画面、assets、wording、project format、trade dress を複製しない。
- native engine、Song、MIDI、effects、AI、UI redesign を同じ tracer へ混ぜない。
- recording、Spotify auth、system audio capture、user audio extraction は検証で起動しない。
- dirty canonical checkout、project data、autosave を reset / clean / delete しない。
- Android stable signing secrets が無ければ binary Release は fail-closed のままにする。
- platform effect の失敗を state success として扱わない。
