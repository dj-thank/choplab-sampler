# ADR-0009: 構造化歌詞と時刻の根拠を保存する

- Status: Accepted
- Date: 2026-09-28

## Decision

NEXTの歌詞は表示用の行だけでなく、利用者が確認した曲名・言語・section名/種別/小節数と、行ID・本文snapshotに結び付く読みを保存する。日本語のモーラと韻母列はかなから再計算し、providerが返した数値を正解として取り込まない。鍵・prompt・provider session・cache pathは制作文書へ保存しない。

この追加にschema11を用いる。writerは11、readerは10を明示移行して11を読む。旧schema10は構造なしとし、手動のword時刻をMANUALとして保持する。archive、autosave、Undo/Redoに同じ文書を使い、元音bytesと既存の配置・歌詞を保全する。後続schemaへの拡張は新しい保護試験と現在のARCHITECTUREに記録する。

word時刻はMANUAL・RETURNED・ESTIMATEDの根拠を保持する。端末TTSにword timingがない時は推定と表示し、実測や音声認識の時刻として扱わない。FlowPlannerは1小節・2小節・倍速を音楽tickで明示配置し、16分あたりの密度を助言する。duration変更で以前のword anchorを移植しない。

本文が変わった読みは訂正できるよう残すが、そのまま生成に使わない。行を削除したらその読みを除去し、同じ位置の別行へ転用しない。構造/読みが不足・古い、範囲が不正な時はtyped拒否とする。提案・試聴・確定を分け、明示適用を1 Undoとする。

## Verification and rollback

schema10 migration、strict schema11 roundtrip、不正/重複/未知fields、読みの本文一致と再計算、line削除、timing根拠、Flow密度とtick境界を検証する。archive/新store再開・autosaveで元音hashと編集内容を照合する。現在のrevisionと検証結果は[ROADMAP](../ROADMAP.md)のみへ記録する。

rollbackは実装PRのrevertと保持した旧artifactへの復帰。schema11制作を古いreaderへ渡せると約束しない。旧readerは未知schemaを拒否するため、元制作を保持して使える版で開く。
