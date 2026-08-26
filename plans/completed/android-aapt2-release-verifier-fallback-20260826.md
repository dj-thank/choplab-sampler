# Verify final Android APKs with a build-tools-only SDK

## Purpose and user-visible outcome

ChopLab の最終 Android APK を、Android command-line tools一式がなくても標準的な build-tools のみで同じrelease policyに照らして検査できるようにする。これにより、package/version、permission、debuggable、exported component、alignment、signatureのread-backを手作業へ退行させず、将来の製品waveとrelease preparationを同じfail-closed gateへ通せる。

## Current state

- exact base: `a484a96dedb1c1b6c9025d332d22de602017ae64` / tree `f325d6eab92fc80385f55deebc3aa4e698f948a6`。
- owner root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-android-verifier-fallback-20260826`。
- `scripts/verify_android_release.py` はmanifest取得前に必ず `find_android_tool("apkanalyzer")` を呼ぶ。
- installed SDK `C:/Users/rambo/AppData/Local/Android/Sdk` は build-tools `36.0.0` の `aapt2.exe`、`zipalign.exe`、`apksigner.bat`を持つが、`cmdline-tools/latest/bin/apkanalyzer.bat` と `tools/bin/apkanalyzer.bat`を持たない。
- 2026-08-26T23:10+09:00、Wave 8 unsigned release APKに対する現行 verifier は `ERROR: Cannot find Android SDK tool: apkanalyzer` / exit 1。
- 同じ APK に対する `aapt2 dump xmltree <apk> --file AndroidManifest.xml` は merged manifest の package/version/security surfaceを出力する。

## Constraints and invariants

- 変更対象は `scripts/verify_android_release.py`、`scripts/tests/test_verify_android_release.py`、このplanと直接のSSOTだけ。
- verifierの既存policyを一つも削除・緩和しない。両backendを同じ `verify_manifest` へ流す。
- `apkanalyzer` が存在すればprimary。見つかった後のcommand failureはfallbackで隠さない。
- `apkanalyzer` 不在時だけSDK-owned `aapt2`を探す。両方不在は既存同様fail closed。
- parserはmanifest treeの階層、名前空間、quoted/raw scalarを一意に扱い、malformed line、orphan attribute、複数rootを拒否する。
- signature/certificate/alignmentの実装・引数・出力契約を変更しない。秘密値や鍵を探索・保存・表示しない。
- workflow、tag、Release、GitHub、Pixel/ADB、OAuth/provider、public/Human gateはscope外。

## Architecture and interfaces

`read_manifest(apk)` をmanifest backendの唯一の入口にする。まず optional tool lookup で `apkanalyzer` の有無だけを判断し、存在すれば現行XML出力を `parse_manifest` へ渡す。存在しない場合は `aapt2 dump xmltree` を実行し、indent-based element stackから `xml.etree.ElementTree.Element` を構築する。

`aapt2` parserは各 `E:` をelement、直下の `A:` をattributeとして扱う。Android namespace URI付き属性は既存 `ANDROID` QNameへ正規化し、quoted valueはJSON stringとして厳密にdecodeする。`Raw:` は表示補助としてのみ剥がす。生成したrootは既存 `verify_manifest` に渡し、policyの二重実装を避ける。

## Milestones

### Milestone 1: Deterministic RED and backend contracts

- Scope: current environment failure and parser/backend selection tests。
- Files/interfaces expected to change: `scripts/tests/test_verify_android_release.py`、このplan。
- Implementation steps: exact APKでbaseline failureを記録し、parser未実装のRED、primary/fallback/fail-closed selection tests、security negative controlsを追加する。
- Tests/checks: `python -m unittest scripts.tests.test_verify_android_release`。
- Acceptance evidence: new tests fail for missing fallback API while existing tests remain diagnostically separate。

### Milestone 2: Fail-closed aapt2 fallback

- Scope: optional lookup、strict xmltree parser、single manifest verification path。
- Files/interfaces expected to change: `scripts/verify_android_release.py`。
- Implementation steps: parserと`read_manifest`を実装し、mainがbackend名をsafe read-backへ含める。
- Tests/checks: focused Python suite and exact Wave 8 APK verifier run。
- Acceptance evidence: all tests green; exact unsigned APK passes with `manifest_tool=aapt2`。

### Milestone 3: Repository gate and closeout

- Scope: configured local gate、package/SBOM/public-surface、docs/read-back。
- Files/interfaces expected to change: current SSOT、active plan registry、PAD receipts。
- Implementation steps: inputs/bytesが変わらない高価なGradle product gateは再実行せず、Python policy/configured validationとexact artifact read-backを実行する。planをcompletedへ移し、clean local commitsを作る。
- Tests/checks: repository-provided validation scripts、release verifier、git diff/status/read-back。
- Acceptance evidence: exact HEAD/tree、test counts、artifact hash、dirty preservation、remaining gatesがrevision-boundで記録される。

## Progress

- [x] 2026-08-26T23:10+09:00 — exact Wave 8 APKで`apkanalyzer`不在のexit 1を再現し、同じSDKの`aapt2`出力に必要security surfaceが存在することを確認。
- [x] 2026-08-26T23:15+09:00 — fallback API未実装のImportErrorをREDとして固定し、backend selection、parser malformed input、manifest security negative controlsを追加。
- [x] 2026-08-26T23:18+09:00 — strict `aapt2` parserとprimary/fallback selectionを実装。59 Python policy testsとexact APK read-backがGREEN。
- [x] 2026-08-26T23:22+09:00 — product checkpoint `e522907`、configured validation、artifact/SBOM/public-surface/read-back、Standards/Spec reviewとSSOT closeoutを完了。

## Discoveries

- `find_android_tool` はgeneric build-tools探索を既に持つため、`aapt2`の新しいpath規則は不要。
- `aapt2 xmltree` はattributesをelementより2 spaces深く出し、nested intent-filter/actionも同じ形式で出す。component判定は生成tree上のapplication direct childrenに限定すれば既存policyを保持できる。
- release APK bytesはWave 8の既存成果物をread-only入力にでき、product sourceを再buildする必要はない。
- nonliteral `debuggable` / `exported`をfalse扱いするとfallback固有の表現差を見落とすため、両backend共通policyでliteral `true` / `false`以外を拒否するよう強化した。

## Decision log

- 2026-08-26T23:12+09:00 — stereoより先に一waveだけrelease verifier availabilityを選択。理由は将来すべてのfinal APK gateへ効く横断制約であり、exact artifactとnegative controlsで局所反証できるため。次waveは製品体験へ戻す。
- 2026-08-26T23:12+09:00 — `apkanalyzer` command failure時の自動fallbackは不採用。壊れたprimaryや異なる出力を隠すため。
- 2026-08-26T23:18+09:00 — `aapt2`専用のsecurity policyは作らず、strict tree normalization後に既存`verify_manifest`を一度だけ適用。backend driftを一つのtest matrixで検出する。

## Validation log

- `python scripts/verify_android_release.py --apk <Wave8 unsigned APK> --version 0.17.0 --version-code 27`
  - 2026-08-26 / Windows / SDK build-tools 36.0.0 only
  - RED: `ERROR: Cannot find Android SDK tool: apkanalyzer`; exit 1。
- `aapt2.exe dump xmltree <Wave8 unsigned APK> --file AndroidManifest.xml`
  - 2026-08-26 / Windows
  - PASS: package/version/permission/application/component security attributesを観測。
- `python -m unittest discover -s scripts/tests -p 'test_*.py'`
  - 2026-08-26 / Python 3.12 / Windows
  - PASS: 59 tests。fallback parser/backend selectionと既存release/workflow/SBOM/public policyを含む。
- `python scripts/verify_android_release.py --apk <Wave8 unsigned APK> --version 0.17.0 --version-code 27`
  - 2026-08-26 / build-tools 36.0.0 only
  - PASS: exact 24,175,732-byte APK / SHA-256 `09B43846CBEF6356089BA3B063E22C500B0F6484A2E2E5E42B313905FF6A8944`; `manifest_tool=aapt2`; alignment PASS; unsigned candidate accepted。
- Same command with `--require-signed`
  - 2026-08-26 / Windows
  - Expected negative PASS: exit 1, unsigned APK rejected。signature policyは不変。
- `scripts/validate_project.sh`
  - 2026-08-26 / Git Bash / JDK 17
  - PASS: public surface 425、tracked executable modes、18 Gradle JVM-core/Desktop tasks、six XML files、wrapper SHA/UTF-8 policy。
- `python scripts/check_public_surface.py --history`
  - 2026-08-26 / Windows
  - PASS: 425 candidates、current tree and reachable history、credential/signing/audio findings 0。
- `python scripts/verify_sbom.py ...`
  - 2026-08-26 / existing Wave 8 artifact
  - PASS: CycloneDX 1.6、`com.choplab:ChopLab:0.17.0`、650 components / 651 dependencies、SHA-256 `23509E6C543E2C7B6E6F6FC49A6DDC7E5C463BCE4D72516AD8152697951B4FC8`。
- Product checkpoint: `e5229075150bcfb219eb05c666f46fd9eba05ad8` / tree `514e5161dd73ba49436756afa53782eeb16c47d5`。`app` / `shared` / `jvm-core` / `desktop` / root build inputsはbase `a484a96`とtree-object一致。したがってWave 8の190-task/511-test product gateとAPK/Windows/SBOM bytesは再利用可能で、同じ高価なbuildを再実行していない。

## Risks and rollback

最大のriskはindent/value parserが未知の `aapt2` 形式を誤解し、security属性を落とすこと。malformed/orphan/multiple-rootを拒否し、実APKとnegative fixturesで反証する。rollbackはこのisolated worktree/branchの未統合commitを採用しないことだけで、canonical dirty checkoutとWave 8 bytesは不変。

## Remaining device validation

なし。このwaveはfinal artifact inspectionのlocal gateだけを対象とする。実機install、audio、route loss、TalkBack、provider/public/signing/Human GOは別の権限付きtaskで扱う。
