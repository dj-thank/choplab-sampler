# ChopLab MPCフロントエンド進捗

## 現在

- 携帯・縦画面・片手操作を主対象として再編中
- Chop 2の波形に2本指ピンチ拡大縮小を追加
- 長押しからの単一音トリム編集を維持
- 試聴の二重再生防止、元曲停止、全停止を実装済み
- `WaveformViewportPolicy` の純関数境界と契約テストを追加
- 左右パン、全体表示リセット、表示範囲ミニマップ、TalkBackの前/次操作、48dpハンドル領域を追加
- `:app:testDebugUnitTest` 220件、`:app:lintDebug`、debug/release APK build 成功
- Android 12+ cloud backup/device transferを含む全保存領域のバックアップ除外を明示
- APK `outputs/ChopLab-v0.13.1-mpc-frontend-local-debug.apk` を生成（SHA-256: `6AFDBE56652C57174931BE1ABAF1D7C9D99E4E3CBEB0820FBD11AFA97D2A652D`）

## 次の完了単位

1. 全画面の戻る導線を統一
2. 停止・UNDO・REDOの配置を携帯向けに統一
3. 波形・パッド・ビートの操作を同じタッチ規約へ整理
4. APKビルド（LOCAL_PASS 済み）
5. Pixel実機でタップ、長押し、ピンチ、停止、戻るを確認

## 証拠の境界

現時点は LOCAL_PASS のみ。最新APKの端末反映と実機タッチ確認は未実施。Pixel 9a は Sanporoid 担当中のため端末待ち。
