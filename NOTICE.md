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
| AndroidX、Jetpack Compose | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) / Apache-2.0 | 解決されたAAR/JARのnoticeを保持。AndroidX Test runner 1.7.0は対応instrumentation APKだけの明示依存 |
| Compose Multiplatform、Skiko | [Compose](https://github.com/JetBrains/compose-multiplatform)、[Skiko](https://github.com/JetBrains/skiko) / Apache-2.0 | Skiaなど内包ネイティブ部品のnoticeも保持 |
| Java Native Access | [JNA](https://github.com/java-native-access/jna) / Apache-2.0 または LGPL-2.1-or-later | 選択した配布条件と同梱native noticeを保持 |
| ONNX Runtime | [ONNX Runtime](https://github.com/microsoft/onnxruntime) / MIT | ThirdPartyNoticesも含め、Android/Windowsそれぞれのartifactを確認 |
| youtubedl-android | [youtubedl-android](https://github.com/yausername/youtubedl-android) / GPL-3.0 | 現在のAndroid取込に同梱。Python/FFmpeg等の条件と対応sourceを含めて扱う |
| NewPipeExtractor v0.26.5 | [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5) / GPL-3.0-or-later | 新しい取込workerのruntime依存。正確なcommit・JAR/POM/module/source JARのhashと推移依存は `config/newpipe-dependencies.json` に固定。公開時は組合せのライセンス条件、対応sourceとbuild手順、表示を実物に合わせて提供 |
| NewPipeExtractorの推移依存とAndroid NIO desugar | nanojson、jsoup、jsr305、protobuf-javalite、Rhino、`desugar_jdk_libs_nio`、`desugar_jdk_libs_configuration_nio` | 固定座標・個別license表示・取得物hashは `config/newpipe-dependencies.json` を参照。最終APKに実際に含むものを照合し、必要なlicense本文・表示・source提供条件を満たす |
| yt-dlp | [yt-dlp](https://github.com/yt-dlp/yt-dlp) / 本体Unlicense、配布EXEの依存には個別条件あり | 固定したReleaseのLICENSEとbinaryの構成を保持 |
| FFmpeg / FFprobe | [FFmpeg](https://ffmpeg.org/legal.html)、[Windows build](https://www.gyan.dev/ffmpeg/builds/) | build構成によりGPL等が適用される。実際の固定buildに対応するlicense/sourceを提供 |
| Node.js | [Node.js](https://github.com/nodejs/node) / MITおよび同梱部品の条件 | 固定versionのLICENSEをtoolと一緒に保持 |
| Drum separation model | [StemSplitio/htdemucs-ft-drums-onnx](https://huggingface.co/StemSplitio/htdemucs-ft-drums-onnx) / model cardのMIT条件 | 固定commit・hash・model cardと元Demucsのattributionを保持 |
| Four-part separation model | [StemSplitio/htdemucs-onnxの固定model card](https://huggingface.co/StemSplitio/htdemucs-onnx/blob/d54ed9eb60e258ea82131c6ee14578628816456a/README.md) / MIT、元[Demucs](https://github.com/facebookresearch/demucs/blob/main/LICENSE)のattribution | 単一HT-Demucs、commit `d54ed9eb60e258ea82131c6ee14578628816456a`、`htdemucs_fp16weights.onnx` 165,612,636 bytes、SHA-256 `d05c269d0178d2a72ad484b10b11dd370193fc923201c3b27a99f848745db70a`。明示download・全size/hash検証・既存cache保全。FT drums専用モデルとは別 |
| Desktop Java runtime | [Eclipse Temurin](https://adoptium.net/)、[OpenJDK](https://openjdk.org/legal/) | GPL-2.0 with Classpath Exception等。runtime/legalの同梱表示を保持 |
| Gradle Wrapper / build plugins | [Gradle](https://github.com/gradle/gradle)、[CycloneDX Gradle](https://github.com/CycloneDX/cyclonedx-gradle-plugin) | Wrapper・build用依存の各licenseを保持 |

Androidの任意指定の音声runtime候補は、元のFFmpeg AAR 0.18.1を保持し、arm64のFFmpeg/FFprobeとその共有library ZIPの3 entryだけを置換します。FFmpegは同じ7.1.1の[公式source](https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz)からNDK r28c/API 29で再buildし、音声decoder/encoder、音声filter、container/parser/protocolとTLSを残します。不要な動画codec・描画・debug情報を除き、共有libraryの重複を避けます。libvorbis、Opus、LAME、GnuTLS等の外部libraryとPython側の共有依存は、hash固定した元AARのbytesを変更せず再利用します。Java、consumer rules、他ABIも元bytesのままです。

この候補と組み合わせる Python linkage 派生は、同じ library AAR 0.18.1 の arm64 ZIP にある link 用 alias `usr/lib/liblzma.so` だけを除きます。Android system の同名 library を私有 cache が隠す問題への互換修正です。実行時 SONAME `liblzma.so.5`、元 XZ 5.8.1 ELF、Python `_lzma` を含む 1,006 entry は内容と mode を保持します。元 Python/FFmpeg の 252 ELF の DT_NEEDED を検査し、除去名への依存がないことを recipe が要求します。再梱包に伴う ZIP 圧縮形式と bytes の変更は `config/android-ffmpeg-audio.json` に固定し、元 AAR/source の identity、25 個の他 AAR entry、依存 metadata、各ライセンス・対応 source の提供条件を維持します。codec/TLS の削除や OS library の同梱はしません。

元AAR・公式source・上流Termux recipeのcommit・対応する18組の公開header source・NDK・派生AAR/各native entryのbytes/SHA-256は `config/android-ffmpeg-audio.json`、再build手順は `scripts/build_android_audio_runtime.py` が正本です。生成receiptは依存closure、変更しないentry集合、configure内容を含みます。このFFmpeg buildは `--enable-gpl --enable-version3` を用い、GPLv3本文をnative ZIP内の `licenses/FFmpeg-COPYING.GPLv3` に保持します。外部libraryの個別license、元AARの対応sourceとTermux patch群、必要なbuild/install手順、配布物近傍のsource提供と利用者へのnotice配達は引き続き公開判定の対象です。hash一致やこの候補recipeだけで、それら全ての提供を完了したとは扱いません。

GPL部品を組み合わせた配布物には、その組合せに適用されるGPL条件と対応するsource/build手順が必要です。
ChopLab単体のMIT表示だけで、組合せ全体をMIT-onlyと扱いません。
署名鍵・token・利用者音声は対応sourceへ含めません。
旧版は `archive/pre-rebuild-v0.18.0` から追跡できます。

## Android NIO desugar 2.1.5 — source and license evidence

`config/newpipe-dependencies.json` はGoogle Mavenで取得したNIO本体とconfigurationの両JAR/POMをURL・bytes・SHA-256で固定します。`:app:verifyNewPipeDesugaring` は解決した推移依存を含む全座標と両JARを照合します。設定だけでなく、configuration JARにある29個の補助classも検査対象です。これらが最終APKへどの形で入るかはD8/R8後の配布物で確認します。

- NIO本体の[公式source候補](https://github.com/google/desugar_jdk_libs/tree/73170c345e6a762fc6a1f0301bb15218850023ef)は、NIO version 2.1.5とBazel target `maven_release_jdk11_nio` を持ち、取得archiveのbytes/hashもmanifestに固定しました。生成・改名されたclassを含む全1,459 classのsource/header対応と再build一致は未検証です。[上流LICENSE](https://github.com/google/desugar_jdk_libs/blob/73170c345e6a762fc6a1f0301bb15218850023ef/LICENSE)のClasspath例外は指定されたsource headerに対する条件であり、POMの宣言だけで全classへ拡張しません。manifestの集約licenseは `NOASSERTION` を維持します。これは上流条件が無いという意味ではありません。
- configurationの[公式R8 release-preparation commit](https://r8.googlesource.com/r8/+/c331a820ddae7dea41f41a04375b784486d0f705)をsource候補として記録しました。Google MavenのPOMと実JARの `LICENSE` はBSD-3-Clauseで一致します。生成設定と29補助classのexact source/build対応は未検証で、公式sources classifierは2026-09-28に404でした。

以下はconfiguration JAR内の `LICENSE` 全文です（1,491 bytes、SHA-256はmanifestの `license_evidence`）。この本文の保持だけで、NIO全体の例外判定、組合せの対応source提供、APK内のnotice配達が完了したとは扱いません。公開前の判定は[RELEASE](docs/RELEASE.md#newpipenioを含む配布の条件)に従います。

```text
Copyright (c) 2016, the R8 project authors.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of Google Inc. nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Planned dependencies

Concentus、4stemモデル、AI SDK等は、実際に導入した時点で
正確なversion・取得元・条件・配布内容をこの文書とSBOMへ追加します。
計画に名前があるだけの部品を、現在同梱しているとは記載しません。

## Trademarks

AKAI、AKAI Professional、MPCは各権利者の商標です。
ChopLabは独立した製品で、これらの権利者との提携・後援を示しません。
第三者のロゴ、firmware、専有project形式、固有の外観は同梱しません。

## Local macOS Preview tools

Mac PreviewはHomebrewのFFmpeg/FFprobe、Nodeとそれらのdylibを私有build領域へ複製し、loader参照を同梱配置へ変更します。元のインストールは変更しません。実version、元bytes/配置変更後のhashはtool manifest、許可するnative名は `config/mac-media-tool-files.txt` が正本です。yt-dlp standaloneは2026.08.19、upstream assetのSHA-256を固定します。

Mac NEXTもオンライン音源取得のため同じFFmpeg/FFprobe・Node・yt-dlpとdylibを同梱し、`config/mac-media-tool-files.txt` と同じlicense/source条件を適用します。NEXTにも既存Previewと同じcommit/SHA-256固定のドラム分離モデルを同梱し、上記モデルのライセンス条件を適用します。ローカルcodecだけの準備用部分集合は `config/mac-audio-tool-files.txt` に残します。

このローカルbundleは一般配布のlicense適合済みartifactではありません。FFmpegのGPL構成、Nodeの内包/共有library、yt-dlp standalone内の依存のlicense本文・対応source/build手順の提供を、公証/公開前に完了する必要があります。JDKのlegal文書は内容を保ち、bundle内の参照リンクを通常ファイルとして同梱します。
