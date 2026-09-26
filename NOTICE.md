# Third-party notices / 第三者ソフトウェア

ChopLab / おとひろい / Earth Song のオリジナルコードは [MIT License](LICENSE) で提供します。
第三者のライブラリ、実行環境、モデル、同梱ツールには各提供元の条件が適用されます。
この索引だけで、配布物のライセンス本文や対応するソースの提供を代替しません。

## Built-in sounds

DUSTY JAZZ、BOOM BAP、VINYL SOUL、LO-FI TAPE、CLEAN STUDIO は、
`shared/src/commonMain/kotlin/com/choplab/sampler/audio/BuiltInDrumKits.kt` にある
独自の決定的PCM合成です。第三者の録音素材をコピー・ダウンロードしたものではありません。
この実装と生成音はChopLabのMIT Licenseで提供します。利用者が持ち込む素材の権利は別です。

## Current dependency families

実際のバージョンはGradle設定・バージョンカタログを正本とし、ReleaseのSBOMで解決済みの依存を記録します。
推移依存とネイティブ部品も、最終APK/Windows zipに入った実物を確認します。

| Family | Upstream and license information | Distribution handling |
|---|---|---|
| Kotlin、coroutines、serialization | [JetBrains Kotlin](https://github.com/JetBrains/kotlin)、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)、[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) / Apache-2.0 | 各ライセンスと著作権表示を保持 |
| AndroidX、Jetpack Compose | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) / Apache-2.0 | 解決されたAAR/JARのnoticeを保持 |
| Compose Multiplatform、Skiko | [Compose](https://github.com/JetBrains/compose-multiplatform)、[Skiko](https://github.com/JetBrains/skiko) / Apache-2.0 | Skiaなど内包ネイティブ部品のnoticeも保持 |
| Java Native Access | [JNA](https://github.com/java-native-access/jna) / Apache-2.0 または LGPL-2.1-or-later | 選択した配布条件と同梱native noticeを保持 |
| ONNX Runtime | [ONNX Runtime](https://github.com/microsoft/onnxruntime) / MIT | ThirdPartyNoticesも含め、Android/Windowsそれぞれのartifactを確認 |
| youtubedl-android | [youtubedl-android](https://github.com/yausername/youtubedl-android) / GPL-3.0 | 現在のAndroid取込に同梱。Python/FFmpeg等の条件と対応sourceを含めて扱う |
| yt-dlp | [yt-dlp](https://github.com/yt-dlp/yt-dlp) / 本体Unlicense、配布EXEの依存には個別条件あり | 固定したReleaseのLICENSEとbinaryの構成を保持 |
| FFmpeg / FFprobe | [FFmpeg](https://ffmpeg.org/legal.html)、[Windows build](https://www.gyan.dev/ffmpeg/builds/) | build構成によりGPL等が適用される。実際の固定buildに対応するlicense/sourceを提供 |
| Node.js | [Node.js](https://github.com/nodejs/node) / MITおよび同梱部品の条件 | 固定versionのLICENSEをtoolと一緒に保持 |
| Drum separation model | [StemSplitio/htdemucs-ft-drums-onnx](https://huggingface.co/StemSplitio/htdemucs-ft-drums-onnx) / model cardのMIT条件 | 固定commit・hash・model cardと元Demucsのattributionを保持 |
| Desktop Java runtime | [Eclipse Temurin](https://adoptium.net/)、[OpenJDK](https://openjdk.org/legal/) | GPL-2.0 with Classpath Exception等。runtime/legalの同梱表示を保持 |
| Gradle Wrapper / build plugins | [Gradle](https://github.com/gradle/gradle)、[CycloneDX Gradle](https://github.com/CycloneDX/cyclonedx-gradle-plugin) | Wrapper・build用依存の各licenseを保持 |

GPL部品を組み合わせた配布物には、その組合せに適用されるGPL条件と対応するsource/build手順が必要です。
ChopLab単体のMIT表示だけで、組合せ全体をMIT-onlyと扱いません。
署名鍵・token・利用者音声は対応sourceへ含めません。
旧版は `archive/pre-rebuild-v0.18.0` から追跡できます。

## Planned dependencies

NewPipeExtractor、Concentus、4stemモデル、AI SDK等は、実際に導入した時点で
正確なversion・取得元・条件・配布内容をこの文書とSBOMへ追加します。
計画に名前があるだけの部品を、現在同梱しているとは記載しません。

## Trademarks

AKAI、AKAI Professional、MPCは各権利者の商標です。
ChopLabは独立した製品で、これらの権利者との提携・後援を示しません。
第三者のロゴ、firmware、専有project形式、固有の外観は同梱しません。

## Local macOS Preview tools

Mac PreviewはHomebrewのFFmpeg/FFprobe、Nodeとそれらのdylibを私有build領域へ複製し、loader参照を同梱配置へ変更します。元のインストールは変更しません。実version、元bytes/配置変更後のhashはtool manifest、許可するnative名は `config/mac-media-tool-files.txt` が正本です。yt-dlp standaloneは2026.08.19、upstream assetのSHA-256を固定します。

このローカルbundleは一般配布のlicense適合済みartifactではありません。FFmpegのGPL構成、Nodeの内包/共有library、yt-dlp standalone内の依存のlicense本文・対応source/build手順の提供を、公証/公開前に完了する必要があります。JDKのlegal文書は内容を保ち、bundle内の参照リンクを通常ファイルとして同梱します。
