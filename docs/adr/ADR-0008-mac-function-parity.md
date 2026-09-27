# ADR-0008: Macで全制作機能を扱う

- Status: Accepted requirement; platform acceptance remains in ROADMAP
- Date: 2026-09-26

## Decision

ユーザーの指定により、Macをビルド専用hostに限定せず、Windowsと同じ制作機能を使う対象に追加する。ADR6のiOS廃止・0.18.0基準・後続別editorを混ぜない決定は維持する。Mac対応の要件化と、全機能の実機受入完了を区別する。

共有編集・音声・保存・取込ロジックは一つとし、OS固有のファイルパネル、ショートカット、録音、データ領域、ツール解決だけをadapterで分ける。4工程と選択済み案2の配置を保つ。取り込みの待ち時間、複数選択、一部失敗、取消、再試行を受入に含める。依存不足は必要ツールを整備して解消し、機能削除や品質低下で代用しない。

進捗・受入は [ROADMAP](../ROADMAP.md)、製品機能は [PRODUCT](../PRODUCT.md)、開発起動は [desktop README](../../desktop/README.md) を正本とする。Windowsの再開文書や起動ショートカットはSSHで現物とrevisionを照合し、古い配布物・未統合worktree・mainを同一視しない。既存dirty workと利用者データは保持する。

## Verification and rollback

Macの単体・UI・合成音源テストに加え、ファイル取込→編集→PAD/beat→保存/再開→WAV、マイク、システム音、分離、providerを各境界で検証する。Windowsの必須CIとnative検証も保持する。実音、権限、実アカウント、Human受入をtestや画像だけで認定しない。確認済みMac環境以外のOS版・CPUや署名済みMac配布は個別に確認する。

機能変更はPR単位のrevertを基本とし、データ移動や既存アプリ削除をrollbackにしない。iOS再開とMac公開releaseはこの決定だけで実施済み配布と扱わない。
