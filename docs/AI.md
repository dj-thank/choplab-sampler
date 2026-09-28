# AIと練習の契約

段階6の作詞/TTSと段階10のcoachに向けた要件です。0.18.0にこれらのAI機能が実装済みとは扱いません。進捗は [ROADMAP](ROADMAP.md)。端末内の基本制作はキーやcloud接続なしで完結します。

## 提案と接続

AIはsnapshotからproposalを作り、試聴/差分→明示適用→1回Undoを通します。音量・無音・chop位置などのlocal解析は再現可能な処理を優先。通信・推論・TTS・preview・disk書込みはrender外のcancel可能jobにし、revision/job IDで遅い応答を排除します。

`LlmProvider` と `TtsProvider` をcore portへ隔離します。初回はGoogle作詞＋端末/Gemini TTSの一往復を完結させ、OpenAI/互換REST、Anthropic、OpenRouter、DeepSeek、Groq、Ollama、LM Studio、ElevenLabs、VOICEVOX等は個別契約を通して追加する候補です。base URL互換だけでschema/stream/usage/timeout/errorやtool機能が同じとはしません。model名・無料枠・利用条件は採用時の公式情報と実アカウントで確認し、永続の既定に固定しません。

SDKはAPI29/R8、通信基盤の重複、APK増分を計測し、必要なら薄いRESTにします。TTSはMIME/形式からdecodeし、WAV応答へraw PCM用headerを二重付加しません。端末TTSの声の導入状況・offline可否も実機で確認します。

Googleの作詞adapterは固定HTTPS originへ薄いRESTで接続し、redirectや自動再送は許可しません。hostの利用適合判定 `LyricProviderAvailability` と毎回の送信同意は別です。未確認のhostは `UNVERIFIED` として送信を無効にし、鍵の入力やローカル試験だけで `AVAILABLE` にしません。採用時の[Google追加規約](https://ai.google.dev/gemini-api/terms)、用途・利用者・地域・アカウント条件を確認した上でhostを有効化します。モデル名を利用者のsession入力にし、無料枠や料金を固定の既定にしません。

提案にはtheme、雰囲気、構成、韻、保持行など明示入力だけを送り、制作ID・音声・ファイル名を自動付加しません。応答のUTF-8、schema、文字/行/section上限を検証し、日本語のモーラと韻はかなから再計算します。鍵は当該job/sessionのメモリ内で所有し、取消/終了で破棄します。保存できる鍵の導入は下の端末別契約を満たす別実装です。

## 作詞・FlowPlanner・TTS

- 入力はtheme、雰囲気、言語、rap/歌、構成、韻、残す行。行の書直し、読み、モーラ/韻候補を比較できる。
- 出力schemaは `title / language / sections[name, kind, bars, lines[text, reading, mora, rhymeVowels]]`。文字数/sections/linesを上限付きで検証し、モーラと韻はかなからアプリが再計算する。
- 歌詞はsection→line→任意word、位置はbeatで保存。LRC/拡張LRC、行tap timing、現在/次行、loop/slow練習と接続する。
- FlowPlannerは1行1小節/2小節/倍速から開始拍と長さを計算し、16分grid密度を検証。詰込みや空き過ぎには分割/倍速を提案する。
- 行単位TTS→48kHz→無音検出→WSOLA0.6–1.6倍で割当。収まらない時は分割/読み修正/再生成を示し、黙って切らない。声試聴、行再生成、単語highlight、掛合い練習を提供する。
- 返却されたword timingと推定timingを区別。cache keyには本文/読み、provider、model/version、voice/version、style、全合成設定を含め、BPM/配置依存の加工cacheは分ける。
- 端末ガイドはVOCAL/LRCの明示入口から、読み確認→行生成/再生成→試聴→明示配置で使う。試聴はSOURCEの所有を一時取得し、終了/取消で元の原曲状態を戻す。再生tokenで古い取消が新しい試聴を止めない。録音/busy/古いrevision/閉じた画面の結果は適用せず、複数行の確定は1Undoにする。
- 端末音声は導入済みのoffline voiceだけ使い、自動downloadやcloud送信を行わない。Macはsayの固定argument、Windowsは固定Command bootstrap＋私有request/WAV、Androidはnative callbackで受ける。未知のvoice data versionはsession nonceで跨session cacheを拒否。OS設定/実行policyは変更しない。
- TtsAudioは受領workerがfinallyで閉じる所有資産。native WAV header・callback実chunk・defensive copy・resampler/WSOLA・cache/assetの固定窓は[AUDIO](AUDIO.md)の共有PCM予算へ先予約する。不足はMEMORY_LIMIT、0.6–1.6倍/30秒の境界を黙って緩めず、取消/遅着/失敗でも保持を返す。

## 鍵・同意・失敗

初回に送信項目、接続先、データ利用条件を表示します。音声は利用者が明示選択した時だけ送信。usageはprovider実値と推定を分け、timeout後の料金不明を成功/無料扱いしません。二重課金を完全に防げると約束せず、無条件に再送しません。

鍵はAndroid KeystoreのAES-GCM、Windows DPAPIを候補にし、backup/移行時の復号失敗は再入力で回復。鍵/token/raw requestはlog、制作ファイル、crash送信へ入れません。credentialをproviderへ束縛し、任意URLへ既存キーを自動転送しません。local HTTPはloopbackを基本とします。

契約試験はschema不正/上限、offline、cancel、429、未知model、認証失効、timeout、遅い応答、project変更、復号失敗、課金不明を含みます。失敗しても元の制作・確定済みテイクを保ちます。

## 練習coach

timing/pitch指標はlocalで計算し、LLMはその説明を担当します。基準melody/音符時刻/reference takeがある歌唱と、拍/発声timingを扱うrapを分けます。基準のない自由歌唱は観測pitchとして示し、音程正解率を作りません。rapではpitch点を出さず、低信頼・無声・伴奏かぶりは採点対象外と表示。ASRがない段階で歌詞内容の正誤を採点しません。

テイク履歴、苦手な行の反復、日本語の具体的助言へつなぎます。モデルの自由文だけで点数を作らず、根拠と限界を画面から確認できるようにします。声複製やメロディ付き歌唱AIは将来候補です。

構造化歌詞の保存と時刻の根拠は[ADR9](adr/ADR-0009-structured-lyrics-and-timing.md)・[ARCHITECTURE](ARCHITECTURE.md)に従う。FlowPlannerは1小節・2小節・倍速を音楽tickで明示配置し、16分あたりの密度を助言する。本文に一致しない読みや不足をtyped拒否し、duration変更時に古いword anchorを使わない。端末TTSがword timingを返さない時はESTIMATEDと表示する。
