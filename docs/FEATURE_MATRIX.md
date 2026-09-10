# UI follow-up — 2026-09-11

| Area | Candidate change | Current boundary |
|---|---|---|
| Buttons/tabs/settings | Readable text hierarchy, disabled labels, keyboard focus, named +/- and slider state | Pure presentation/type diagnostics; new exact-head Compose/physical checks required |
| SAVE | >=48dp action-row budget, large-text/narrow scrolling, bounded two-column layout |60 geometry cases; real rendering checks are a separate target |
| PAD | Opaque high-contrast palette, visible press, unique BANK/address suffix |32 palette combinations,128 unique descriptions; pointer/voice logic unchanged |
| Step sequencer | Checked semantics, non-color active underline, larger numbers |Existing step policy preserved; new real UI test target added |
| Status | Read-only full text/source/PAD dialog from the header |Physical dialog focus/dismissal and screen-reader speech pending |

See [UI receipt](ui/UI_QUALITY_20260911.md) and [selected plan](../plans/active/ui-quality-20260911.md). No audio-source/PCM/schema/release change. Existing features and their revision-bound evidence remain in the linked historical matrix; this table only records the current UI delta.


## 機能マトリクス：継承する実装と確認範囲

PR96時点の完全な機能表は [FEATURE_MATRIX_HISTORY_20260910.md](FEATURE_MATRIX_HISTORY_20260910.md) に内容を変えず保管しています。音声、録音、保存、履歴、全128 PAD、4工程の実装は継承し、その過去の device / provider / public 表示を今回のUIの検証結果には読み替えません。

新しいUIの実装・テスト範囲は上表、最新の受入状況は [PROJECT_STATE.md](PROJECT_STATE.md) と [UI検証記録](ui/UI_QUALITY_20260911.md) を参照してください。
